-- 领取与网络发送分离；发送进程退出后，其他实例可以接续过期租约。
ALTER TABLE agent_outbox ADD COLUMN lease_owner VARCHAR(64) NULL;
ALTER TABLE agent_outbox ADD COLUMN lease_expires_at TIMESTAMP NULL;
CREATE INDEX idx_agent_outbox_lease ON agent_outbox(status,lease_expires_at);
