package com.chy.zhikexing.trial;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class TrialInventory {
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<String> reserve = new DefaultRedisScript<>();
    private final DefaultRedisScript<Long> release = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> INIT =
            new DefaultRedisScript<>(
                    """
if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
redis.call('HSET', KEYS[1], 'state', 'LIVE', 'remaining', ARGV[1], 'start', ARGV[2], 'end', ARGV[3])
return 1
""",
                    Long.class);
    private static final DefaultRedisScript<Long> LIMIT =
            new DefaultRedisScript<>(
                    """
local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],1000) end; return n
""",
                    Long.class);

    public TrialInventory(StringRedisTemplate redis) {
        this.redis = redis;
        reserve.setLocation(new ClassPathResource("lua/trial-reserve.lua"));
        reserve.setResultType(String.class);
        release.setLocation(new ClassPathResource("lua/trial-release.lua"));
        release.setResultType(Long.class);
    }

    static List<String> keys(String campaign) {
        String prefix = "zhikexing:trial:{" + campaign + "}:";
        return List.of(prefix + "inventory", prefix + "holders", prefix + "receipts");
    }

    public void publish(String campaign, int capacity, Instant start, Instant end) {
        redis.execute(
                INIT,
                List.of(keys(campaign).getFirst()),
                Integer.toString(capacity),
                Long.toString(start.toEpochMilli()),
                Long.toString(end.toEpochMilli()));
    }

    public boolean allow(long actor) {
        Long hits = redis.execute(LIMIT, List.of("zhikexing:trial:rate:" + actor));
        return hits != null && hits <= 5;
    }

    public void pause(String campaign) {
        redis.execute(
                new DefaultRedisScript<>(
                        "if redis.call('EXISTS',KEYS[1])==1 then"
                            + " redis.call('HSET',KEYS[1],'state','PAUSED') end; return 1",
                        Long.class),
                List.of(keys(campaign).getFirst()));
    }

    public java.util.Map<Object, Object> snapshot(String campaign) {
        return redis.opsForHash().entries(keys(campaign).getFirst());
    }

    public String reserve(String campaign, String request, long actor) {
        String result = redis.execute(reserve, keys(campaign), request, Long.toString(actor));
        if (result == null) throw new IllegalStateException("Redis returned no admission decision");
        return result;
    }

    public boolean release(String campaign, String request, long actor) {
        Long result = redis.execute(release, keys(campaign), request, Long.toString(actor));
        return result != null && result >= 0;
    }
}
