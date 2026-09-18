package com.chy.ai.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class AgentRuntimeClient {
    // Uvicorn 使用 HTTP/1.1；固定协议避免明文 h2c 升级握手丢失 POST 请求体。
    private final HttpClient client =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
    private final String baseUrl;
    private final String internalToken;
    private final AgentJson json;

    public AgentRuntimeClient(
            @Value("${app.agent.runtime-url}") String url,
            @Value("${app.agent.internal-token}") String token,
            AgentJson json) {
        this.baseUrl = url.replaceAll("/$", "") + "/internal/v1";
        this.internalToken = token;
        this.json = json;
        if (token.isBlank()) throw new IllegalStateException("请配置 AGENT_INTERNAL_TOKEN");
    }

    public boolean validToken(String received) {
        return received != null
                && java.security.MessageDigest.isEqual(
                        internalToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        received.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public HttpRequest request(
            String method,
            String path,
            long actor,
            String workspace,
            byte[] body,
            String contentType) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(300))
                .header("X-Internal-Token", internalToken)
                .header("X-Actor-Id", Long.toString(actor))
                .header("X-Workspace-Id", workspace)
                .header("Content-Type", contentType)
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    public ResponseEntity<byte[]> exchange(
            String method,
            String path,
            long actor,
            String workspace,
            byte[] body,
            String contentType) {
        return exchange(request(method, path, actor, workspace, body, contentType));
    }

    private ResponseEntity<byte[]> exchange(HttpRequest request) {
        try {
            HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            HttpHeaders headers = new HttpHeaders();
            response.headers()
                    .firstValue("Content-Type")
                    .ifPresent(v -> headers.set("Content-Type", v));
            response.headers()
                    .firstValue("Content-Disposition")
                    .ifPresent(v -> headers.set("Content-Disposition", v));
            return new ResponseEntity<>(
                    response.body(), headers, HttpStatusCode.valueOf(response.statusCode()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "运行服务请求被中断");
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "运行服务暂不可用，已入队任务会自动重试");
        }
    }

    public HttpResponse<InputStream> events(String path, long actor, String workspace) {
        try {
            return client.send(
                    request("GET", path, actor, workspace, null, "application/json"),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "事件请求被中断");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "运行服务暂不可用");
        }
    }

    public ResponseEntity<byte[]> json(
            String method, String path, long actor, String workspace, Object body) {
        return exchange(
                method,
                path,
                actor,
                workspace,
                body == null
                        ? null
                        : json.write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "application/json");
    }

    public ResponseEntity<byte[]> command(AgentCommand command) {
        var request =
                request(
                        "POST",
                        command.path(),
                        Long.parseLong(command.actorId()),
                        command.workspaceId(),
                        json.write(command.payload())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "application/json");
        // outbox 的单次发送时限小于领取租约，失败由持久化重试负责。
        return exchange(
                HttpRequest.newBuilder(request, (name, value) -> true)
                        .header("X-Command-Delivery", "true")
                        .timeout(Duration.ofSeconds(10))
                        .build());
    }
}
