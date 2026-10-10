-- 来源顺序与任务状态分开持久化；旧活动任务空版本按 2.0 完成。
ALTER TABLE agent_sessions ADD COLUMN conversation_epoch VARCHAR(100) NULL;
ALTER TABLE agent_sessions ADD COLUMN conversation_turn_sequence BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_sessions ADD COLUMN context_protocol_version VARCHAR(10) NULL;

-- 不复制用户正文或交易凭证；消息 ID 关联历史实际写入结果。
CREATE TABLE agent_conversation_sources (
    source_id VARCHAR(100) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    epoch VARCHAR(100) NOT NULL,
    input_key VARCHAR(110) NOT NULL,
    turn_sequence BIGINT NOT NULL,
    body_sha256 VARCHAR(64) NOT NULL,
    message_id VARCHAR(80) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT ux_context_input UNIQUE (user_id,session_id,epoch,input_key),
    CONSTRAINT ux_context_turn UNIQUE (user_id,session_id,epoch,turn_sequence)
);
