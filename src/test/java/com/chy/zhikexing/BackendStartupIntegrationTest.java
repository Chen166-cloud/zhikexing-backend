package com.chy.zhikexing;

import static org.junit.jupiter.api.Assertions.*;

import com.chy.zhikexing.agent.AgentJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Starts the full application against explicitly isolated MySQL/Redis. */
@EnabledIfEnvironmentVariable(
        named = "BACKEND_TEST_MYSQL_URL", matches = ".*zhikexing_cleanup_app_test.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "management.server.port=0",
        "spring.datasource.url=${BACKEND_TEST_MYSQL_URL}",
        "spring.datasource.username=root",
        "spring.datasource.password=trial-test-only",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=26379",
        "spring.data.redis.password=",
        "spring.data.redis.database=14",
        "app.agent.internal-token=isolated-startup-test",
        "app.agent.rocketmq-enabled=false",
        "app.ids.node-id=887"
})
class BackendStartupIntegrationTest {
    @Value("${local.server.port}")
    private int port;

    @Autowired
    private AgentJson json;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void currentBusinessEndpointsStartAndRetiredAiEndpointsKeepTheirMigrationResponse()
            throws Exception {
        assertEquals(401, request("GET", "/api/v1/courses", null, null).statusCode());

        var credentials = Map.of("userName", "cl" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 8), "password", "CleanupOnly123!");
        var registered = request("POST", "/user/register", json.write(credentials), null);
        assertEquals(200, registered.statusCode(), registered.body());
        var registration = json.read(registered.body());
        assertEquals(1, ((Number) registration.get("ok")).intValue(), registered.body());
        String token = registration.get("data").toString();

        assertEquals(200, request("GET", "/user/me", null, token).statusCode());
        var courses = request("GET", "/api/v1/courses", null, token);
        assertEquals(200, courses.statusCode(), courses.body());
        assertTrue(json.read(courses.body()).containsKey("items"));
        var workspaces = request("GET", "/api/v1/workspaces", null, token);
        assertEquals(200, workspaces.statusCode(), workspaces.body());
        assertTrue(workspaces.body().contains("OWNER"));

        var retired = request("GET", "/ai/chat", null, token);
        assertEquals(410, retired.statusCode(), retired.body());
        assertTrue(json.read(retired.body()).get("msg").toString().contains("Agent 工作台"));

        var login = request("POST", "/user/login", json.write(credentials), null);
        assertEquals(200, login.statusCode(), login.body());
        assertEquals(1, ((Number) json.read(login.body()).get("ok")).intValue(), login.body());
    }

    private HttpResponse<String> request(String method, String path, String body, String token)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
