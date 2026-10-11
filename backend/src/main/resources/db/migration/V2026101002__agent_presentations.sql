-- 最终展示顺序仅用于会话指代，私有确认凭证仍在原授权表，不进入此公开引用表。
CREATE TABLE IF NOT EXISTS agent_presentations (
    presentation_id VARCHAR(100) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    epoch VARCHAR(100) NOT NULL,
    source_id VARCHAR(100) NOT NULL,
    resource_type VARCHAR(20) NOT NULL,
    presentation_version INT NOT NULL,
    ordered_ids TEXT NOT NULL,
    CONSTRAINT ux_presentation_source_type UNIQUE (source_id, resource_type)
);
