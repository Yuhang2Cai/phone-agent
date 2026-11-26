-- V16已在隔离真实库实际应用，新增故障持久标记必须继续追加版本，不能修改已应用的校验和。
-- 只保存恢复缺口归属和首次时间，不保存正文、凭证或内部异常；重启不能把缺失历史误报完整。
CREATE TABLE agent_history_faults (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT ux_agent_history_fault_owner UNIQUE(user_id,session_id),
    CONSTRAINT fk_agent_history_fault_owner FOREIGN KEY(user_id) REFERENCES app_users(id)
);
