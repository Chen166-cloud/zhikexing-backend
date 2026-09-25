package com.chy.zhikexing.util;

import static org.junit.jupiter.api.Assertions.*;

import com.chy.zhikexing.entity.vo.UserDTO;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

class SnowflakeIdsTest {
    @Test
    void concurrentIdsStayUniqueAndEncodeTheirNode() throws Exception {
        SnowflakeIds generator = new SnowflakeIds(777);
        var tasks = new ArrayList<Callable<Long>>();
        for (int i = 0; i < 20_000; i++) tasks.add(generator::nextLong);
        try (var workers = Executors.newFixedThreadPool(16)) {
            var results = workers.invokeAll(tasks);
            var ids = new HashSet<Long>();
            for (var result : results) {
                long id = result.get();
                assertTrue(id > 0);
                assertEquals(777, (id >>> 12) & 1023);
                assertTrue(ids.add(id));
            }
        }
    }

    @Test
    void unsafeNumericHttpIdIsWrittenAsJsonString() {
        UserDTO user = new UserDTO();
        user.setId(9_007_199_254_740_993L);
        assertTrue(
                JsonMapper.builder()
                        .build()
                        .writeValueAsString(user)
                        .contains("\"id\":\"9007199254740993\""));
    }

    @Test
    void migrationNodeIsNotAssignableToAnApplicationInstance() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIds(1023));
    }
}
