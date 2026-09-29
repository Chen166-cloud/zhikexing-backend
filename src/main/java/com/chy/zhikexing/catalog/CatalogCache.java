package com.chy.zhikexing.catalog;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 仅缓存课程展示数据；库存、权限和会话继续使用原有实时校验。 */
@Component
public class CatalogCache {
    private final Cache<String, String> local;
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final CatalogCacheProperties properties;
    private final MeterRegistry metrics;

    public CatalogCache(StringRedisTemplate redis, RedissonClient redisson,
            CatalogCacheProperties properties, MeterRegistry metrics) {
        this.redis = redis;
        this.redisson = redisson;
        this.properties = properties;
        this.metrics = metrics;
        local = Caffeine.newBuilder().maximumSize(properties.getMaxEntries())
                .expireAfterWrite(properties.getLocalTtl()).recordStats().build();
        CaffeineCacheMetrics.monitor(metrics, local, "courseCatalog");
    }

    public String get(String key, Supplier<String> loader) {
        try {
            // 同 JVM 同 key 的请求合并加载；跨 JVM 的回源由 RLock 协调。
            return local.get(key, k -> loadShared(k, loader));
        } catch (DataAccessException | RedisException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "课程服务暂时不可用，请稍后重试", e);
        }
    }

    private String loadShared(String key, Supplier<String> loader) {
        String redisKey = "catalog:v1:" + key;
        String value = redis.opsForValue().get(redisKey);
        metrics.counter("catalog.cache.redis.requests", "result", value == null ? "miss" : "hit").increment();
        if (value != null) return value;

        var lock = redisson.getLock("catalog:lock:v1:" + key);
        try {
            if (!lock.tryLock(properties.getLockWait().toMillis(), TimeUnit.MILLISECONDS))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "课程数据正在加载，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "课程查询已中断", e);
        }
        try {
            value = redis.opsForValue().get(redisKey);
            if (value == null) {
                metrics.counter("catalog.cache.loads").increment();
                value = loader.get();
                redis.opsForValue().set(redisKey, value, properties.getSharedTtl());
            }
            return value;
        } finally {
            lock.unlock();
        }
    }
}
