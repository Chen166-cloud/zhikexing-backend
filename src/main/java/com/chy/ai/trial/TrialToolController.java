package com.chy.ai.trial;

import static com.chy.ai.agent.AgentJson.*;

import com.chy.ai.agent.AgentBusinessService;
import com.chy.ai.agent.AgentRuntimeClient;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/internal/v1/tools")
public class TrialToolController {
    private final AgentRuntimeClient runtime;
    private final AgentBusinessService business;
    private final TrialService trials;
    private final TrialAgentService agent;

    public TrialToolController(
            AgentRuntimeClient runtime,
            AgentBusinessService business,
            TrialService trials,
            TrialAgentService agent) {
        this.runtime = runtime;
        this.business = business;
        this.trials = trials;
        this.agent = agent;
    }

    record Context(long actor, String workspace, String run) {}

    private Context authenticate(HttpServletRequest request) {
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
        return new Context(id, workspace, run);
    }

    @GetMapping("/trial-campaigns")
    public Object campaigns(HttpServletRequest request) {
        var c = authenticate(request);
        return trials.campaigns(c.actor, c.workspace);
    }

    @PostMapping("/draft-trial-claim")
    public Object draft(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        var c = authenticate(request);
        return agent.draft(c.actor, c.workspace, c.run, body);
    }

    @PostMapping("/execute-trial-claim")
    public Object execute(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        var c = authenticate(request);
        return agent.execute(c.actor, c.workspace, c.run, body);
    }

    @GetMapping("/trial-claims/by-action/{action}")
    public Object result(HttpServletRequest request, @PathVariable String action) {
        var c = authenticate(request);
        return trials.byAction(c.actor, c.workspace, c.run, action);
    }
}
