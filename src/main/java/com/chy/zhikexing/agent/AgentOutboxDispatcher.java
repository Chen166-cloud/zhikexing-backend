package com.chy.zhikexing.agent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class AgentOutboxDispatcher {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AgentOutboxPublisher publisher;
    private final AgentJson json;

    public AgentOutboxDispatcher(
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            AgentOutboxPublisher publisher,
            AgentJson json) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.publisher = publisher;
        this.json = json;
    }

    @Scheduled(fixedDelay = 1000)
    public void dispatch() {
        for (int count = 0; count < 10; count++) {
            Map<String, Object> row = claim();
            if (row == null) return;
            AgentCommand command =
                    new AgentCommand(
                            row.get("id").toString(),
                            row.get("path").toString(),
                            row.get("actor_id").toString(),
                            row.get("workspace_id").toString(),
                            json.read(row.get("payload").toString()));
            try {
                // 网络操作在领取事务外执行，避免持锁等待模型服务或消息服务器。
                publisher.publish(command);
                jdbc.update(
                        "UPDATE agent_outbox SET"
                            + " status='DELIVERED',last_error=NULL,lease_owner=NULL,lease_expires_at=NULL"
                            + " WHERE id=? AND lease_owner=? AND status='IN_FLIGHT'",
                        command.id(),
                        row.get("lease_owner"));
            } catch (Exception error) {
                int attempts = ((Number) row.get("attempts")).intValue();
                long delay = Math.min(60, 1L << Math.min(attempts, 6));
                jdbc.update(
                        "UPDATE agent_outbox SET"
                            + " status='PENDING',last_error=?,next_attempt_at=?,lease_owner=NULL,lease_expires_at=NULL"
                            + " WHERE id=? AND lease_owner=? AND status='IN_FLIGHT'",
                        error instanceof AgentDeliveryException
                                ? error.getMessage()
                                : error.getClass().getSimpleName(),
                        Timestamp.from(Instant.now().plusSeconds(delay)),
                        command.id(),
                        row.get("lease_owner"));
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private Map<String, Object> claim() {
        return transaction.execute(
                status -> {
                    // 同一运行先发布创建再发布取消，多个 Java 实例也不能越过尚未完成的前序消息。
                    var rows =
                            jdbc.queryForList(
                                    """
SELECT o.* FROM agent_outbox o
WHERE ((o.status='PENDING' AND o.next_attempt_at<=CURRENT_TIMESTAMP)
    OR (o.status='IN_FLIGHT' AND o.lease_expires_at<CURRENT_TIMESTAMP))
  AND NOT EXISTS (
    SELECT 1 FROM agent_outbox previous
    WHERE previous.run_id=o.run_id AND previous.status<>'DELIVERED'
      AND (previous.created_at<o.created_at
        OR (previous.created_at=o.created_at AND previous.path<o.path)
        OR (previous.created_at=o.created_at AND previous.path=o.path AND previous.id<o.id)))
ORDER BY o.created_at,o.path,o.id LIMIT 1 FOR UPDATE SKIP LOCKED
""");
                    if (rows.isEmpty()) return null;
                    Map<String, Object> row = rows.getFirst();
                    String owner = UUID.randomUUID().toString();
                    int attempts = ((Number) row.get("attempts")).intValue() + 1;
                    jdbc.update(
                            "UPDATE agent_outbox SET"
                                + " status='IN_FLIGHT',lease_owner=?,lease_expires_at=?,attempts=?"
                                + " WHERE id=?",
                            owner,
                            Timestamp.from(Instant.now().plusSeconds(45)),
                            attempts,
                            row.get("id"));
                    row.put("lease_owner", owner);
                    row.put("attempts", attempts);
                    return row;
                });
    }
}
