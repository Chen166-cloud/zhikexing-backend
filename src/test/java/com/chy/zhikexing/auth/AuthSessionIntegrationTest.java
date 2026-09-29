package com.chy.zhikexing.auth;

import com.chy.zhikexing.entity.vo.UserDTO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Uses only random test users/keys on an explicitly configured Redis; never flushes a database. */
@EnabledIfEnvironmentVariable(named = "AUTH_TEST_REDIS_PORT", matches = "[0-9]+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthSessionIntegrationTest {
    private JedisConnectionFactory factory;
    private StringRedisTemplate redis;
    private AuthProtectionProperties properties;
    private AuthSessionService sessions;
    private UserDTO user;
    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    @BeforeAll
    void connect() {
        var configuration = new RedisStandaloneConfiguration("127.0.0.1",
                Integer.parseInt(System.getenv("AUTH_TEST_REDIS_PORT")));
        String database = System.getenv("AUTH_TEST_REDIS_DATABASE");
        configuration.setDatabase(database == null ? 15 : Integer.parseInt(database));
        factory = new JedisConnectionFactory(configuration);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @BeforeEach
    void setUp() {
        properties = new AuthProtectionProperties();
        sessions = new AuthSessionService(redis, properties);
        user = new UserDTO();
        user.setId(-Math.abs(UUID.randomUUID().getLeastSignificantBits()));
        user.setUserName("session-test");
        user.setNickName("测试用户");
        keys.add(AuthSessionService.SESSION_PREFIX + user.getId());
    }

    @AfterEach
    void cleanTestKeys() {
        redis.delete(keys);
        keys.clear();
    }

    @AfterAll
    void disconnect() {
        if (factory != null) factory.destroy();
    }

    @Test
    void evictsOldestCreationEvenWhenItWasRecentlyRefreshed() {
        String first = create();
        String second = create();
        String third = create();
        redis.expire(AuthSessionService.TOKEN_PREFIX + first, Duration.ofSeconds(30));
        redis.expire(AuthSessionService.SESSION_PREFIX + user.getId(), Duration.ofSeconds(30));
        assertEquals(user, sessions.resolveAndRefresh(first));
        assertTrue(redis.getExpire(AuthSessionService.TOKEN_PREFIX + first, TimeUnit.SECONDS) > 86390);
        assertTrue(redis.getExpire(AuthSessionService.SESSION_PREFIX + user.getId(), TimeUnit.SECONDS) > 86390);

        String fourth = create();
        assertNull(sessions.resolveAndRefresh(first));
        assertFalse(redis.hasKey(AuthSessionService.TOKEN_PREFIX + first));
        assertNotNull(sessions.resolveAndRefresh(second));
        assertNotNull(sessions.resolveAndRefresh(third));
        assertNotNull(sessions.resolveAndRefresh(fourth));
        assertEquals(3L, redis.opsForZSet().zCard(AuthSessionService.SESSION_PREFIX + user.getId()));
    }

    @Test
    void removesExpiredMembersBeforeEnforcingTheLimit() throws InterruptedException {
        String first = create();
        String expired = create();
        String third = create();
        redis.expire(AuthSessionService.TOKEN_PREFIX + expired, Duration.ofMillis(10));
        Thread.sleep(20);
        String fourth = create();
        assertNull(sessions.resolveAndRefresh(expired));
        assertNotNull(sessions.resolveAndRefresh(first));
        assertNotNull(sessions.resolveAndRefresh(third));
        assertNotNull(sessions.resolveAndRefresh(fourth));
        assertNull(redis.opsForZSet().score(AuthSessionService.SESSION_PREFIX + user.getId(), expired));
    }

    @Test
    void concurrentLoginsNeverExceedTheConfiguredCap() throws Exception {
        properties.setMaxSessions(2);
        List<String> tokens = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(12)) {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) tasks.add(this::create);
            for (var future : pool.invokeAll(tasks)) tokens.add(future.get());
        }
        assertEquals(2L, redis.opsForZSet().zCard(AuthSessionService.SESSION_PREFIX + user.getId()));
        assertEquals(2L, tokens.stream().filter(token -> sessions.resolveAndRefresh(token) != null).count());
        assertEquals(2L, tokens.stream()
                .filter(token -> Boolean.TRUE.equals(redis.hasKey(AuthSessionService.TOKEN_PREFIX + token))).count());
    }

    @Test
    void logoutRacingWithRefreshAndNicknameUpdatesCannotResurrectTheToken() throws Exception {
        String token = create();
        try (var pool = Executors.newFixedThreadPool(12)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) tasks.add(() -> {
                sessions.resolveAndRefresh(token);
                sessions.updateNickname(token, user);
                return null;
            });
            tasks.add(() -> {
                sessions.logout(token);
                return null;
            });
            for (var future : pool.invokeAll(tasks)) future.get();
        }
        sessions.updateNickname(token, user);
        assertNull(sessions.resolveAndRefresh(token));
        assertFalse(redis.hasKey(AuthSessionService.TOKEN_PREFIX + token));
        assertNull(redis.opsForZSet().score(AuthSessionService.SESSION_PREFIX + user.getId(), token));
    }

    @Test
    void nicknameUpdateRequiresTheTokenOwnerAndDoesNotReviveAnEvictedToken() {
        String token = create();
        UserDTO other = new UserDTO();
        other.setId(user.getId() - 1);
        other.setNickName("错误用户");
        sessions.updateNickname(token, other);
        assertEquals(user.getNickName(), sessions.resolveAndRefresh(token).getNickName());
        user.setNickName("新昵称");
        sessions.updateNickname(token, user);
        assertEquals("新昵称", sessions.resolveAndRefresh(token).getNickName());
        for (int i = 0; i < properties.getMaxSessions(); i++) create();
        sessions.updateNickname(token, user);
        assertNull(sessions.resolveAndRefresh(token));
        assertFalse(redis.hasKey(AuthSessionService.TOKEN_PREFIX + token));
    }

    @Test
    void legacyTokensAndSessionsWithoutTheirIndexAreRejected() {
        String legacy = UUID.randomUUID().toString().replace("-", "");
        String legacyKey = "login:token:" + legacy;
        keys.add(legacyKey);
        redis.opsForHash().put(legacyKey, "id", user.getId().toString());
        assertNull(sessions.resolveAndRefresh(legacy));
        String token = create();
        redis.delete(AuthSessionService.SESSION_PREFIX + user.getId());
        sessions.updateNickname(token, user);
        assertNull(sessions.resolveAndRefresh(token));
        assertFalse(redis.hasKey(AuthSessionService.TOKEN_PREFIX + token));
    }

    private String create() {
        String token = sessions.create(user);
        keys.add(AuthSessionService.TOKEN_PREFIX + token);
        return token;
    }
}
