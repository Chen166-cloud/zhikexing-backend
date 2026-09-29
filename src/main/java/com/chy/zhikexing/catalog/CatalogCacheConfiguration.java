package com.chy.zhikexing.catalog;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

@Configuration
public class CatalogCacheConfiguration {
    @Bean(destroyMethod = "shutdown")
    public RedissonClient catalogRedisson(JedisConnectionFactory factory) {
        Config config = new Config();
        config.setCodec(StringCodec.INSTANCE).setThreads(2).setNettyThreads(2);
        config.setLazyInitialization(true);
        var server = config.useSingleServer()
                .setAddress((factory.isUseSsl() ? "rediss://" : "redis://")
                        + factory.getHostName() + ":" + factory.getPort())
                .setDatabase(factory.getDatabase())
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2)
                .setConnectTimeout(1500).setTimeout(1500).setRetryAttempts(0);
        server.setUsername(factory.getStandaloneConfiguration().getUsername());
        if (factory.getPassword() != null && !factory.getPassword().isEmpty())
            server.setPassword(factory.getPassword());
        return Redisson.create(config);
    }
}
