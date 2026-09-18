-- 新表独立命名，不删除或覆盖旧聊天数据。
CREATE TABLE agent_workspace (
  id VARCHAR(64) PRIMARY KEY, name VARCHAR(120) NOT NULL,
  owner_id BIGINT NOT NULL, personal_owner_id BIGINT NULL UNIQUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE agent_workspace_member (
  workspace_id VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL, role VARCHAR(20) NOT NULL,
  PRIMARY KEY(workspace_id,user_id),
  FOREIGN KEY(workspace_id) REFERENCES agent_workspace(id)
);
CREATE TABLE agent_task_request (
  run_id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL,
  actor_id BIGINT NOT NULL, conversation_id VARCHAR(64) NOT NULL,
  client_request_id VARCHAR(128) NOT NULL, payload_hash VARCHAR(64) NOT NULL,
  writes_blocked BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(workspace_id,actor_id,client_request_id),
  FOREIGN KEY(workspace_id) REFERENCES agent_workspace(id)
);
CREATE TABLE agent_outbox (
  id VARCHAR(64) PRIMARY KEY, run_id VARCHAR(64) NOT NULL,
  workspace_id VARCHAR(64) NOT NULL, actor_id BIGINT NOT NULL,
  path VARCHAR(256) NOT NULL, payload TEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_error VARCHAR(100) NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(run_id) REFERENCES agent_task_request(run_id)
);
CREATE INDEX idx_agent_outbox_pending ON agent_outbox(status,next_attempt_at);
CREATE TABLE agent_approval (
  id VARCHAR(64) PRIMARY KEY, run_id VARCHAR(64) NOT NULL,
  workspace_id VARCHAR(64) NOT NULL, actor_id BIGINT NOT NULL,
  action_id VARCHAR(128) NOT NULL, status VARCHAR(20) NOT NULL,
  version INT NOT NULL DEFAULT 1, args TEXT NOT NULL, args_hash VARCHAR(64) NOT NULL,
  expires_at TIMESTAMP NOT NULL, result TEXT NULL,
  UNIQUE(run_id,action_id), FOREIGN KEY(run_id) REFERENCES agent_task_request(run_id)
);
CREATE TABLE agent_reservation_action (
  action_id VARCHAR(128) NOT NULL, run_id VARCHAR(64) NOT NULL,
  approval_id VARCHAR(64) NOT NULL UNIQUE, reservation_id INT NOT NULL,
  result TEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(run_id,action_id), FOREIGN KEY(run_id) REFERENCES agent_task_request(run_id),
  FOREIGN KEY(reservation_id) REFERENCES course_reservation(id)
);
