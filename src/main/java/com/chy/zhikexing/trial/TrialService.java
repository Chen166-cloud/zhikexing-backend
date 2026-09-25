package com.chy.zhikexing.trial;

import static com.chy.zhikexing.agent.AgentJson.*;

import com.chy.zhikexing.agent.AgentBusinessService;
import com.chy.zhikexing.util.SnowflakeIds;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** MySQL is the durable decision journal and final order authority. */
@Service
public class TrialService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AgentBusinessService business;
    private final TrialInventory inventory;
    private final SnowflakeIds ids;

    public TrialService(
            JdbcTemplate jdbc,
            TransactionTemplate template,
            AgentBusinessService business,
            TrialInventory inventory,
            SnowflakeIds ids) {
        this.jdbc = jdbc;
        this.business = business;
        this.inventory = inventory;
        this.ids = ids;
        this.tx = new TransactionTemplate(template.getTransactionManager());
        tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    private void owner(long actor, String workspace) {
        business.requireMember(actor, workspace);
        if (!"OWNER"
                .equals(
                        jdbc.queryForObject(
                                "SELECT role FROM agent_workspace_member WHERE workspace_id=? AND"
                                    + " user_id=?",
                                String.class,
                                workspace,
                                actor))) throw error(HttpStatus.FORBIDDEN, "只有空间所有者可以管理试听活动");
    }

    /** Existing business records offered as choices when an owner creates a trial campaign. */
    public Map<String, Object> catalog(long actor, String workspace) {
        business.requireMember(actor, workspace);
        var courses =
                jdbc.query(
                        "SELECT id,name FROM course ORDER BY id LIMIT 500",
                        (r, n) -> Map.of("id", r.getString("id"), "name", r.getString("name")));
        var campuses =
                jdbc.query(
                        "SELECT id,name,city FROM school ORDER BY id LIMIT 200",
                        (r, n) ->
                                Map.of(
                                        "id", r.getString("id"),
                                        "name", r.getString("name"),
                                        "city", Objects.toString(r.getString("city"), "")));
        return Map.of("courses", courses, "campuses", campuses);
    }

    public List<Map<String, Object>> campaigns(long actor, String workspace) {
        business.requireMember(actor, workspace);
        return jdbc
                .queryForList(
                        """
                        SELECT c.*,p.name course_name,s.name school_name FROM trial_campaign c
                        JOIN course p ON c.course_id=p.id JOIN school s ON c.school_id=s.id
                        WHERE c.workspace_id=? ORDER BY c.starts_at DESC LIMIT 100
                        """,
                        workspace)
                .stream()
                .map(this::campaignView)
                .toList();
    }

    public Map<String, Object> campaign(long actor, String workspace, String id) {
        business.requireMember(actor, workspace);
        return campaignView(campaignRow(workspace, id, false));
    }

    private Map<String, Object> campaignRow(String workspace, String id, boolean lock) {
        var rows =
                jdbc.queryForList(
                        """
                        SELECT c.*,p.name course_name,s.name school_name FROM trial_campaign c
                        JOIN course p ON c.course_id=p.id JOIN school s ON c.school_id=s.id
                        WHERE c.workspace_id=? AND c.id=?
                        """
                                + (lock ? " FOR UPDATE" : ""),
                        workspace,
                        id);
        if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "试听活动不存在");
        return rows.getFirst();
    }

    private Map<String, Object> campaignView(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("id", "title", "capacity", "remaining", "status"))
            result.put(field, row.get(field));
        result.put("courseId", row.get("course_id").toString());
        result.put("schoolId", row.get("school_id").toString());
        result.put("courseName", row.get("course_name"));
        result.put("schoolName", row.get("school_name"));
        result.put("startsAt", ((Timestamp) row.get("starts_at")).toInstant().toString());
        result.put("endsAt", ((Timestamp) row.get("ends_at")).toInstant().toString());
        result.put("amountCent", 0);
        result.put("remainingMeaning", "数据库未确认名额；不代表实时可抢数量");
        return result;
    }

    public Map<String, Object> create(long actor, String workspace, Map<String, Object> body) {
        owner(actor, workspace);
        String title = required(body, "title", 120),
                course = required(body, "courseId", 32),
                school = required(body, "schoolId", 32);
        long courseId, schoolId;
        try {
            if (!course.matches("[0-9]{1,19}") || !school.matches("[0-9]{1,19}"))
                throw new NumberFormatException();
            courseId = Long.parseLong(course);
            schoolId = Long.parseLong(school);
            if (courseId <= 0 || schoolId <= 0) throw new NumberFormatException();
        } catch (NumberFormatException invalidId) {
            throw error(HttpStatus.BAD_REQUEST, "课程和校区编号无效");
        }
        if (!(body.get("capacity") instanceof Integer capacity) || capacity < 1 || capacity > 10000)
            throw error(HttpStatus.BAD_REQUEST, "试听名额必须为1至10000的整数");
        Instant start, end;
        try {
            start = Instant.parse(required(body, "startsAt", 40));
            end = Instant.parse(required(body, "endsAt", 40));
        } catch (java.time.format.DateTimeParseException e) {
            throw error(HttpStatus.BAD_REQUEST, "时间必须包含时区，建议ISO-8601 UTC");
        }
        if (!end.isAfter(start)
                || !end.isAfter(Instant.now())
                || end.isAfter(start.plusSeconds(30 * 86400)))
            throw error(HttpStatus.BAD_REQUEST, "结束时间必须晚于开始时间和当前时间，窗口不超过30天");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM course WHERE id=?", Integer.class, courseId)
                        == 0
                || jdbc.queryForObject(
                                "SELECT COUNT(*) FROM school WHERE id=?", Integer.class, schoolId)
                        == 0) throw error(HttpStatus.BAD_REQUEST, "课程或校区不存在");
        String id = ids.nextString();
        jdbc.update(
                """
INSERT INTO trial_campaign(id,workspace_id,course_id,school_id,title,capacity,remaining,starts_at,ends_at,created_by)
VALUES(?,?,?,?,?,?,?,?,?,?)
""",
                id,
                workspace,
                courseId,
                schoolId,
                title,
                capacity,
                capacity,
                Timestamp.from(start),
                Timestamp.from(end),
                actor);
        return campaign(actor, workspace, id);
    }

    public Map<String, Object> publish(long actor, String workspace, String id) {
        owner(actor, workspace);
        return tx.execute(
                status -> {
                    var row = campaignRow(workspace, id, true);
                    // A published campaign must NEVER be auto-rewarmed after Redis loss.
                    if ("DRAFT".equals(row.get("status"))) {
                        if (!((Timestamp) row.get("ends_at")).toInstant().isAfter(Instant.now()))
                            throw error(HttpStatus.CONFLICT, "活动已结束");
                        inventory.publish(
                                id,
                                ((Number) row.get("capacity")).intValue(),
                                ((Timestamp) row.get("starts_at")).toInstant(),
                                ((Timestamp) row.get("ends_at")).toInstant());
                        jdbc.update("UPDATE trial_campaign SET status='LIVE' WHERE id=?", id);
                    }
                    return campaign(actor, workspace, id);
                });
    }

    public Map<String, Object> submit(
            long actor, String workspace, String campaign, String clientRequestId) {
        return enqueue(actor, workspace, campaign, "DIRECT", clientRequestId, null, null, null);
    }

    public Map<String, Object> pause(long actor, String workspace, String id) {
        owner(actor, workspace);
        return tx.execute(
                status -> {
                    campaignRow(workspace, id, true);
                    inventory.pause(id);
                    jdbc.update("UPDATE trial_campaign SET status='PAUSED' WHERE id=?", id);
                    return campaign(actor, workspace, id);
                });
    }

    /** Diagnostic snapshot: cache and DB are read separately, never auto-repair from this view. */
    public Map<String, Object> reconciliation(long actor, String workspace, String id) {
        owner(actor, workspace);
        campaign(actor, workspace, id);
        Map<String, Object> view =
                new LinkedHashMap<>(
                        jdbc.queryForMap(
                                """
SELECT c.capacity,c.remaining,
  (SELECT COUNT(*) FROM trial_order o WHERE o.campaign_id=c.id) AS confirmedOrders,
  (SELECT COUNT(*) FROM trial_claim_request r WHERE r.campaign_id=c.id AND r.status='PENDING') AS pending,
  (SELECT COUNT(*) FROM trial_claim_request r WHERE r.campaign_id=c.id AND r.status='RESERVED') AS reserved,
  (SELECT COUNT(*) FROM trial_claim_request r WHERE r.campaign_id=c.id AND r.release_pending=TRUE) AS releasePending
FROM trial_campaign c WHERE c.id=? AND c.workspace_id=?
""",
                                id,
                                workspace));
        view.put(
                "databaseInvariantHolds",
                ((Number) view.get("capacity")).longValue()
                        == ((Number) view.get("remaining")).longValue()
                                + ((Number) view.get("confirmedOrders")).longValue());
        try {
            view.put("redis", inventory.snapshot(id));
        } catch (org.springframework.dao.DataAccessException unavailable) {
            view.put("redis", Map.of("status", "UNAVAILABLE"));
        }
        view.put("note", "Redis与数据库不在同一快照；暂停准入并排空在途请求后人工对账，禁止直接覆盖库存。");
        return view;
    }

    /** Can join the approval transaction; no broker calls occur while its locks are held. */
    public Map<String, Object> enqueue(
            long actor,
            String workspace,
            String campaign,
            String source,
            String key,
            String run,
            String action,
            String approval) {
        business.requireMember(actor, workspace);
        return tx.execute(
                status -> {
                    // Serialize only this actor, never all participants on the campaign hot row.
                    jdbc.queryForObject(
                            "SELECT user_id FROM agent_workspace_member WHERE workspace_id=? AND"
                                + " user_id=? FOR UPDATE",
                            Long.class,
                            workspace,
                            actor);
                    var existing =
                            jdbc.queryForList(
                                    "SELECT * FROM trial_claim_request WHERE workspace_id=? AND"
                                        + " actor_id=? AND source=? AND client_request_id=?",
                                    workspace,
                                    actor,
                                    source,
                                    key);
                    if (!existing.isEmpty()) {
                        var previous = existing.getFirst();
                        if (!campaign.equals(previous.get("campaign_id")))
                            throw error(HttpStatus.CONFLICT, "同一请求编号不能更换活动");
                        return resultView(previous);
                    }
                    if (!inventory.allow(actor))
                        throw error(HttpStatus.TOO_MANY_REQUESTS, "抢课请求过于频繁，请使用原请求编号稍后查询");
                    var event = campaignRow(workspace, campaign, false);
                    Instant now = Instant.now();
                    if (!"LIVE".equals(event.get("status")))
                        throw error(HttpStatus.CONFLICT, "活动尚未发布");
                    if (now.isBefore(((Timestamp) event.get("starts_at")).toInstant()))
                        throw error(HttpStatus.CONFLICT, "抢课尚未开始，审批不提前占名额");
                    if (!now.isBefore(((Timestamp) event.get("ends_at")).toInstant()))
                        throw error(HttpStatus.CONFLICT, "抢课已结束");
                    var prior =
                            jdbc.queryForList(
                                    "SELECT id FROM trial_claim_request WHERE campaign_id=? AND"
                                        + " actor_id=?",
                                    campaign,
                                    actor);
                    if (!prior.isEmpty()) throw error(HttpStatus.CONFLICT, "每人每场活动只允许一次参与，请查询原请求");
                    String id = ids.nextString();
                    jdbc.update(
                            """
INSERT INTO trial_claim_request(id,campaign_id,workspace_id,actor_id,source,client_request_id,run_id,action_id,approval_id)
VALUES(?,?,?,?,?,?,?,?,?)
""",
                            id,
                            campaign,
                            workspace,
                            actor,
                            source,
                            key,
                            run,
                            action,
                            approval);
                    return result(actor, workspace, id);
                });
    }

    public Map<String, Object> result(long actor, String workspace, String id) {
        business.requireMember(actor, workspace);
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM trial_claim_request WHERE id=? AND workspace_id=? AND"
                            + " actor_id=?",
                        id,
                        workspace,
                        actor);
        if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "抢课请求不存在");
        return resultView(rows.getFirst());
    }

    /** Durable, actor-scoped receipts survive a browser refresh or a change of device. */
    public List<Map<String, Object>> claims(long actor, String workspace) {
        business.requireMember(actor, workspace);
        return jdbc.queryForList(
                        """
                        SELECT r.*,c.title AS campaign_title,o.id AS order_id
                        FROM trial_claim_request r
                        JOIN trial_campaign c ON c.id=r.campaign_id
                        LEFT JOIN trial_order o ON o.request_id=r.id
                        WHERE r.workspace_id=? AND r.actor_id=?
                        ORDER BY r.created_at DESC,r.id DESC LIMIT 100
                        """,
                        workspace,
                        actor)
                .stream()
                .map(this::resultView)
                .toList();
    }

    public Map<String, Object> byAction(long actor, String workspace, String run, String action) {
        business.requireRun(actor, workspace, run, false);
        // A later chat run may query the actor's previous action; never use the model's user
        // identity.
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM trial_claim_request WHERE action_id=? AND actor_id=? AND"
                            + " workspace_id=?",
                        action,
                        actor,
                        workspace);
        if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "动作尚未提交");
        return resultView(rows.getFirst());
    }

    private Map<String, Object> resultView(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", row.get("id"));
        result.put("campaignId", row.get("campaign_id"));
        result.put("actionId", row.get("action_id"));
        result.put("status", row.get("status"));
        result.put("reason", row.get("reason"));
        result.put("source", row.get("source"));
        result.put("createdAt", ((Timestamp) row.get("created_at")).toInstant().toString());
        if (row.containsKey("campaign_title")) result.put("campaignTitle", row.get("campaign_title"));
        if (row.containsKey("order_id")) result.put("orderId", row.get("order_id"));
        else {
            var orders =
                    jdbc.queryForList(
                            "SELECT id FROM trial_order WHERE request_id=?",
                            String.class,
                            row.get("id"));
            result.put("orderId", orders.isEmpty() ? null : orders.getFirst());
        }
        result.put("amountCent", 0);
        return result;
    }

    /** Called after HALF persisted. Any ambiguous failure is UNKNOWN, never guessed rollback. */
    public String reserve(String requestId) {
        return tx.execute(
                status -> {
                    var rows =
                            jdbc.queryForList(
                                    "SELECT * FROM trial_claim_request WHERE id=? FOR UPDATE",
                                    requestId);
                    if (rows.isEmpty()) return "ROLLBACK";
                    var row = rows.getFirst();
                    String state = row.get("status").toString();
                    if (Set.of("RESERVED", "SUCCEEDED").contains(state)) return "COMMIT";
                    if ("REJECTED".equals(state)) return "ROLLBACK";
                    String admission =
                            inventory.reserve(
                                    row.get("campaign_id").toString(),
                                    requestId,
                                    ((Number) row.get("actor_id")).longValue());
                    if ("NOT_READY".equals(admission))
                        throw new IllegalStateException("TRIAL_INVENTORY_NOT_READY");
                    if (!Set.of(
                                    "RESERVED",
                                    "NOT_STARTED",
                                    "ENDED",
                                    "SOLD_OUT",
                                    "DUPLICATE",
                                    "RELEASED",
                                    "PAUSED")
                            .contains(admission))
                        throw new IllegalStateException("Unknown inventory response");
                    boolean accepted = "RESERVED".equals(admission);
                    jdbc.update(
                            "UPDATE trial_claim_request SET status=?,reason=? WHERE id=?",
                            accepted ? "RESERVED" : "REJECTED",
                            accepted ? null : admission,
                            requestId);
                    return accepted ? "COMMIT" : "ROLLBACK";
                });
    }

    /** Read only checker: recovery resends PENDING requests and replays idempotent Lua. */
    public String check(String id) {
        var states =
                jdbc.queryForList(
                        "SELECT status FROM trial_claim_request WHERE id=?", String.class, id);
        if (states.isEmpty()) return "ROLLBACK";
        return switch (states.getFirst()) {
            case "RESERVED", "SUCCEEDED" -> "COMMIT";
            case "REJECTED" -> "ROLLBACK";
            default -> "UNKNOWN";
        };
    }

    public void consume(String id) {
        tx.executeWithoutResult(
                status -> {
                    var rows =
                            jdbc.queryForList(
                                    "SELECT * FROM trial_claim_request WHERE id=? FOR UPDATE", id);
                    if (rows.isEmpty()) throw new IllegalArgumentException("Unknown claim request");
                    var row = rows.getFirst();
                    String state = row.get("status").toString();
                    if (Set.of("SUCCEEDED", "REJECTED").contains(state)) return;
                    if (!"RESERVED".equals(state))
                        throw new IllegalStateException("Admission not committed");
                    String campaign = row.get("campaign_id").toString();
                    int deducted =
                            jdbc.update(
                                    "UPDATE trial_campaign SET remaining=remaining-1 WHERE id=? AND"
                                        + " remaining>0",
                                    campaign);
                    if (deducted == 0) {
                        jdbc.update(
                                "UPDATE trial_claim_request SET"
                                    + " status='REJECTED',reason='DB_SOLD_OUT',release_pending=TRUE"
                                    + " WHERE id=?",
                                id);
                        return;
                    }
                    jdbc.update(
                            "INSERT INTO"
                                + " trial_order(id,request_id,campaign_id,workspace_id,actor_id)"
                                + " VALUES(?,?,?,?,?)",
                            ids.nextString(),
                            id,
                            campaign,
                            row.get("workspace_id"),
                            row.get("actor_id"));
                    jdbc.update("UPDATE trial_claim_request SET status='SUCCEEDED' WHERE id=?", id);
                });
    }

    public List<String> due() {
        return tx.execute(
                status -> {
                    var ids =
                            jdbc.queryForList(
                                    """
SELECT id FROM trial_claim_request WHERE status IN ('PENDING','RESERVED')
AND next_attempt_at<=CURRENT_TIMESTAMP(3) ORDER BY next_attempt_at LIMIT 30 FOR UPDATE SKIP LOCKED
""",
                                    String.class);
                    for (String id : ids)
                        jdbc.update(
                                "UPDATE trial_claim_request SET"
                                    + " next_attempt_at=?,attempts=attempts+1 WHERE id=?",
                                Timestamp.from(Instant.now().plusSeconds(30)),
                                id);
                    return ids;
                });
    }

    public void compensate() {
        var rows =
                jdbc.queryForList(
                        "SELECT id,campaign_id,actor_id FROM trial_claim_request WHERE"
                            + " release_pending=TRUE LIMIT 30");
        for (var row : rows) {
            if (inventory.release(
                    row.get("campaign_id").toString(),
                    row.get("id").toString(),
                    ((Number) row.get("actor_id")).longValue()))
                jdbc.update(
                        "UPDATE trial_claim_request SET release_pending=FALSE WHERE id=? AND"
                            + " status='REJECTED'",
                        row.get("id"));
        }
    }
}
