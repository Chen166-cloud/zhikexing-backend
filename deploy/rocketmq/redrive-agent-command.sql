-- 人工重驱单个已发送 Agent 命令。先在 DLQ/日志核实 command id 并修复失败原因。
-- 替换下面唯一占位符；不修改 run_id/payload/id，不重置审批或业务执行结果。
SET @command_id = 'REPLACE_WITH_VERIFIED_AGENT_OUTBOX_ID';
START TRANSACTION;
SELECT id, run_id, path, status, attempts, last_error
FROM agent_outbox WHERE id=@command_id FOR UPDATE;
UPDATE agent_outbox
SET status='PENDING', next_attempt_at=CURRENT_TIMESTAMP,
    lease_owner=NULL, lease_expires_at=NULL, last_error='MANUAL_DLQ_REDRIVE'
WHERE id=@command_id AND status='DELIVERED';
SELECT ROW_COUNT() AS redriven_rows;
COMMIT;
