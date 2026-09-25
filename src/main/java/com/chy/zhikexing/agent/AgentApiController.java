package com.chy.zhikexing.agent;

import static com.chy.zhikexing.agent.AgentJson.*;

import com.chy.zhikexing.util.UserHolder;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class AgentApiController {
    private final AgentBusinessService business;
    private final AgentRuntimeClient runtime;
    private final AgentJson json;

    public AgentApiController(
            AgentBusinessService business, AgentRuntimeClient runtime, AgentJson json) {
        this.business = business;
        this.runtime = runtime;
        this.json = json;
    }

    @GetMapping("/workspaces")
    public Object workspaces() {
        return business.workspaces(UserHolder.requireUserId());
    }

    @PostMapping("/workspaces")
    public Object createWorkspace(@RequestBody Map<String, Object> body) {
        return business.createWorkspace(
                UserHolder.requireUserId(), required(body, "name", 120), false);
    }

    @GetMapping("/workspaces/{id}/members")
    public Object members(@PathVariable String id) {
        return business.members(UserHolder.requireUserId(), id);
    }

    @PostMapping("/workspaces/{id}/members")
    public Object addMember(@PathVariable String id, @RequestBody Map<String, Object> body) {
        business.addMember(UserHolder.requireUserId(), id, body);
        return Map.of("status", "ADDED");
    }

    @PostMapping("/runs")
    public Object submit(@RequestBody Map<String, Object> body) {
        long actor = UserHolder.requireUserId();
        String workspace = required(body, "workspaceId", 64);
        business.requireMember(actor, workspace);
        // 先确认会话存在且属于空间，再接受持久化任务。
        String conversation = required(body, "conversationId", 64);
        if (!conversation.matches("[A-Za-z0-9_-]+")) throw error(HttpStatus.BAD_REQUEST, "无效会话编号");
        var result = runtime.json("GET", "/conversations/" + conversation, actor, workspace, null);
        if (!result.getStatusCode().is2xxSuccessful()) return result;
        if (body.get("knowledgeBaseIds") instanceof List<?> ids && !ids.isEmpty()) {
            var libraries = runtime.json("GET", "/knowledge-bases", actor, workspace, null);
            if (!libraries.getStatusCode().is2xxSuccessful()) return libraries;
            Set<Object> allowed = new HashSet<>();
            for (var library : json.readList(libraries.getBody())) allowed.add(library.get("id"));
            if (!allowed.containsAll(ids)) throw error(HttpStatus.FORBIDDEN, "所选知识库不属于当前工作空间");
        }
        return business.submit(actor, workspace, body);
    }

    @PostMapping("/runs/{id}/cancel")
    public Object cancel(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return business.cancel(UserHolder.requireUserId(), required(body, "workspaceId", 64), id);
    }

    @GetMapping("/approvals")
    public Object approvals(
            @RequestParam String workspaceId, @RequestParam(required = false) String runId) {
        return business.approvals(UserHolder.requireUserId(), workspaceId, runId);
    }

    @PostMapping("/approvals/{id}/decision")
    public Object decide(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return business.decision(
                UserHolder.requireUserId(), required(body, "workspaceId", 64), id, body);
    }

    @GetMapping(value = "/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> events(
            @PathVariable String id,
            @RequestParam String workspaceId,
            @RequestParam(defaultValue = "0") long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastId)
            throws IOException {
        long actor = UserHolder.requireUserId();
        business.requireRun(actor, workspaceId, id, false);
        if (lastId != null) {
            try {
                after = Math.max(after, Long.parseLong(lastId));
            } catch (NumberFormatException e) {
                throw error(HttpStatus.BAD_REQUEST, "无效事件序号");
            }
        }
        var response =
                runtime.events(
                        "/runs/" + id + "/events?after=" + Math.max(0, after), actor, workspaceId);
        // 入队事务先于异步投递提交，首次订阅允许等待这段短暂的交接时间。
        for (int attempt = 0; response.statusCode() == 404 && attempt < 25; attempt++) {
            response.body().close();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw error(HttpStatus.SERVICE_UNAVAILABLE, "事件订阅被中断");
            }
            response =
                    runtime.events(
                            "/runs/" + id + "/events?after=" + Math.max(0, after),
                            actor,
                            workspaceId);
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw error(HttpStatus.valueOf(response.statusCode()), "无法读取运行事件");
        }
        // 身份在进入异步线程前固定，客户端断开触发写异常并关闭上游流。
        InputStream upstream = response.body();
        StreamingResponseBody stream =
                output -> {
                    try (InputStream input = upstream) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                            output.flush();
                        }
                    }
                };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(stream);
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> upload(
            @RequestParam String workspaceId,
            @RequestParam String knowledgeBaseId,
            @RequestParam(required = false) String documentId,
            @RequestParam MultipartFile file)
            throws IOException {
        long actor = UserHolder.requireUserId();
        business.requireMember(actor, workspaceId);
        if (file.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "文件不能为空");
        String boundary = "zhikexing-" + UUID.randomUUID();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        multipartField(bytes, boundary, "workspaceId", workspaceId);
        multipartField(bytes, boundary, "knowledgeBaseId", knowledgeBaseId);
        if (documentId != null) multipartField(bytes, boundary, "documentId", documentId);
        String name =
                Objects.toString(file.getOriginalFilename(), "document")
                        .replaceAll("[\\r\\n\\\"\\\\]", "_");
        bytes.write(
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                                + name
                                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        try (InputStream input = file.getInputStream()) {
            input.transferTo(bytes);
        }
        bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return runtime.exchange(
                "POST",
                "/documents",
                actor,
                workspaceId,
                bytes.toByteArray(),
                "multipart/form-data; boundary=" + boundary);
    }

    private void multipartField(OutputStream output, String boundary, String field, String value)
            throws IOException {
        output.write(
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\""
                                + field
                                + "\"\r\n\r\n"
                                + value
                                + "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping({
        "/conversations",
        "/conversations/{id}",
        "/runs",
        "/runs/{id}",
        "/knowledge-bases",
        "/documents",
        "/documents/{id}/content",
        "/documents/{id}/chunks",
        "/evaluations",
        "/evaluations/{id}",
        "/metrics"
    })
    public ResponseEntity<byte[]> read(HttpServletRequest request) {
        return proxy(request, null);
    }

    @PostMapping({
        "/conversations",
        "/runs/{id}/retry",
        "/runs/{id}/inputs",
        "/knowledge-bases",
        "/documents/{id}/retry",
        "/evaluations"
    })
    public ResponseEntity<byte[]> command(
            HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
        return proxy(request, body);
    }

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<byte[]> deleteDocument(HttpServletRequest request) {
        return proxy(request, null);
    }

    // 每种 HTTP 方法按契约单独注册，避免泛化代理与提交、上传处理器发生路由重叠。
    private ResponseEntity<byte[]> proxy(HttpServletRequest request, Map<String, Object> body) {
        long actor = UserHolder.requireUserId();
        String workspace = request.getParameter("workspaceId");
        if (workspace == null && body != null) workspace = required(body, "workspaceId", 64);
        if (workspace == null) throw error(HttpStatus.BAD_REQUEST, "缺少 workspaceId");
        business.requireMember(actor, workspace);
        String path = request.getRequestURI().substring("/api/v1".length());
        if (path.startsWith("/runs/"))
            business.requireRun(actor, workspace, path.split("/")[2], false);
        if (path.endsWith("/retry") || path.endsWith("/inputs")) {
            if (path.startsWith("/runs/"))
                business.requireActiveRun(actor, workspace, path.split("/")[2]);
        }
        boolean runDetail = "GET".equals(request.getMethod()) && path.matches("/runs/[^/]+");
        String runId = runDetail ? path.substring("/runs/".length()) : null;
        if (request.getQueryString() != null) path += "?" + request.getQueryString();
        var response = runtime.json(request.getMethod(), path, actor, workspace, body);
        if (runDetail && response.getStatusCode().value() == 404) {
            // 只补齐已授权任务的异步交接空档，其他资源的 404 保留原义。
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .cacheControl(CacheControl.noStore())
                    .body(
                            json.write(business.submissionFallback(actor, workspace, runId))
                                    .getBytes(StandardCharsets.UTF_8));
        }
        return response;
    }
}
