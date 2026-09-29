package com.chy.zhikexing.agent;

import static com.chy.zhikexing.agent.AgentJson.*;

import com.chy.zhikexing.catalog.CourseCatalogService;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/internal/v1/tools")
public class AgentToolController {
    private final AgentBusinessService business;
    private final AgentRuntimeClient runtime;
    private final CourseCatalogService catalog;

    public AgentToolController(
            AgentBusinessService business, AgentRuntimeClient runtime, CourseCatalogService catalog) {
        this.business = business;
        this.runtime = runtime;
        this.catalog = catalog;
    }

    record Actor(long id, String workspace, String run) {}

    private Actor authenticate(HttpServletRequest request) {
        if (!runtime.validToken(request.getHeader("X-Internal-Token")))
            throw error(HttpStatus.UNAUTHORIZED, "无效内部凭据");
        String actor = request.getHeader("X-Actor-Id"),
                workspace = request.getHeader("X-Workspace-Id"),
                run = request.getHeader("X-Run-Id");
        if (actor == null || workspace == null || run == null)
            throw error(HttpStatus.BAD_REQUEST, "缺少可信运行上下文");
        long id;
        try {
            id = Long.parseLong(actor);
        } catch (NumberFormatException e) {
            throw error(HttpStatus.BAD_REQUEST, "无效用户编号");
        }
        business.requireRun(id, workspace, run, false);
        return new Actor(id, workspace, run);
    }

    @PostMapping("/courses")
    public Object courses(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        authenticate(request);
        return catalog.search(body);
    }

    @GetMapping("/campuses")
    public Object campuses(HttpServletRequest request) {
        authenticate(request);
        return catalog.campuses().stream().limit(100).toList();
    }

    @PostMapping("/draft-reservation")
    public Object draft(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        Actor a = authenticate(request);
        return business.draft(a.id, a.workspace, a.run, body);
    }

    @GetMapping("/approvals/{id}")
    public Object approval(HttpServletRequest request, @PathVariable String id) {
        Actor a = authenticate(request);
        Map<String, Object> value = business.approval(a.id, a.workspace, id);
        if (!a.run.equals(value.get("runId"))) throw error(HttpStatus.NOT_FOUND, "审批不属于当前运行");
        return value;
    }

    @PostMapping("/execute-reservation")
    public Object execute(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        Actor a = authenticate(request);
        return business.execute(a.id, a.workspace, a.run, body);
    }

    @GetMapping("/reservations/by-action/{action}")
    public Object result(HttpServletRequest request, @PathVariable String action) {
        Actor a = authenticate(request);
        return business.reservation(a.id, a.workspace, a.run, action);
    }
}
