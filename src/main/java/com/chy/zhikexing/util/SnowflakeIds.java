package com.chy.zhikexing.util;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Shared MyBatis/JDBC Snowflake generator. Node 1023 is reserved for migration backfills. */
@Component
public final class SnowflakeIds implements IdentifierGenerator {
    static final long EPOCH_MILLIS = 1735689600000L; // 2025-01-01 UTC
    private static final long MAX_SEQUENCE = 4095L;
    private final long nodeId;
    private long lastMillis = -1;
    private long sequence;

    public SnowflakeIds(@Value("${app.ids.node-id:0}") int nodeId) {
        if (nodeId < 0 || nodeId > 1022)
            throw new IllegalArgumentException("SNOWFLAKE_NODE_ID must be between 0 and 1022");
        this.nodeId = nodeId;
    }

    /** The numeric value stays in Java/MySQL; HTTP boundaries must serialize it as a string. */
    public synchronized long nextLong() {
        long now = System.currentTimeMillis();
        if (now < lastMillis)
            throw new IllegalStateException("Clock moved backwards; refusing to reuse a Snowflake ID");
        long nextSequence;
        if (now == lastMillis) {
            nextSequence = (sequence + 1) & MAX_SEQUENCE;
            if (nextSequence == 0) {
                do {
                    Thread.onSpinWait();
                    now = System.currentTimeMillis();
                    if (now < lastMillis)
                        throw new IllegalStateException("Clock moved backwards; refusing to reuse a Snowflake ID");
                } while (now <= lastMillis);
            }
        } else nextSequence = 0;
        if (now > lastMillis) nextSequence = 0;
        long elapsed = now - EPOCH_MILLIS;
        if (elapsed < 0 || elapsed >= (1L << 41))
            throw new IllegalStateException("System time is outside the Snowflake epoch");
        sequence = nextSequence;
        lastMillis = now;
        return (elapsed << 22) | (nodeId << 12) | sequence;
    }

    public String nextString() {
        return Long.toString(nextLong());
    }

    @Override
    public Long nextId(Object entity) {
        return nextLong();
    }
}
