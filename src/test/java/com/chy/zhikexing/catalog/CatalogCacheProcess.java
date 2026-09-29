package com.chy.zhikexing.catalog;

import java.time.Duration;

/** Child JVM used by the cross-process test; readiness and load counts live in Redis. */
public final class CatalogCacheProcess {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int database = Integer.parseInt(args[1]);
        String key = args[2];
        try (var connection = new CatalogCacheTestRedis(port, database)) {
            var properties = new CatalogCacheProperties();
            properties.setLockWait(Duration.ofSeconds(5));
            var cache = connection.cache(properties);
            connection.redis.opsForValue().increment(args[3]);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (!Boolean.TRUE.equals(connection.redis.hasKey(args[4]))) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("Start gate timed out");
                Thread.sleep(20);
            }
            String value = cache.get(key, () -> {
                connection.redis.opsForValue().increment(args[5]);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return "{\"id\":1}";
            });
            if (!"{\"id\":1}".equals(value)) throw new IllegalStateException("Unexpected cached value");
        }
    }
}
