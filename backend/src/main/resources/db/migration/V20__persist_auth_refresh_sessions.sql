-- 长期登录会话：只存刷新凭据摘要；原账号删除时清理所属会话，不修改旧业务表和历史数据。
CREATE TABLE auth_sessions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    previous_token_hash VARCHAR(64),
    previous_request_hash VARCHAR(64),
    idle_expires_at TIMESTAMP(6) NOT NULL,
    absolute_expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6),
    -- 查本人会话和后续清理使用用户索引；秘密摘要不是公开认证凭据。
    INDEX idx_auth_sessions_user (user_id),
    CONSTRAINT fk_auth_sessions_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
);
