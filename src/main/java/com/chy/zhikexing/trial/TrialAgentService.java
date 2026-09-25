package com.chy.zhikexing.trial;

import static com.chy.zhikexing.agent.AgentJson.*;

import com.chy.zhikexing.agent.AgentBusinessService;
import com.chy.zhikexing.util.SnowflakeIds;
import com.chy.zhikexing.agent.AgentJson;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class TrialAgentService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AgentBusinessService business;
    private final TrialService trials;
    private final AgentJson json;
    private final SnowflakeIds ids;
    private final boolean enabled;

    public TrialAgentService(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            AgentBusinessService business,
            TrialService trials,
            AgentJson json,
            SnowflakeIds ids,
            @Value("${app.agent.rocketmq-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tx.getTransactionManager());
        this.business = business;
        this.trials = trials;
        this.json = json;
        this.ids = ids;
        this.enabled = enabled;
        this.tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Map<String, Object> draft(
            long actor, String workspace, String run, Map<String, Object> body) {
        String action = required(body, "actionId", 128),
                campaign = required(body, "campaignId", 64);
        return tx.execute(
                status -> {
                    business.requireRun(actor, workspace, run, true);
                    business.requireActiveRun(actor, workspace, run);
                    var previous =
                            jdbc.queryForList(
                                    "SELECT id,tool_name,args FROM agent_approval WHERE run_id=?"
                                        + " AND action_id=?",
                                    run,
                                    action);
                    if (!previous.isEmpty()) {
                        var row = previous.getFirst();
                        if (!"claim_trial".equals(row.get("tool_name"))
                                || !campaign.equals(
                                        json.read(row.get("args").toString()).get("campaignId")))
                            throw error(HttpStatus.CONFLICT, "同一动作不能更换工具或活动");
                        return business.approval(actor, workspace, row.get("id").toString());
                    }
                    var event = trials.campaign(actor, workspace, campaign);
                    if (!"LIVE".equals(event.get("status")))
                        throw error(HttpStatus.CONFLICT, "活动尚未发布");
                    Instant end = Instant.parse(event.get("endsAt").toString());
                    if (!end.isAfter(Instant.now())) throw error(HttpStatus.CONFLICT, "活动已结束");
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("campaignId", campaign);
                    for (String field :
                            List.of(
                                    "title",
                                    "courseName",
                                    "schoolName",
                                    "startsAt",
                                    "endsAt",
                                    "amountCent")) args.put(field, event.get(field));
                    args.put("notice", "批准仅授权提交抢课，不保证有名额；受理后取消Agent不会撤销报名。");
                    String id = ids.nextString();
                    Instant expiry = Instant.now().plusSeconds(900);
                    if (end.isBefore(expiry)) expiry = end;
                    jdbc.update(
                            """
INSERT INTO agent_approval(id,run_id,workspace_id,actor_id,action_id,status,args,args_hash,expires_at,tool_name)
VALUES(?,?,?,?,?,'PENDING',?,?,?,'claim_trial')
""",
                            id,
                            run,
                            workspace,
                            actor,
                            action,
                            json.write(args),
                            json.hash(args),
                            Timestamp.from(expiry));
                    return business.approval(actor, workspace, id);
                });
    }

    public Map<String, Object> execute(
            long actor, String workspace, String run, Map<String, Object> body) {
        String action = required(body, "actionId", 128),
                approvalId = required(body, "approvalId", 64);
        return tx.execute(
                status -> {
                    business.requireRun(actor, workspace, run, true);
                    var existing =
                            jdbc.queryForList(
                                    "SELECT approval_id FROM trial_claim_request WHERE run_id=? AND"
                                        + " action_id=?",
                                    run,
                                    action);
                    if (!existing.isEmpty()) {
                        if (!approvalId.equals(existing.getFirst().get("approval_id")))
                            throw error(HttpStatus.CONFLICT, "审批不匹配");
                        return trials.byAction(actor, workspace, run, action);
                    }
                    if (!enabled) throw error(HttpStatus.SERVICE_UNAVAILABLE, "试听抢课需要启用RocketMQ");
                    business.requireActiveRun(actor, workspace, run);
                    var approval = business.approval(actor, workspace, approvalId);
                    if (!run.equals(approval.get("runId"))
                            || !action.equals(approval.get("actionId"))
                            || !"claim_trial".equals(approval.get("toolName")))
                        throw error(HttpStatus.CONFLICT, "审批与抢课动作不匹配");
                    if (!"APPROVED".equals(approval.get("status")))
                        throw error(HttpStatus.CONFLICT, "抢课必须经过用户审批且审批未过期");
                    var raw =
                            jdbc.queryForMap(
                                    "SELECT args,args_hash FROM agent_approval WHERE id=?",
                                    approvalId);
                    Map<String, Object> args = json.read(raw.get("args").toString());
                    if (!json.hash(args).equals(raw.get("args_hash")))
                        throw error(HttpStatus.CONFLICT, "审批参数校验失败");
                    var result =
                            trials.enqueue(
                                    actor,
                                    workspace,
                                    args.get("campaignId").toString(),
                                    "AGENT",
                                    approvalId,
                                    run,
                                    action,
                                    approvalId);
                    jdbc.update(
                            "UPDATE agent_approval SET status='EXECUTED',version=version+1,result=?"
                                + " WHERE id=?",
                            json.write(result),
                            approvalId);
                    return result;
                });
    }
}
