package com.chy.ai.config;

import com.chy.ai.contants.SystemConstants;
import com.chy.ai.tools.CourseTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;

import static com.chy.ai.contants.SystemConstants.SERVICE_SYSTEM_PROMPT;

@Configuration
public class IiipConfiguration {

    // 使用ChatModel接口，具体实现由spring.ai.model.chat配置决定（支持DeepSeek/OpenAI等）
    @Bean
    public ChatClient normalChatClient(ChatModel chatModel, ChatMemory chatMemory){
        return ChatClient.builder(chatModel)  // 创建ChatClient工厂
//                .defaultSystem("你是lpl的wbg战队的中单选手xiaohu")
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(), // 添加日志记录advisor
                        MessageChatMemoryAdvisor.builder(chatMemory).build() // 会话记忆advisor
                )
                .build(); // 构建ChatClient实例
    }

    @Bean
    public ChatMemory chatMemory(JdbcChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    @Bean
    public ChatClient gameChatClient(
            ChatModel chatModel, ChatMemory inMemoryChatMemory){
        return ChatClient.builder(chatModel)
                .defaultSystem(SystemConstants.GAME_SYSTEM_PROMPT)
                .defaultAdvisors(
                        SimpleLoggerAdvisor.builder().build(),
                        MessageChatMemoryAdvisor.builder(inMemoryChatMemory).build()
                )
                .build();
    }

    @Bean
    public ChatMemory inMemoryChatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }

    @Bean
    public ChatClient serviceChatClient(
            ChatModel model,
            ChatMemory chatMemory,
            CourseTools courseTools) {
        return ChatClient.builder(model)
                .defaultSystem(SERVICE_SYSTEM_PROMPT)
                .defaultAdvisors(
                        SimpleLoggerAdvisor.builder().build(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                                .defaultTools(courseTools)
                                .build();
    }

    @Bean
    public VectorStore vectorStore(
            EmbeddingModel embeddingModel,
            JedisConnectionFactory jedisConnectionFactory,
            @Value("${spring.ai.vectorstore.redis.index-name:iiip-pdf-index}") String indexName,
            @Value("${spring.ai.vectorstore.redis.prefix:iiip:pdf:}") String prefix,
            @Value("${spring.ai.vectorstore.redis.initialize-schema:true}") boolean initializeSchema) {
        return RedisVectorStore
                .builder(jedisPooled(jedisConnectionFactory), embeddingModel)
                .indexName(indexName)
                .prefix(prefix)
                .initializeSchema(initializeSchema)
                .metadataFields(
                        RedisVectorStore.MetadataField.tag("chat_id"),
                        RedisVectorStore.MetadataField.tag("file_name")
                )
                .build();
    }

    private JedisPooled jedisPooled(JedisConnectionFactory jedisConnectionFactory) {
        JedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .ssl(jedisConnectionFactory.isUseSsl())
                .clientName(jedisConnectionFactory.getClientName())
                .timeoutMillis(jedisConnectionFactory.getTimeout())
                .password(jedisConnectionFactory.getPassword())
                .build();
        return new JedisPooled(
                new HostAndPort(jedisConnectionFactory.getHostName(), jedisConnectionFactory.getPort()),
                clientConfig
        );
    }

    @Bean
    public ChatClient pdfChatClient(
            ChatModel model,
            ChatMemory chatMemory,
            VectorStore vectorStore) {
        return ChatClient.builder(model)
                .defaultAdvisors(
                        SimpleLoggerAdvisor.builder().build(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        QuestionAnswerAdvisor
                                .builder(vectorStore)
                                .searchRequest(
                                        SearchRequest.builder() // 向量检索的请求参数
                                                .similarityThreshold(0.5d) // 相似度阈值
                                                .topK(2) // 返回的文档片段数量
                                                .build()
                                ).build()
                )
                .build();
    }

}
