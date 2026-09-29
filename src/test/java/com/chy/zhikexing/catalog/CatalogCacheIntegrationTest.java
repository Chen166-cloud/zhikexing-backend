package com.chy.zhikexing.catalog;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real Redis tests. Only their random keys are deleted; no database flush. */
@EnabledIfEnvironmentVariable(named = "CATALOG_TEST_REDIS_PORT", matches = "[0-9]+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogCacheIntegrationTest {
    private CatalogCacheTestRedis first;
    private CatalogCacheTestRedis second;
    private int port;
    private int database;
    private final Set<String> keys = new HashSet<>();
    @TempDir Path temporaryDirectory;

    @BeforeAll
    void connect() {
        port = Integer.parseInt(System.getenv("CATALOG_TEST_REDIS_PORT"));
        database = Integer.parseInt(System.getenv().getOrDefault("CATALOG_TEST_REDIS_DATABASE", "15"));
        first = new CatalogCacheTestRedis(port, database);
        second = new CatalogCacheTestRedis(port, database);
    }

    @AfterEach
    void cleanKeys() {
        first.redis.delete(keys);
        keys.clear();
    }

    @AfterAll
    void disconnect() {
        if (second != null) second.close();
        if (first != null) first.close();
    }

    @Test
    void concurrentColdRequestsAcrossInstancesLoadOnce() throws Exception {
        String key = cacheKey();
        var cache1 = first.cache(new CatalogCacheProperties());
        var cache2 = second.cache(new CatalogCacheProperties());
        var loads = new AtomicInteger();
        var start = new CountDownLatch(1);
        Supplier<String> loader = () -> {
            loads.incrementAndGet();
            sleep(200);
            return "[{\"id\":1}]";
        };
        try (var pool = Executors.newFixedThreadPool(12)) {
            var requests = new ArrayList<Future<String>>();
            for (int i = 0; i < 12; i++) {
                var cache = i % 2 == 0 ? cache1 : cache2;
                requests.add(pool.submit(() -> {
                    start.await();
                    return cache.get(key, loader);
                }));
            }
            start.countDown();
            for (var request : requests) assertEquals("[{\"id\":1}]", request.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, loads.get());
    }

    @Test
    void rebuildingOneKeyDoesNotBlockAnother() throws Exception {
        String slowKey = cacheKey();
        String otherKey = cacheKey();
        var cache1 = first.cache(new CatalogCacheProperties());
        var cache2 = second.cache(new CatalogCacheProperties());
        var loading = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var slow = pool.submit(() -> cache1.get(slowKey, () -> {
                loading.countDown();
                try {
                    assertTrue(finish.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return "slow";
            }));
            try {
                assertTrue(loading.await(2, TimeUnit.SECONDS));
                var other = pool.submit(() -> cache2.get(otherKey, () -> "other"));
                assertEquals("other", other.get(2, TimeUnit.SECONDS));
            } finally {
                finish.countDown();
            }
            assertEquals("slow", slow.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void localAndSharedHitsAvoidLoadingUntilBothExpire() throws Exception {
        String key = cacheKey();
        var properties = new CatalogCacheProperties();
        properties.setLocalTtl(Duration.ofMillis(150));
        properties.setSharedTtl(Duration.ofMillis(800));
        var cache1 = first.cache(properties);
        var cache2 = second.cache(properties);
        var loads = new AtomicInteger();
        Supplier<String> loader = () -> "version-" + loads.incrementAndGet();

        assertEquals("version-1", cache1.get(key, loader));
        // Changing L2 demonstrates the first instance is still serving its L1 value.
        first.redis.opsForValue().set("catalog:v1:" + key, "shared", Duration.ofMillis(800));
        assertEquals("version-1", cache1.get(key, loader));
        assertEquals("shared", cache2.get(key, loader));
        Thread.sleep(200);
        assertEquals("shared", cache1.get(key, loader));
        assertEquals(1, loads.get());

        await(() -> !Boolean.TRUE.equals(first.redis.hasKey("catalog:v1:" + key)), Duration.ofSeconds(3));
        Thread.sleep(200);
        assertEquals("version-2", cache1.get(key, loader));
        assertEquals(2, loads.get());
    }

    @Test
    void lockTimeoutReturnsUnavailableWithoutAnUnprotectedLoad() {
        String key = cacheKey();
        var properties = new CatalogCacheProperties();
        properties.setLockWait(Duration.ofMillis(100));
        var cache = second.cache(properties);
        var lock = first.redisson.getLock("catalog:lock:v1:" + key);
        var loads = new AtomicInteger();
        lock.lock();
        try {
            var error = assertThrows(ResponseStatusException.class,
                    () -> cache.get(key, () -> "load-" + loads.incrementAndGet()));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
            assertEquals(0, loads.get());
        } finally {
            lock.unlock();
        }
    }

    @Test
    void failedLoadReleasesTheLockAndCanBeRetriedByAnotherInstance() {
        String key = cacheKey();
        var cache1 = first.cache(new CatalogCacheProperties());
        var cache2 = second.cache(new CatalogCacheProperties());
        assertThrows(IllegalStateException.class, () -> cache1.get(key, () -> {
            throw new IllegalStateException("database query failed");
        }));
        assertFalse(first.redis.hasKey("catalog:v1:" + key));
        assertEquals("recovered", cache2.get(key, () -> "recovered"));
        assertEquals("recovered", cache1.get(key, () -> fail("Should use the shared result")));
    }

    @Test
    void jsonNullIsCachedAcrossInstances() {
        String key = cacheKey();
        var cache1 = first.cache(new CatalogCacheProperties());
        var cache2 = second.cache(new CatalogCacheProperties());
        assertEquals("null", cache1.get(key, () -> "null"));
        assertEquals("null", cache1.get(key, () -> fail("Should use L1")));
        assertEquals("null", cache2.get(key, () -> fail("Should use L2")));
    }

    @Test
    void twoSeparateJavaProcessesShareTheRebuildLock() throws Exception {
        String key = cacheKey();
        String ready = coordinationKey();
        String start = coordinationKey();
        String count = coordinationKey();
        first.redis.opsForValue().set(ready, "0", Duration.ofMinutes(1));
        first.redis.opsForValue().set(count, "0", Duration.ofMinutes(1));
        Path output1 = temporaryDirectory.resolve("cache-process-1.log");
        Path output2 = temporaryDirectory.resolve("cache-process-2.log");
        Process process1 = startProcess(key, ready, start, count, output1);
        Process process2 = startProcess(key, ready, start, count, output2);
        try {
            await(() -> "2".equals(first.redis.opsForValue().get(ready)), Duration.ofSeconds(20));
            first.redis.opsForValue().set(start, "go", Duration.ofMinutes(1));
            assertTrue(process1.waitFor(15, TimeUnit.SECONDS), "First child did not finish");
            assertTrue(process2.waitFor(15, TimeUnit.SECONDS), "Second child did not finish");
            assertEquals(0, process1.exitValue(), () -> read(output1));
            assertEquals(0, process2.exitValue(), () -> read(output2));
            assertEquals("1", first.redis.opsForValue().get(count));
            assertEquals("{\"id\":1}", first.redis.opsForValue().get("catalog:v1:" + key));
        } finally {
            process1.destroyForcibly().waitFor();
            process2.destroyForcibly().waitFor();
        }
    }

    private Process startProcess(String key, String ready, String start, String count, Path output) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        // Spring's test classpath can exceed the Windows command-line length limit.
        Path arguments = output.resolveSibling(output.getFileName() + ".args");
        Files.writeString(arguments, "-cp\n\"" + classpath.replace('\\', '/') + "\"\n");
        return new ProcessBuilder(java.toString(), "@" + arguments, CatalogCacheProcess.class.getName(),
                Integer.toString(port), Integer.toString(database), key, ready, start, count)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }

    private String cacheKey() {
        String key = "test:" + UUID.randomUUID();
        keys.add("catalog:v1:" + key);
        keys.add("catalog:lock:v1:" + key);
        return key;
    }

    private String coordinationKey() {
        String key = "catalog:test:" + UUID.randomUUID();
        keys.add(key);
        return key;
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Condition timed out");
            Thread.sleep(20);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            return e.toString();
        }
    }
}
