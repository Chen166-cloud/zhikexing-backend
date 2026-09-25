package com.chy.zhikexing.agent;

import static com.chy.zhikexing.agent.AgentJson.*;

import com.chy.zhikexing.util.SnowflakeIds;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class AgentBusinessService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AgentJson json;
    private final SnowflakeIds ids;

    public AgentBusinessService(JdbcTemplate jdbc, TransactionTemplate tx, AgentJson json, SnowflakeIds ids) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tx.getTransactionManager());
        // 获得运行行锁后必须能读到前一事务的提交结果，避免 MySQL 旧快照破坏幂等响应。
        this.tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.json = json;
        this.ids = ids;
    }

    public void requireMember(long actor, String workspace) {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM agent_workspace_member WHERE workspace_id=? AND"
                                + " user_id=?",
                        Integer.class,
                        workspace,
                        actor)
                == 0) throw error(HttpStatus.FORBIDDEN, "无权访问该工作空间");
    }

    public List<Map<String, Object>> workspaces(long actor) {
        // 锁定用户行，避免并发首次访问建立多个个人空间。
        tx.executeWithoutResult(
                status -> {
                    jdbc.queryForObject(
                            "SELECT id FROM user_info WHERE id=? FOR UPDATE", Long.class, actor);
                    if (jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM agent_workspace WHERE"
                                            + " personal_owner_id=?",
                                    Integer.class,
                                    actor)
                            == 0) createWorkspace(actor, "个人工作空间", true);
                });
        return jdbc.query(
                "SELECT w.id,w.name,m.role FROM agent_workspace w JOIN agent_workspace_member m ON"
                        + " w.id=m.workspace_id WHERE m.user_id=? ORDER BY w.created_at",
                (rs, n) ->
                        Map.of(
                                "id",
                                rs.getString(1),
                                "name",
                                rs.getString(2),
                                "role",
                                rs.getString(3)),
                actor);
    }

    public Map<String, Object> createWorkspace(long actor, String name, boolean personal) {
        return tx.execute(
                status -> {
                    String id = ids.nextString();
                    jdbc.update(
                            "INSERT INTO agent_workspace(id,name,owner_id,personal_owner_id)"
                                    + " VALUES(?,?,?,?)",
                            id,
                            name,
                            actor,
                            personal ? actor : null);
                    jdbc.update(
                            "INSERT INTO agent_workspace_member(id,workspace_id,user_id,role)"
                                    + " VALUES(?,?,?,'OWNER')",
                            ids.nextLong(),
                            id,
                            actor);
                    return Map.of("id", id, "name", name, "role", "OWNER");
                });
    }

    public List<Map<String, Object>> members(long actor, String workspace) {
        requireMember(actor, workspace);
        return jdbc.query(
                "SELECT m.user_id,u.user_name,m.role FROM agent_workspace_member m JOIN user_info u"
                        + " ON u.id=m.user_id WHERE workspace_id=?",
                (r, n) ->
                        Map.of(
                                "userId",
                                r.getString(1),
                                "userName",
                                r.getString(2),
                                "role",
                                r.getString(3)),
                workspace);
    }

    public void addMember(long actor, String workspace, Map<String, Object> body) {
        requireMember(actor, workspace);
        if (!"OWNER"
                .equals(
                        jdbc.queryForObject(
                                "SELECT role FROM agent_workspace_member WHERE workspace_id=? AND"
                                        + " user_id=?",
                                String.class,
                                workspace,
                                actor))) throw error(HttpStatus.FORBIDDEN, "只有所有者可以管理成员");
        String username = required(body, "userName", 64);
        List<Long> users =
                jdbc.queryForList(
                        "SELECT id FROM user_info WHERE user_name=?", Long.class, username);
        if (users.isEmpty()) throw error(HttpStatus.NOT_FOUND, "用户不存在");
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM agent_workspace_member WHERE workspace_id=? AND"
                                + " user_id=?",
                        Integer.class,
                        workspace,
                        users.getFirst())
                == 0)
            jdbc.update(
                    "INSERT INTO agent_workspace_member(id,workspace_id,user_id,role)"
                            + " VALUES(?,?,?,'MEMBER')",
                    ids.nextLong(),
                    workspace,
                    users.getFirst());
    }

    public Map<String, Object> submit(long actor, String workspace, Map<String, Object> input) {
        requireMember(actor, workspace);
        String requestId = required(input, "clientRequestId", 128);
        String conversationId = required(input, "conversationId", 64);
        required(input, "input", 12000);
        if (input.containsKey("knowledgeBaseIds")) {
            if (!(input.get("knowledgeBaseIds") instanceof List<?> ids)
                    || ids.size() > 20
                    || ids.stream()
                            .anyMatch(
                                    id ->
                                            !(id instanceof String text)
                                                    || text.isBlank()
                                                    || text.length() > 64))
                throw error(HttpStatus.BAD_REQUEST, "知识库编号必须是最多20项的字符串列表");
        }
        String mode = Objects.toString(input.getOrDefault("mode", "agent"));
        if (!Set.of("agent", "chat").contains(mode)) throw error(HttpStatus.BAD_REQUEST, "无效运行模式");
        // 授权检查、幂等摘要和下游命令必须使用相同的编号，不能校验 trim 后又发送原值。
        Map<String, Object> normalized = new LinkedHashMap<>(input);
        normalized.put("conversationId", conversationId);
        normalized.put("clientRequestId", requestId);
        // 对成员行串行化创建；相同幂等键必须具有完全相同的业务内容。
        return tx.execute(
                status -> {
                    jdbc.queryForObject(
                            "SELECT user_id FROM agent_workspace_member WHERE workspace_id=? AND"
                                    + " user_id=? FOR UPDATE",
                            Long.class,
                            workspace,
                            actor);
                    List<Map<String, Object>> existing =
                            jdbc.queryForList(
                                    "SELECT * FROM agent_task_request WHERE workspace_id=? AND"
                                            + " actor_id=? AND client_request_id=?",
                                    workspace,
                                    actor,
                                    requestId);
                    String hash = json.hash(normalized);
                    if (!existing.isEmpty()) {
                        Map<String, Object> row = existing.getFirst();
                        if (!hash.equals(row.get("payload_hash")))
                            throw error(HttpStatus.CONFLICT, "同一请求编号不能提交不同内容");
                        return Map.of(
                                "runId",
                                row.get("run_id"),
                                "conversationId",
                                conversationId,
                                "status",
                                "QUEUED");
                    }
                    String runId = ids.nextString();
                    Map<String, Object> payload = new LinkedHashMap<>(normalized);
                    payload.put("runId", runId);
                    payload.put("actorId", Long.toString(actor));
                    payload.put("workspaceId", workspace);
                    jdbc.update(
                            "INSERT INTO"
                                + " agent_task_request(run_id,workspace_id,actor_id,conversation_id,client_request_id,payload_hash)"
                                + " VALUES(?,?,?,?,?,?)",
                            runId,
                            workspace,
                            actor,
                            conversationId,
                            requestId,
                            hash);
                    enqueue(runId, workspace, actor, "/runs", payload);
                    return Map.of(
                            "runId", runId, "conversationId", conversationId, "status", "QUEUED");
                });
    }

    public Map<String, Object> requireRun(
            long actor, String workspace, String runId, boolean lock) {
        requireMember(actor, workspace);
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM agent_task_request WHERE run_id=? AND workspace_id=? AND"
                                + " actor_id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        runId,
                        workspace,
                        actor);
        if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "运行不存在或不属于当前用户");
        return rows.getFirst();
    }

    private void requireWritable(Map<String, Object> run) {
        if (Boolean.TRUE.equals(run.get("writes_blocked"))
                || "1".equals(Objects.toString(run.get("writes_blocked"))))
            throw error(HttpStatus.CONFLICT, "运行已取消，禁止后续写操作");
    }

    public void requireActiveRun(long actor, String workspace, String runId) {
        requireWritable(requireRun(actor, workspace, runId, false));
    }

    public Map<String, Object> cancel(long actor, String workspace, String runId) {
        return tx.execute(
                status -> {
                    Map<String, Object> run = requireRun(actor, workspace, runId, true);
                    if (Boolean.TRUE.equals(run.get("writes_blocked"))) {
                        return Map.of("runId", runId, "status", "CANCELLED");
                    }
                    jdbc.update(
                            "UPDATE agent_task_request SET writes_blocked=TRUE WHERE run_id=?",
                            runId);
                    jdbc.update(
                            "UPDATE agent_approval SET status='CANCELLED',version=version+1 WHERE"
                                    + " run_id=? AND status IN ('PENDING','APPROVED')",
                            runId);
                    enqueue(
                            runId,
                            workspace,
                            actor,
                            "/runs/" + runId + "/cancel",
                            Map.of("workspaceId", workspace));
                    return Map.of("runId", runId, "status", "CANCELLED");
                });
    }

    private void enqueue(String run, String workspace, long actor, String path, Object payload) {
        jdbc.update(
                "INSERT INTO agent_outbox(id,run_id,workspace_id,actor_id,path,payload)"
                        + " VALUES(?,?,?,?,?,?)",
                ids.nextString(),
                run,
                workspace,
                actor,
                path,
                json.write(payload));
    }

    public List<Map<String, Object>> approvals(long actor, String workspace, String run) {
        requireMember(actor, workspace);
        String sql = "SELECT * FROM agent_approval WHERE workspace_id=? AND actor_id=?";
        List<Map<String, Object>> rows =
                run == null
                        ? jdbc.queryForList(sql + " ORDER BY expires_at DESC", workspace, actor)
                        : jdbc.queryForList(
                                sql + " AND run_id=? ORDER BY expires_at DESC",
                                workspace,
                                actor,
                                run);
        return rows.stream().map(this::approvalView).toList();
    }

    public Map<String, Object> approval(long actor, String workspace, String id) {
        requireMember(actor, workspace);
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM agent_approval WHERE id=? AND workspace_id=? AND actor_id=?",
                        id,
                        workspace,
                        actor);
        if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "审批不存在");
        return approvalView(rows.getFirst());
    }

    public Map<String, Object> decision(
            long actor, String workspace, String id, Map<String, Object> body) {
        String decision = required(body, "decision", 20);
        if (!Set.of("APPROVED", "REJECTED").contains(decision))
            throw error(HttpStatus.BAD_REQUEST, "无效审批决定");
        if (!(body.get("expectedVersion") instanceof Integer version) || version < 1)
            throw error(HttpStatus.BAD_REQUEST, "审批版本号必须是正整数");
        return tx.execute(
                status -> {
                    Map<String, Object> item = approval(actor, workspace, id);
                    requireWritable(
                            requireRun(actor, workspace, item.get("runId").toString(), true));
                    int count =
                            jdbc.update(
                                    "UPDATE agent_approval SET status=?,version=version+1 WHERE"
                                            + " id=? AND status='PENDING' AND version=? AND"
                                            + " expires_at>CURRENT_TIMESTAMP",
                                    decision,
                                    id,
                                    version.intValue());
                    if (count == 0) throw error(HttpStatus.CONFLICT, "审批已变化或已过期，请刷新后重试");
                    return approval(actor, workspace, id);
                });
    }

    public Map<String, Object> draft(
            long actor, String workspace, String run, Map<String, Object> body) {
        String action = required(body, "actionId", 128);
        String course = required(body, "courseId", 32), school = required(body, "schoolId", 32);
        if (!course.matches("[0-9]+") || !school.matches("[0-9]+"))
            throw error(HttpStatus.BAD_REQUEST, "课程和校区编号必须为数字");
        required(body, "studentName", 64);
        required(body, "contactInfo", 128);
        if (body.containsKey("remark") && Objects.toString(body.get("remark"), "").length() > 500)
            throw error(HttpStatus.BAD_REQUEST, "备注过长");
        return tx.execute(
                status -> {
                    requireWritable(requireRun(actor, workspace, run, true));
                    Map<String, Object> args = new LinkedHashMap<>(body);
                    args.remove("actionId");
                    args.remove("courseName");
                    args.remove("schoolName");
                    args.put("courseId", course);
                    args.put("schoolId", school);
                    List<Map<String, Object>> existing =
                            jdbc.queryForList(
                                    "SELECT * FROM agent_approval WHERE run_id=? AND action_id=?",
                                    run,
                                    action);
                    if (!existing.isEmpty()) {
                        Map<String, Object> original =
                                json.read(existing.getFirst().get("args").toString());
                        original.remove("courseName");
                        original.remove("schoolName");
                        if (!json.hash(args).equals(json.hash(original)))
                            throw error(HttpStatus.CONFLICT, "同一动作不能更换参数");
                        // 草稿的显示名称属于审批快照，课程改名不会改变同一动作的重放结果。
                        return approvalView(existing.getFirst());
                    }
                    List<Map<String, Object>> courses =
                            jdbc.queryForList("SELECT id,name FROM course WHERE id=?", course);
                    List<Map<String, Object>> schools =
                            jdbc.queryForList("SELECT id,name FROM school WHERE id=?", school);
                    if (courses.isEmpty() || schools.isEmpty())
                        throw error(HttpStatus.BAD_REQUEST, "课程或校区不存在");
                    args.put("courseName", courses.getFirst().get("name"));
                    args.put("schoolName", schools.getFirst().get("name"));
                    String id = ids.nextString();
                    jdbc.update(
                            "INSERT INTO"
                                + " agent_approval(id,run_id,workspace_id,actor_id,action_id,status,args,args_hash,expires_at)"
                                + " VALUES(?,?,?,?,?,'PENDING',?,?,?)",
                            id,
                            run,
                            workspace,
                            actor,
                            action,
                            json.write(args),
                            json.hash(args),
                            Timestamp.from(Instant.now().plusSeconds(900)));
                    return approval(actor, workspace, id);
                });
    }

    public Map<String, Object> execute(
            long actor, String workspace, String run, Map<String, Object> body) {
        String action = required(body, "actionId", 128), id = required(body, "approvalId", 64);
        return tx.execute(
                status -> {
                    // 取消与提交锁定同一运行行：取消成功返回后，不会发生新的预约提交。
                    Map<String, Object> runRow = requireRun(actor, workspace, run, true);
                    List<Map<String, Object>> results =
                            jdbc.queryForList(
                                    "SELECT result,approval_id FROM agent_reservation_action WHERE"
                                            + " run_id=? AND action_id=?",
                                    run,
                                    action);
                    if (!results.isEmpty()) {
                        if (!id.equals(results.getFirst().get("approval_id")))
                            throw error(HttpStatus.CONFLICT, "审批不匹配当前动作");
                        return json.read(results.getFirst().get("result").toString());
                    }
                    requireWritable(runRow);
                    Map<String, Object> approval = approval(actor, workspace, id);
                    if (!run.equals(approval.get("runId"))
                            || !action.equals(approval.get("actionId")))
                        throw error(HttpStatus.CONFLICT, "审批不匹配当前动作");
                    if (!"APPROVED".equals(approval.get("status")))
                        throw error(HttpStatus.CONFLICT, "预约必须经过用户审批");
                    if (!"reserve_course".equals(approval.get("toolName")))
                        throw error(HttpStatus.CONFLICT, "审批工具类型不匹配");
                    Map<String, Object> args = (Map<String, Object>) approval.get("args");
                    long reservationId = ids.nextLong();
                    jdbc.update(
                            "INSERT INTO"
                                + " course_reservation(id,course,student_name,contact_info,school,remark)"
                                + " VALUES(?,?,?,?,?,?)",
                            reservationId,
                            args.get("courseName").toString(),
                            args.get("studentName").toString(),
                            args.get("contactInfo").toString(),
                            args.get("schoolName").toString(),
                            Objects.toString(args.get("remark"), ""));
                    Map<String, Object> result =
                            Map.of(
                                    "reservationId",
                                    Long.toString(reservationId),
                                    "actionId",
                                    action,
                                    "status",
                                    "SUCCEEDED",
                                    "courseName",
                                    args.get("courseName"),
                                    "schoolName",
                                    args.get("schoolName"));
                    jdbc.update(
                            "INSERT INTO"
                                + " agent_reservation_action(id,action_id,run_id,approval_id,reservation_id,result)"
                                + " VALUES(?,?,?,?,?,?)",
                            ids.nextLong(),
                            action,
                            run,
                            id,
                            reservationId,
                            json.write(result));
                    jdbc.update(
                            "UPDATE agent_approval SET status='EXECUTED',version=version+1,result=?"
                                    + " WHERE id=?",
                            json.write(result),
                            id);
                    return result;
                });
    }

    public Map<String, Object> reservation(
            long actor, String workspace, String run, String action) {
        requireRun(actor, workspace, run, false);
        List<String> rows =
                jdbc.queryForList(
                        "SELECT result FROM agent_reservation_action WHERE run_id=? AND"
                                + " action_id=?",
                        String.class,
                        run,
                        action);
        if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "动作尚未提交");
        return json.read(rows.getFirst());
    }

    private Map<String, Object> approvalView(Map<String, Object> row) {
        String state = row.get("status").toString();
        Timestamp expiry = (Timestamp) row.get("expires_at");
        if (Set.of("PENDING", "APPROVED").contains(state)
                && expiry.toInstant().isBefore(Instant.now())) state = "EXPIRED";
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.get("id"));
        view.put("runId", row.get("run_id"));
        view.put("actionId", row.get("action_id"));
        view.put("toolName", row.get("tool_name"));
        view.put("status", state);
        view.put("version", row.get("version"));
        view.put("args", json.read(row.get("args").toString()));
        view.put("expiresAt", expiry.toInstant().toString());
        view.put(
                "result",
                row.get("result") == null ? null : json.read(row.get("result").toString()));
        return view;
    }

    /** 只描述 Java 的受理和投递事实，执行服务尚无记录时不推测任务是否运行。 */
    public Map<String, Object> submissionFallback(long actor, String workspace, String runId) {
        Map<String, Object> request = requireRun(actor, workspace, runId, false);
        Map<String, Object> delivery =
                jdbc.queryForMap(
                        "SELECT * FROM agent_outbox WHERE run_id=? AND path='/runs' ORDER BY"
                                + " created_at,id LIMIT 1",
                        runId);
        String state = delivery.get("status").toString();
        String description =
                switch (state) {
                    case "DELIVERED" -> "任务已由 Java 投递，执行服务尚未提供运行记录，请稍后刷新。";
                    case "IN_FLIGHT" -> "任务已由 Java 保存，正在投递或等待投递确认。";
                    default ->
                            delivery.get("last_error") == null
                                    ? "任务已由 Java 保存，等待投递。"
                                    : "任务已由 Java 保存；上次投递失败，将自动重试。";
                };
        boolean cancellationRequested = Boolean.TRUE.equals(request.get("writes_blocked"));
        if (cancellationRequested) description += "取消请求已保存，后续业务写操作已禁止。";

        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("id", delivery.get("id"));
        submission.put("status", state);
        submission.put("attempts", delivery.get("attempts"));
        submission.put("lastError", delivery.get("last_error"));
        submission.put(
                "nextAttemptAt",
                "PENDING".equals(state)
                        ? ((Timestamp) delivery.get("next_attempt_at")).toInstant().toString()
                        : null);
        submission.put(
                "leaseExpiresAt",
                delivery.get("lease_expires_at") == null
                        ? null
                        : ((Timestamp) delivery.get("lease_expires_at")).toInstant().toString());
        submission.put("cancellationRequested", cancellationRequested);
        submission.put("message", description);

        String createdAt = ((Timestamp) request.get("created_at")).toInstant().toString();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("workspaceId", workspace);
        result.put("conversationId", request.get("conversation_id"));
        result.put("input", json.read(delivery.get("payload").toString()).get("input"));
        result.put("createdAt", createdAt);
        result.put("updatedAt", createdAt);
        result.put("status", "QUEUED");
        result.put("execution", null);
        result.put("submission", submission);
        return result;
    }
}
