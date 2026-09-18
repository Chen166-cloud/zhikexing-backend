-- Spring AI 2 按序号读取旧会话；保留原正文、时间与主键，同秒消息按原 id 排序。
ALTER TABLE spring_ai_chat_memory ADD COLUMN sequence_id BIGINT NULL;

UPDATE spring_ai_chat_memory memory
JOIN (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY conversation_id ORDER BY `timestamp`,id) - 1 AS seq
    FROM spring_ai_chat_memory
) ordered ON memory.id=ordered.id
SET memory.sequence_id=ordered.seq;

ALTER TABLE spring_ai_chat_memory MODIFY COLUMN sequence_id BIGINT NOT NULL;
CREATE INDEX idx_chat_memory_conversation_sequence ON spring_ai_chat_memory(conversation_id,sequence_id);
