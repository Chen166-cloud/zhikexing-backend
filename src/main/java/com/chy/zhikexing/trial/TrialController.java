package com.chy.zhikexing.trial;

import static com.chy.zhikexing.agent.AgentJson.*;

import com.chy.zhikexing.util.UserHolder;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/workspaces/{workspace}/trials")
public class TrialController {
    private final TrialService service;
    private final boolean enabled;

    public TrialController(
            TrialService service, @Value("${app.agent.rocketmq-enabled:false}") boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    private void requireMessaging() {
        if (!enabled) throw error(HttpStatus.SERVICE_UNAVAILABLE, "试听抢课需要启用RocketMQ");
    }

    @GetMapping("/catalog")
    public Object catalog(@PathVariable String workspace) {
        return service.catalog(UserHolder.requireUserId(), workspace);
    }

    @GetMapping("/campaigns")
    public Object campaigns(@PathVariable String workspace) {
        return service.campaigns(UserHolder.requireUserId(), workspace);
    }

    @GetMapping("/campaigns/{id}")
    public Object campaign(@PathVariable String workspace, @PathVariable String id) {
        return service.campaign(UserHolder.requireUserId(), workspace, id);
    }

    @PostMapping("/campaigns")
    public Object create(@PathVariable String workspace, @RequestBody Map<String, Object> body) {
        return service.create(UserHolder.requireUserId(), workspace, body);
    }

    @PostMapping("/campaigns/{id}/publish")
    public Object publish(@PathVariable String workspace, @PathVariable String id) {
        requireMessaging();
        return service.publish(UserHolder.requireUserId(), workspace, id);
    }

    @PostMapping("/campaigns/{id}/claims")
    public ResponseEntity<?> claim(
            @PathVariable String workspace,
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        requireMessaging();
        return ResponseEntity.accepted()
                .body(
                        service.submit(
                                UserHolder.requireUserId(),
                                workspace,
                                id,
                                required(body, "clientRequestId", 128)));
    }

    @GetMapping("/claims/{id}")
    public Object result(@PathVariable String workspace, @PathVariable String id) {
        return service.result(UserHolder.requireUserId(), workspace, id);
    }

    @GetMapping("/claims")
    public Object claims(@PathVariable String workspace) {
        return service.claims(UserHolder.requireUserId(), workspace);
    }

    @PostMapping("/campaigns/{id}/pause")
    public Object pause(@PathVariable String workspace, @PathVariable String id) {
        return service.pause(UserHolder.requireUserId(), workspace, id);
    }

    @GetMapping("/campaigns/{id}/reconciliation")
    public Object reconciliation(@PathVariable String workspace, @PathVariable String id) {
        return service.reconciliation(UserHolder.requireUserId(), workspace, id);
    }
}
