package com.chy.zhikexing.catalog;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogCacheModeTest {
    @Test
    void databaseModeQueriesEveryTimeWithoutTouchingCacheRedis() {
        var redis = mock(StringRedisTemplate.class);
        var redisson = mock(RedissonClient.class);
        var properties = new CatalogCacheProperties();
        properties.setMode(CatalogCacheProperties.Mode.DB);
        var metrics = new SimpleMeterRegistry();
        var cache = new CatalogCache(redis, redisson, properties, metrics);
        var count = new AtomicInteger();
        assertEquals("1", cache.get("course:1", () -> "" + count.incrementAndGet()));
        assertEquals("2", cache.get("course:1", () -> "" + count.incrementAndGet()));
        assertEquals(2, metrics.get("catalog.cache.loads").counter().count());
        verifyNoInteractions(redis, redisson);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisOnlyObservesSharedChangesWhileTwoLevelRetainsItsLocalValue() {
        var redis = mock(StringRedisTemplate.class);
        var values = (ValueOperations<String, String>) mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("catalog:v1:course:1")).thenReturn("old", "new");
        var properties = new CatalogCacheProperties();
        properties.setMode(CatalogCacheProperties.Mode.REDIS);
        var cache = new CatalogCache(redis, mock(RedissonClient.class), properties, new SimpleMeterRegistry());
        assertEquals("old", cache.get("course:1", () -> fail("Unexpected database load")));
        assertEquals("new", cache.get("course:1", () -> fail("Unexpected database load")));
        properties = new CatalogCacheProperties();
        when(values.get("catalog:v1:course:2")).thenReturn("local", "changed");
        cache = new CatalogCache(redis, mock(RedissonClient.class), properties, new SimpleMeterRegistry());
        assertEquals("local", cache.get("course:2", () -> fail("Unexpected database load")));
        assertEquals("local", cache.get("course:2", () -> fail("Unexpected database load")));
        verify(values, times(1)).get("catalog:v1:course:2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void customPrefixScopesBothValueAndRebuildLock() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        var values = (ValueOperations<String, String>) mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var redisson = mock(RedissonClient.class);
        var lock = mock(RLock.class);
        when(redisson.getLock("experiment:cache:unit:lock:v1:course:1")).thenReturn(lock);
        when(lock.tryLock(1000, TimeUnit.MILLISECONDS)).thenReturn(true);
        var properties = new CatalogCacheProperties();
        properties.setKeyPrefix("experiment:cache:unit");
        var cache = new CatalogCache(redis, redisson, properties, new SimpleMeterRegistry());
        assertEquals("loaded", cache.get("course:1", () -> "loaded"));
        verify(values).set("experiment:cache:unit:v1:course:1", "loaded", properties.getSharedTtl());
        verify(lock).unlock();
    }

    @Test
    void relaxedModeNamesBindAndDefaultStaysTwoLevel() {
        assertEquals(CatalogCacheProperties.Mode.TWO_LEVEL, new CatalogCacheProperties().getMode());
        for (String mode : new String[] {"db", "redis", "two-level"}) {
            var source = new MapConfigurationPropertySource(Map.of("app.catalog-cache.mode", mode));
            var bound = new Binder(source).bind("app.catalog-cache", Bindable.of(CatalogCacheProperties.class)).get();
            assertEquals(mode, bound.getMode().name().toLowerCase().replace('_', '-'));
        }
    }
}
