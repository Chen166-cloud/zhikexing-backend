package com.chy.zhikexing.config;

import com.chy.zhikexing.util.SnowflakeIds;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Spring AI's JDBC writer omits its numeric ID, so adapt only that write path. */
public final class SnowflakeChatMemoryRepository implements ChatMemoryRepository {
    private final JdbcChatMemoryRepository delegate;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SnowflakeIds ids;

    public SnowflakeChatMemoryRepository(
            JdbcChatMemoryRepository delegate,
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            SnowflakeIds ids) {
        this.delegate = delegate;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tx.getTransactionManager());
        this.ids = ids;
    }

    @Override
    public List<String> findConversationIds() {
        return delegate.findConversationIds();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return delegate.findByConversationId(conversationId);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.notNull(messages, "messages cannot be null");
        Assert.noNullElements(messages, "messages cannot contain null elements");
        var supported =
                messages.stream()
                        .filter(
                                message ->
                                        !(message instanceof ToolResponseMessage)
                                                && !(message instanceof AssistantMessage assistant
                                                        && assistant.hasToolCalls()))
                        .toList();
        tx.executeWithoutResult(
                status -> {
                    jdbc.update(
                            "DELETE FROM spring_ai_chat_memory WHERE conversation_id=?",
                            conversationId);
                    jdbc.batchUpdate(
                            """
                            INSERT INTO spring_ai_chat_memory
                            (id,conversation_id,content,type,`timestamp`,sequence_id)
                            VALUES(?,?,?,?,?,?)
                            """,
                            new BatchPreparedStatementSetter() {
                                @Override
                                public void setValues(PreparedStatement statement, int index)
                                        throws SQLException {
                                    Message message = supported.get(index);
                                    Object storedTime =
                                            message.getMetadata()
                                                    .get(JdbcChatMemoryRepository.CONVERSATION_TS);
                                    Instant time =
                                            storedTime instanceof Instant value
                                                    ? value
                                                    : Instant.now();
                                    statement.setLong(1, ids.nextLong());
                                    statement.setString(2, conversationId);
                                    statement.setString(3, message.getText());
                                    statement.setString(4, message.getMessageType().name());
                                    statement.setTimestamp(5, Timestamp.from(time));
                                    statement.setLong(6, index);
                                }

                                @Override
                                public int getBatchSize() {
                                    return supported.size();
                                }
                            });
                });
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        delegate.deleteByConversationId(conversationId);
    }
}
