package com.chy.zhikexing.auth;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Uses an explicitly selected Redis database and deletes only this test's known keys. */
@EnabledIfEnvironmentVariable(named = "AUTH_TEST_REDIS_PORT", matches = "[0-9]+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthLoginGuardIntegrationTest {
    private static final long USER = 99001001L;
    private static final long OTHER_USER = 99001002L;

    private JedisConnectionFactory factory;
    private StringRedisTemplate redis;
    private AuthProtectionProperties properties;
    private AuthLoginGuard first;
    private AuthLoginGuard second;

    @BeforeAll
    void connect() {
        var configuration = new RedisStandaloneConfiguration(
                "127.0.0.1", Integer.parseInt(System.getenv("AUTH_TEST_REDIS_PORT")));
        String database = System.getenv("AUTH_TEST_REDIS_DATABASE");
        configuration.setDatabase(database == null ? 15 : Integer.parseInt(database));
        factory = new JedisConnectionFactory(configuration);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @BeforeEach
    void reset() {
        clearKeys();
        properties = new AuthProtectionProperties();
        properties.setGlobalAttempts(4);
        properties.setGlobalWindowSeconds(30);
        properties.setAccountAttempts(2);
        properties.setAccountWindowSeconds(30);
        properties.setMaxConcurrent(2);
        properties.setFailureThreshold(3);
        properties.setFailureWindowSeconds(600);
        properties.setCooldownSeconds(1);
        createGuards();
    }

    @AfterAll
    void disconnect() {
        clearKeys();
        factory.destroy();
    }

    @Test
    void globalLimitIsAtomicAndSharedByInstances() throws Exception {
        List<Callable<Boolean>> requests = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            AuthLoginGuard guard = i % 2 == 0 ? first : second;
            requests.add(() -> accepted(guard::checkRequestRate));
        }
        assertEquals(4, acceptedCount(requests));
        assertTrue(assertThrows(AuthLoginGuard.Rejected.class, first::checkRequestRate)
                .getRetryAfterSeconds() > 0);
    }

    @Test
    void globalWindowExpiresWithoutManualReset() throws InterruptedException {
        properties.setGlobalAttempts(1);
        properties.setGlobalWindowSeconds(1);
        createGuards();
        first.checkRequestRate();
        assertThrows(AuthLoginGuard.Rejected.class, second::checkRequestRate);
        Thread.sleep(1200);
        assertDoesNotThrow(second::checkRequestRate);
    }

    @Test
    void accountLimitIsSharedAndAccountsAreIndependent() throws Exception {
        List<Callable<Boolean>> requests = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            AuthLoginGuard guard = i % 2 == 0 ? first : second;
            requests.add(() -> accepted(() -> guard.checkAccount(USER)));
        }
        assertEquals(2, acceptedCount(requests));
        assertDoesNotThrow(() -> first.checkAccount(OTHER_USER));
        assertDoesNotThrow(() -> second.checkAccount(OTHER_USER));
        assertThrows(AuthLoginGuard.Rejected.class, () -> second.checkAccount(OTHER_USER));
    }

    @Test
    void concurrentFailuresTriggerSharedCooldownAndRejectedChecksDoNotExtendIt()
            throws Exception {
        properties.setAccountAttempts(50);
        createGuards();
        List<Callable<Boolean>> failures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            AuthLoginGuard guard = i % 2 == 0 ? first : second;
            failures.add(() -> accepted(() -> guard.loginFailed(USER)));
        }
        acceptedCount(failures);
        assertThrows(AuthLoginGuard.Rejected.class, () -> second.checkAccount(USER));
        assertDoesNotThrow(() -> second.checkAccount(OTHER_USER));
        long before = redis.getExpire(cooldownKey(USER), TimeUnit.MILLISECONDS);
        Thread.sleep(100);
        assertThrows(AuthLoginGuard.Rejected.class, () -> first.checkAccount(USER));
        long after = redis.getExpire(cooldownKey(USER), TimeUnit.MILLISECONDS);
        assertTrue(after <= before - 50, "A rejected check must not restart the cooldown");
        Thread.sleep(1200);
        assertDoesNotThrow(() -> second.checkAccount(USER));
    }

    @Test
    void successfulLoginClearsFailuresAndCooldown() {
        first.loginFailed(USER);
        first.loginFailed(USER);
        first.loginSucceeded(USER);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(failureKey(USER))));
        first.loginFailed(USER);
        second.loginFailed(USER);
        assertDoesNotThrow(() -> second.checkAccount(USER));
        accepted(() -> first.loginFailed(USER));
        assertThrows(AuthLoginGuard.Rejected.class, () -> first.checkAccount(USER));
        second.loginSucceeded(USER);
        assertDoesNotThrow(() -> first.checkAccount(USER));
    }

    @Test
    void concurrencyLimitRejectsImmediatelyAndExceptionReleasesPermit() throws Exception {
        try (var one = first.acquire(); var two = first.acquire()) {
            assertTimeoutPreemptively(Duration.ofMillis(250),
                    () -> assertThrows(AuthLoginGuard.Rejected.class, first::acquire));
        }
        assertThrows(IllegalStateException.class, () -> {
            try (var ignored = first.acquire()) {
                throw new IllegalStateException("password operation failed");
            }
        });
        try (var one = first.acquire(); var two = first.acquire()) {
            assertThrows(AuthLoginGuard.Rejected.class, first::acquire);
        }
    }

    private void createGuards() {
        first = new AuthLoginGuard(redis, properties);
        second = new AuthLoginGuard(redis, properties);
    }

    private boolean accepted(Runnable request) {
        try {
            request.run();
            return true;
        } catch (AuthLoginGuard.Rejected rejected) {
            return false;
        }
    }

    private long acceptedCount(List<Callable<Boolean>> requests) throws Exception {
        long accepted = 0;
        try (var pool = Executors.newFixedThreadPool(12)) {
            for (var result : pool.invokeAll(requests)) {
                if (result.get()) accepted++;
            }
        }
        return accepted;
    }

    private void clearKeys() {
        redis.delete(List.of("login:guard:global",
                "login:guard:rate:" + USER, "login:guard:rate:" + OTHER_USER,
                failureKey(USER), failureKey(OTHER_USER), cooldownKey(USER), cooldownKey(OTHER_USER)));
    }

    private String failureKey(long userId) {
        return "login:guard:failures:" + userId;
    }

    private String cooldownKey(long userId) {
        return "login:guard:cooldown:" + userId;
    }
}
