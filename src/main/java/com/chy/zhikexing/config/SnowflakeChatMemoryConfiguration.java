package com.chy.zhikexing.config;

import com.chy.zhikexing.util.SnowflakeIds;

import javax.sql.DataSource;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepositoryDialect;
import org.springframework.ai.chat.memory.repository.jdbc.MysqlChatMemoryRepositoryDialect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = "app.legacy-ai-enabled", havingValue = "true")
public class SnowflakeChatMemoryConfiguration {
    @Bean
    @Primary
    public ChatMemoryRepository snowflakeChatMemoryRepository(
            DataSource dataSource,
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            SnowflakeIds ids) {
        // Spring AI's MySQL dialect spells this table in uppercase. Linux MySQL can be
        // case-sensitive, while Flyway and the legacy mappers created it in lowercase.
        JdbcChatMemoryRepositoryDialect dialect = new MysqlChatMemoryRepositoryDialect() {
            @Override
            public String getSelectMessagesSql() {
                return "SELECT content, type, `timestamp` FROM spring_ai_chat_memory"
                        + " WHERE conversation_id = ? ORDER BY sequence_id";
            }

            @Override
            public String getSelectConversationIdsSql() {
                return "SELECT DISTINCT conversation_id FROM spring_ai_chat_memory";
            }

            @Override
            public String getDeleteMessagesSql() {
                return "DELETE FROM spring_ai_chat_memory WHERE conversation_id = ?";
            }
        };
        var delegate =
                JdbcChatMemoryRepository.builder()
                        .dataSource(dataSource)
                        .jdbcTemplate(jdbc)
                        .transactionManager(tx.getTransactionManager())
                        .dialect(dialect)
                        .build();
        return new SnowflakeChatMemoryRepository(delegate, jdbc, tx, ids);
    }
}
