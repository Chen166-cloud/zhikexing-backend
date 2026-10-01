package com.chy.zhikexing.catalog;

import com.chy.zhikexing.agent.AgentBusinessService;
import com.chy.zhikexing.agent.AgentErrorHandler;
import com.chy.zhikexing.agent.AgentRuntimeClient;
import com.chy.zhikexing.agent.AgentToolController;
import com.chy.zhikexing.auth.AuthSessionService;
import com.chy.zhikexing.entity.vo.UserDTO;
import com.chy.zhikexing.util.LoginInterceptor;
import com.chy.zhikexing.util.RefreshTokenInterceptor;
import com.chy.zhikexing.util.UserHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CatalogAuthorizationTest {
    @AfterEach
    void threadLocalWasCleared() {
        assertNull(UserHolder.getUser());
        UserHolder.removeUser();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aWarmLocalCacheStillRequiresAValidSessionOnEveryRequest() throws Exception {
        var sessions = mock(AuthSessionService.class);
        var user = new UserDTO();
        user.setId(7L);
        when(sessions.resolveAndRefresh("session-token")).thenReturn(user, user, null);
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("catalog:v1:course:9007199254740993"))
                .thenReturn("{\"id\":\"9007199254740993\",\"name\":\"Java\",\"type\":\"编程\",\"edu\":0,\"price\":1000,\"duration\":30}");
        var jdbc = mock(JdbcTemplate.class);
        var redisson = mock(RedissonClient.class);
        var cache = new CatalogCache(redis, redisson, new CatalogCacheProperties(), new SimpleMeterRegistry());
        var catalog = new CourseCatalogService(jdbc, cache, JsonMapper.builder().build(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var mvc = MockMvcBuilders.standaloneSetup(new CourseCatalogController(catalog))
                .addInterceptors(new RefreshTokenInterceptor(sessions), new LoginInterceptor())
                .setControllerAdvice(new AgentErrorHandler()).build();

        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/v1/courses/9007199254740993").header("Authorization", "Bearer session-token"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("9007199254740993"));
            assertNull(UserHolder.getUser());
        }
        mvc.perform(get("/api/v1/courses/9007199254740993")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/courses/9007199254740993").header("Authorization", "Bearer session-token"))
                .andExpect(status().isUnauthorized());
        verify(sessions, times(3)).resolveAndRefresh("session-token");
        verify(values, times(1)).get("catalog:v1:course:9007199254740993");
        verifyNoInteractions(jdbc, redisson);
    }

    @Test
    void internalCourseQueriesRecheckRunAccessBeforeUsingTheCatalog() throws Exception {
        var business = mock(AgentBusinessService.class);
        var runtime = mock(AgentRuntimeClient.class);
        var catalog = mock(CourseCatalogService.class);
        when(runtime.validToken("internal-token")).thenReturn(true);
        when(business.requireRun(7L, "workspace", "run", false)).thenReturn(Map.of())
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "运行不存在"));
        when(catalog.search(Map.of())).thenReturn(List.of());
        var mvc = MockMvcBuilders.standaloneSetup(new AgentToolController(business, runtime, catalog))
                .setControllerAdvice(new AgentErrorHandler()).build();

        mvc.perform(post("/internal/v1/tools/courses").header("X-Internal-Token", "internal-token")
                        .header("X-Actor-Id", "7").header("X-Workspace-Id", "workspace").header("X-Run-Id", "run")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        var order = inOrder(business, catalog);
        order.verify(business).requireRun(7L, "workspace", "run", false);
        order.verify(catalog).search(Map.of());

        mvc.perform(post("/internal/v1/tools/courses").header("X-Internal-Token", "internal-token")
                        .header("X-Actor-Id", "7").header("X-Workspace-Id", "workspace").header("X-Run-Id", "run")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/internal/v1/tools/courses").header("X-Internal-Token", "invalid")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verify(business, times(2)).requireRun(7L, "workspace", "run", false);
        verify(catalog, times(1)).search(Map.of());
    }

    @Test
    void internalCampusResponseKeepsTheExistingHundredItemLimit() throws Exception {
        var business = mock(AgentBusinessService.class);
        var runtime = mock(AgentRuntimeClient.class);
        var catalog = mock(CourseCatalogService.class);
        when(runtime.validToken("internal-token")).thenReturn(true);
        when(catalog.campuses()).thenReturn(IntStream.range(1, 102)
                .mapToObj(i -> new CourseCatalogService.Campus(Integer.toString(i), "校区" + i, "上海")).toList());
        var mvc = MockMvcBuilders.standaloneSetup(new AgentToolController(business, runtime, catalog))
                .setControllerAdvice(new AgentErrorHandler()).build();
        mvc.perform(get("/internal/v1/tools/campuses").header("X-Internal-Token", "internal-token")
                        .header("X-Actor-Id", "7").header("X-Workspace-Id", "workspace").header("X-Run-Id", "run"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(100))
                .andExpect(jsonPath("$[99].id").value("100"));
        var order = inOrder(business, catalog);
        order.verify(business).requireRun(7L, "workspace", "run", false);
        order.verify(catalog).campuses();
    }
}
