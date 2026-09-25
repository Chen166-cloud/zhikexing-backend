package com.chy.zhikexing.config;

import static com.chy.zhikexing.contants.SystemConstants.SERVICE_SYSTEM_PROMPT;

import com.chy.zhikexing.contants.SystemConstants;
import com.chy.zhikexing.tools.CourseTools;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.RedisClient;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "app.legacy-ai-enabled",
        havingValue = "true")
public class ZhikexingConfiguration {

    // 旧入口继续使用百炼兼容接口；主 Agent 流程由独立运行服务负责。
    @Bean
    public ChatClient normalChatClient(ChatModel chatModel, ChatMemory chatMemory) {
        return ChatClient.builder(chatModel) // 创建ChatClient工厂
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build() // 会话记忆advisor
                        )
                .build(); // 构建ChatClient实例
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    @Bean
    public ChatClient gameChatClient(ChatModel chatModel, ChatMemory inMemoryChatMemory) {
        return ChatClient.builder(chatModel)
                .defaultSystem(SystemConstants.GAME_SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(inMemoryChatMemory).build())
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
            ChatModel model, ChatMemory chatMemory, CourseTools courseTools) {
        return ChatClient.builder(model)
                .defaultSystem(SERVICE_SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(courseTools)
                .build();
    }

    @Bean
    public VectorStore vectorStore(
            EmbeddingModel embeddingModel,
            RedisClient legacyVectorRedisClient,
            @Value("${spring.ai.vectorstore.redis.index-name:zhikexing-pdf-index}") String indexName,
            @Value("${spring.ai.vectorstore.redis.prefix:zhikexing:pdf:}") String prefix,
            @Value("${spring.ai.vectorstore.redis.initialize-schema:true}")
                    boolean initializeSchema) {
        return RedisVectorStore.builder(legacyVectorRedisClient, embeddingModel)
                .indexName(indexName)
                .prefix(prefix)
                .initializeSchema(initializeSchema)
                .metadataFields(
                        RedisVectorStore.MetadataField.tag("chat_id"),
                        RedisVectorStore.MetadataField.tag("user_id"),
                        RedisVectorStore.MetadataField.tag("file_name"))
                .build();
    }

    @Bean(destroyMethod = "close")
    public RedisClient legacyVectorRedisClient(JedisConnectionFactory jedisConnectionFactory) {
        JedisClientConfig clientConfig =
                DefaultJedisClientConfig.builder()
                        .ssl(jedisConnectionFactory.isUseSsl())
                        .clientName(jedisConnectionFactory.getClientName())
                        .timeoutMillis(jedisConnectionFactory.getTimeout())
                        .password(jedisConnectionFactory.getPassword())
                        .build();
        return RedisClient.builder()
                .hostAndPort(jedisConnectionFactory.getHostName(), jedisConnectionFactory.getPort())
                .clientConfig(clientConfig)
                .build();
    }

    @Bean
    public ChatClient pdfChatClient(ChatModel model, ChatMemory chatMemory) {
        return ChatClient.builder(model)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
