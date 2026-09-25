-- 免费试听独立建模；金额恒为 0，不能用普通预约表冒充已抢到名额。
ALTER TABLE agent_approval ADD COLUMN tool_name VARCHAR(40) NOT NULL DEFAULT 'reserve_course';
CREATE TABLE trial_campaign (
  id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL,
  course_id INT NOT NULL, school_id INT NOT NULL, title VARCHAR(120) NOT NULL,
  capacity INT NOT NULL, remaining INT NOT NULL,
  starts_at TIMESTAMP(3) NOT NULL, ends_at TIMESTAMP(3) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', created_by BIGINT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT ck_trial_stock CHECK (remaining >= 0 AND remaining <= capacity AND capacity > 0),
  CONSTRAINT ck_trial_window CHECK (ends_at > starts_at),
  FOREIGN KEY(workspace_id) REFERENCES agent_workspace(id),
  FOREIGN KEY(course_id) REFERENCES course(id), FOREIGN KEY(school_id) REFERENCES school(id),
  INDEX idx_trial_workspace(workspace_id,status,starts_at)
);
CREATE TABLE trial_claim_request (
  id VARCHAR(64) PRIMARY KEY, campaign_id VARCHAR(64) NOT NULL,
  workspace_id VARCHAR(64) NOT NULL, actor_id BIGINT NOT NULL,
  source VARCHAR(16) NOT NULL, client_request_id VARCHAR(128) NOT NULL,
  run_id VARCHAR(64) NULL, action_id VARCHAR(128) NULL, approval_id VARCHAR(64) NULL UNIQUE,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING', reason VARCHAR(64) NULL,
  release_pending BOOLEAN NOT NULL DEFAULT FALSE,
  attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE(workspace_id,actor_id,source,client_request_id),
  UNIQUE(campaign_id,actor_id), UNIQUE(run_id,action_id), UNIQUE(workspace_id,actor_id,action_id),
  FOREIGN KEY(campaign_id) REFERENCES trial_campaign(id),
  INDEX idx_trial_recovery(status,next_attempt_at), INDEX idx_trial_release(release_pending)
);
CREATE TABLE trial_order (
  id VARCHAR(64) PRIMARY KEY, request_id VARCHAR(64) NOT NULL UNIQUE,
  campaign_id VARCHAR(64) NOT NULL, workspace_id VARCHAR(64) NOT NULL, actor_id BIGINT NOT NULL,
  amount_cent BIGINT NOT NULL DEFAULT 0, status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE(campaign_id,actor_id), CHECK(amount_cent=0),
  FOREIGN KEY(request_id) REFERENCES trial_claim_request(id)
);
