package com.chy.zhikexing.catalog;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Independent connections model a backend instance without starting Spring or MySQL. */
final class CatalogCacheTestRedis implements AutoCloseable {
    final JedisConnectionFactory factory;
    final StringRedisTemplate redis;
    final RedissonClient redisson;

    CatalogCacheTestRedis(int port, int database) {
        var standalone = new RedisStandaloneConfiguration("127.0.0.1", port);
        standalone.setDatabase(database);
        factory = new JedisConnectionFactory(standalone);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);

        var config = new Config();
        config.setThreads(2).setNettyThreads(2);
        config.useSingleServer().setAddress("redis://127.0.0.1:" + port).setDatabase(database)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        redisson = Redisson.create(config);
    }

    CatalogCache cache(CatalogCacheProperties properties) {
        return new CatalogCache(redis, redisson, properties, new SimpleMeterRegistry());
    }

    @Override
    public void close() {
        redisson.shutdown();
        factory.destroy();
    }
}
