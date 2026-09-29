package com.chy.zhikexing.auth;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Semaphore;

/** Redis shares request limits across instances; the semaphore bounds this JVM's work. */
@Component
public class AuthLoginGuard {
    private static final String PREFIX = "login:guard:";
    private static final DefaultRedisScript<Long> RATE = script("auth-rate.lua");
    private static final DefaultRedisScript<Long> FAILURE = script("auth-failure.lua");

    private final StringRedisTemplate redis;
    private final AuthProtectionProperties properties;
    private final Semaphore concurrent;

    public AuthLoginGuard(StringRedisTemplate redis, AuthProtectionProperties properties) {
        this.redis = redis;
        this.properties = properties;
        this.concurrent = new Semaphore(properties.getMaxConcurrent());
    }

    public void checkRequestRate() {
        checkRate(List.of(PREFIX + "global"), properties.getGlobalAttempts(), properties.getGlobalWindowSeconds());
    }

    // Use the database ID, so alternate spellings accepted by MySQL share one account limit.
    public void checkAccount(long userId) {
        checkRate(List.of(PREFIX + "rate:" + userId, PREFIX + "cooldown:" + userId),
                properties.getAccountAttempts(), properties.getAccountWindowSeconds());
    }

    private void checkRate(List<String> keys, int attempts, int seconds) {
        long retry = redis.execute(RATE, keys, Integer.toString(attempts), Integer.toString(seconds));
        if (retry > 0) throw new Rejected("登录尝试过于频繁，请稍后重试", (int) retry);
    }

    public void loginFailed(long userId) {
        long retry = redis.execute(FAILURE,
                List.of(PREFIX + "failures:" + userId, PREFIX + "cooldown:" + userId),
                Integer.toString(properties.getFailureThreshold()),
                Integer.toString(properties.getFailureWindowSeconds()),
                Integer.toString(properties.getCooldownSeconds()));
        if (retry > 0) throw new Rejected("登录尝试过于频繁，请稍后重试", (int) retry);
    }

    public void loginSucceeded(long userId) {
        redis.delete(List.of(PREFIX + "failures:" + userId, PREFIX + "cooldown:" + userId));
    }

    public Permit acquire() {
        if (!concurrent.tryAcquire()) {
            throw new Rejected(HttpStatus.SERVICE_UNAVAILABLE, "登录服务繁忙，请稍后重试", 1);
        }
        return new Permit();
    }

    public final class Permit implements AutoCloseable {
        @Override
        public void close() { concurrent.release(); }
    }

    private static DefaultRedisScript<Long> script(String filename) {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource("lua/" + filename));
        script.setResultType(Long.class);
        return script;
    }

    public static class Rejected extends RuntimeException {
        private final HttpStatus status;
        private final int retryAfterSeconds;

        public Rejected(String message, int retryAfterSeconds) {
            this(HttpStatus.TOO_MANY_REQUESTS, message, retryAfterSeconds);
        }

        public Rejected(HttpStatus status, String message, int retryAfterSeconds) {
            super(message);
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public HttpStatus getStatus() { return status; }
        public int getRetryAfterSeconds() { return retryAfterSeconds; }
    }
}
