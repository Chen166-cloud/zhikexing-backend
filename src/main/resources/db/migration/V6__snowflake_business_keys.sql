-- Existing identifiers remain stable for cross-service references. All subsequent writes use
-- application-generated Snowflake IDs; no application table retains AUTO_INCREMENT or
-- physical foreign-key constraints. Services validate logical references before writes.
-- Node 1023 and three disjoint synthetic time windows are reserved for this backfill.

SET @drops = (SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP FOREIGN KEY `',CONSTRAINT_NAME,'`') SEPARATOR ', ')
              FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
                AND TABLE_NAME='agent_workspace_member' AND REFERENCED_TABLE_NAME IS NOT NULL);
SET @ddl = CONCAT('ALTER TABLE agent_workspace_member ', @drops);
PREPARE snowflake_ddl FROM @ddl;
EXECUTE snowflake_ddl;
DEALLOCATE PREPARE snowflake_ddl;

SET @drops = (SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP FOREIGN KEY `',CONSTRAINT_NAME,'`') SEPARATOR ', ')
              FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
                AND TABLE_NAME='agent_task_request' AND REFERENCED_TABLE_NAME IS NOT NULL);
SET @ddl = CONCAT('ALTER TABLE agent_task_request ', @drops);
PREPARE snowflake_ddl FROM @ddl;
EXECUTE snowflake_ddl;
DEALLOCATE PREPARE snowflake_ddl;

SET @drops = (SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP FOREIGN KEY `',CONSTRAINT_NAME,'`') SEPARATOR ', ')
              FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
                AND TABLE_NAME='agent_outbox' AND REFERENCED_TABLE_NAME IS NOT NULL);
SET @ddl = CONCAT('ALTER TABLE agent_outbox ', @drops);
PREPARE snowflake_ddl FROM @ddl;
EXECUTE snowflake_ddl;
DEALLOCATE PREPARE snowflake_ddl;

SET @drops = (SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP FOREIGN KEY `',CONSTRAINT_NAME,'`') SEPARATOR ', ')
              FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
                AND TABLE_NAME='agent_approval' AND REFERENCED_TABLE_NAME IS NOT NULL);
SET @ddl = CONCAT('ALTER TABLE agent_approval ', @drops);
PREPARE snowflake_ddl FROM @ddl;
EXECUTE snowflake_ddl;
DEALLOCATE PREPARE snowflake_ddl;

SET @drops = (SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP FOREIGN KEY `',CONSTRAINT_NAME,'`') SEPARATOR ', ')
              FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
                AND TABLE_NAME='agent_reservation_action' AND REFERENCED_TABLE_NAME IS NOT NULL);
SET @ddl = CONCAT('ALTER TABLE agent_reservation_action ', @drops);
PREPARE snowflake_ddl FROM @ddl;
EXECUTE snowflake_ddl;
DEALLOCATE PREPARE snowflake_ddl;

SET @drops = (SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP FOREIGN KEY `',CONSTRAINT_NAME,'`') SEPARATOR ', ')
              FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
                AND TABLE_NAME='trial_campaign' AND REFERENCED_TABLE_NAME IS NOT NULL);
SET @ddl = CONCAT('ALTER TABLE trial_campaign ', @drops);
PREPARE snowflake_ddl FROM @ddl;
EXECUTE snowflake_ddl;
DEALLOCATE PREPARE snowflake_ddl;

SET @drops = (SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP FOREIGN KEY `',CONSTRAINT_NAME,'`') SEPARATOR ', ')
              FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
                AND TABLE_NAME='trial_claim_request' AND REFERENCED_TABLE_NAME IS NOT NULL);
SET @ddl = CONCAT('ALTER TABLE trial_claim_request ', @drops);
PREPARE snowflake_ddl FROM @ddl;
EXECUTE snowflake_ddl;
DEALLOCATE PREPARE snowflake_ddl;

SET @drops = (SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP FOREIGN KEY `',CONSTRAINT_NAME,'`') SEPARATOR ', ')
              FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
                AND TABLE_NAME='trial_order' AND REFERENCED_TABLE_NAME IS NOT NULL);
SET @ddl = CONCAT('ALTER TABLE trial_order ', @drops);
PREPARE snowflake_ddl FROM @ddl;
EXECUTE snowflake_ddl;
DEALLOCATE PREPARE snowflake_ddl;

ALTER TABLE user_info MODIFY COLUMN id BIGINT NOT NULL;
ALTER TABLE spring_ai_chat_memory MODIFY COLUMN id BIGINT NOT NULL;
ALTER TABLE iiip_pdf_file MODIFY COLUMN id BIGINT NOT NULL;
ALTER TABLE course MODIFY COLUMN id BIGINT NOT NULL;
ALTER TABLE school MODIFY COLUMN id BIGINT NOT NULL;
ALTER TABLE course_reservation MODIFY COLUMN id BIGINT NOT NULL;
ALTER TABLE trial_campaign
  MODIFY COLUMN course_id BIGINT NOT NULL,
  MODIFY COLUMN school_id BIGINT NOT NULL;
ALTER TABLE agent_reservation_action MODIFY COLUMN reservation_id BIGINT NOT NULL;

-- The client-visible conversation ID is a correlation key, not the row's primary key.
ALTER TABLE iiip_chat_record CHANGE COLUMN id conversation_id VARCHAR(128) NOT NULL;
ALTER TABLE iiip_chat_record ADD COLUMN id BIGINT NULL FIRST;
CREATE TEMPORARY TABLE snowflake_chat_rows AS
  SELECT conversation_id, ROW_NUMBER() OVER (ORDER BY conversation_id) - 1 AS ordinal
  FROM iiip_chat_record;
UPDATE iiip_chat_record row_data
JOIN snowflake_chat_rows numbered ON numbered.conversation_id=row_data.conversation_id
SET row_data.id = (31556000000 + FLOOR(numbered.ordinal / 4096)) * 4194304
                  + 4190208 + MOD(numbered.ordinal, 4096);
DROP TEMPORARY TABLE snowflake_chat_rows;
ALTER TABLE iiip_chat_record
  MODIFY COLUMN id BIGINT NOT NULL,
  DROP PRIMARY KEY,
  ADD PRIMARY KEY(id),
  ADD UNIQUE KEY uk_chat_conversation(conversation_id);

ALTER TABLE agent_workspace_member ADD COLUMN id BIGINT NULL FIRST;
CREATE TEMPORARY TABLE snowflake_member_rows AS
  SELECT workspace_id,user_id,
         ROW_NUMBER() OVER (ORDER BY workspace_id,user_id) - 1 AS ordinal
  FROM agent_workspace_member;
UPDATE agent_workspace_member row_data
JOIN snowflake_member_rows numbered
  ON numbered.workspace_id=row_data.workspace_id AND numbered.user_id=row_data.user_id
SET row_data.id = (31536000000 + FLOOR(numbered.ordinal / 4096)) * 4194304
                  + 4190208 + MOD(numbered.ordinal, 4096);
DROP TEMPORARY TABLE snowflake_member_rows;
ALTER TABLE agent_workspace_member MODIFY COLUMN id BIGINT NOT NULL;
ALTER TABLE agent_workspace_member ADD UNIQUE KEY uk_workspace_member(workspace_id,user_id);
ALTER TABLE agent_workspace_member DROP PRIMARY KEY, ADD PRIMARY KEY(id);
CREATE INDEX idx_workspace_member_user ON agent_workspace_member(user_id);

ALTER TABLE agent_reservation_action ADD COLUMN id BIGINT NULL FIRST;
CREATE TEMPORARY TABLE snowflake_reservation_rows AS
  SELECT run_id,action_id, ROW_NUMBER() OVER (ORDER BY run_id,action_id) - 1 AS ordinal
  FROM agent_reservation_action;
UPDATE agent_reservation_action row_data
JOIN snowflake_reservation_rows numbered
  ON numbered.run_id=row_data.run_id AND numbered.action_id=row_data.action_id
SET row_data.id = (31546000000 + FLOOR(numbered.ordinal / 4096)) * 4194304
                  + 4190208 + MOD(numbered.ordinal, 4096);
DROP TEMPORARY TABLE snowflake_reservation_rows;
ALTER TABLE agent_reservation_action MODIFY COLUMN id BIGINT NOT NULL;
ALTER TABLE agent_reservation_action ADD UNIQUE KEY uk_reservation_action(run_id,action_id);
ALTER TABLE agent_reservation_action DROP PRIMARY KEY, ADD PRIMARY KEY(id);
