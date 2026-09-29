package com.chy.zhikexing.auth;

import com.chy.zhikexing.entity.vo.UserDTO;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.chy.zhikexing.contants.RedisConstants.LOGIN_USER_TTL;
import static com.chy.zhikexing.contants.RedisConstants.LOGIN_USER_KEY;

/** Bounded sliding sessions for the project's standalone Redis deployment. */
@Service
public class AuthSessionService {
    static final String TOKEN_PREFIX = LOGIN_USER_KEY;
    static final String SESSION_PREFIX = "login:v2:sessions:";
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[0-9a-f]{32}");
    private static final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(LOGIN_USER_TTL);
    private static final DefaultRedisScript<Long> CREATE = script("auth-session-create.lua", Long.class);
    private static final DefaultRedisScript<List> OPERATE = script("auth-session-operate.lua", List.class);

    private final StringRedisTemplate redis;
    private final AuthProtectionProperties properties;

    public AuthSessionService(StringRedisTemplate redis, AuthProtectionProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public String create(UserDTO user) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redis.execute(CREATE, List.of(SESSION_PREFIX + user.getId(), TOKEN_PREFIX + token),
                token, user.getId().toString(), user.getUserName(), user.getNickName(),
                String.valueOf(TTL_MILLIS), String.valueOf(properties.getMaxSessions()), TOKEN_PREFIX);
        return token;
    }

    public UserDTO resolveAndRefresh(String token) {
        String userId = owner(token);
        if (userId == null) return null;
        List<?> values = operate(token, userId, "refresh", "");
        if (values == null || values.isEmpty()) return null;
        UserDTO user = new UserDTO();
        user.setId(Long.valueOf(values.get(0).toString()));
        user.setUserName(values.get(1).toString());
        user.setNickName(values.get(2).toString());
        return user;
    }

    public void logout(String token) {
        String userId = owner(token);
        if (userId != null) operate(token, userId, "logout", "");
    }

    public void updateNickname(String token, UserDTO user) {
        if (validToken(token)) operate(token, user.getId().toString(), "nickname", user.getNickName());
    }

    private String owner(String token) {
        if (!validToken(token)) return null;
        Object userId = redis.opsForHash().get(TOKEN_PREFIX + token, "id");
        return userId == null ? null : userId.toString();
    }

    private List<?> operate(String token, String userId, String operation, String nickname) {
        return redis.execute(OPERATE, List.of(TOKEN_PREFIX + token, SESSION_PREFIX + userId),
                token, userId, operation, String.valueOf(TTL_MILLIS), nickname);
    }

    private boolean validToken(String token) {
        return token != null && TOKEN_FORMAT.matcher(token).matches();
    }

    private static <T> DefaultRedisScript<T> script(String filename, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/" + filename));
        script.setResultType(type);
        return script;
    }
}
