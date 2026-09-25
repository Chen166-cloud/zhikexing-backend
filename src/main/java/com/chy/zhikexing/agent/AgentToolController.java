package com.chy.zhikexing.agent;

import static com.chy.zhikexing.agent.AgentJson.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/internal/v1/tools")
public class AgentToolController {
    private final AgentBusinessService business;
    private final AgentRuntimeClient runtime;
    private final JdbcTemplate jdbc;

    public AgentToolController(
            AgentBusinessService business, AgentRuntimeClient runtime, JdbcTemplate jdbc) {
        this.business = business;
        this.runtime = runtime;
        this.jdbc = jdbc;
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
        StringBuilder sql =
                new StringBuilder("SELECT id,name,type,edu,price,duration FROM course WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (body.get("type") != null) {
            sql.append(" AND type=?");
            params.add(body.get("type"));
        }
        if (body.get("edu") != null) {
            sql.append(" AND edu<=?");
            params.add(body.get("edu"));
        }
        if (body.get("keyword") != null) {
            // 名称和输入按相同规则忽略空白，保留数据库原名并继续绑定查询参数。
            sql.append(" AND REGEXP_REPLACE(name, '[[:space:]]+', '')")
                    .append(" LIKE CONCAT('%', REGEXP_REPLACE(?, '[[:space:]]+', ''), '%')");
            params.add(body.get("keyword").toString());
        }
        if (body.get("sortBy") != null) {
            String column = body.get("sortBy").toString();
            if (!Set.of("price", "duration", "id").contains(column))
                throw error(HttpStatus.BAD_REQUEST, "排序字段只允许 price、duration、id");
            sql.append(" ORDER BY ")
                    .append(column)
                    .append(Boolean.FALSE.equals(body.get("ascending")) ? " DESC" : " ASC");
        }
        sql.append(" LIMIT 50");
        return jdbc.query(
                sql.toString(),
                (r, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", r.getString("id"));
                    row.put("name", r.getString("name"));
                    row.put("type", r.getString("type"));
                    row.put("edu", r.getObject("edu"));
                    row.put("price", r.getObject("price"));
                    row.put("duration", r.getObject("duration"));
                    return row;
                },
                params.toArray());
    }

    @GetMapping("/campuses")
    public Object campuses(HttpServletRequest request) {
        authenticate(request);
        return jdbc.query(
                "SELECT id,name,city FROM school ORDER BY id LIMIT 100",
                (r, n) ->
                        Map.of(
                                "id",
                                r.getString(1),
                                "name",
                                r.getString(2),
                                "city",
                                Objects.toString(r.getString(3), "")));
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
