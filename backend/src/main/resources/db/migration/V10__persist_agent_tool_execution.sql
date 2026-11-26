-- 工具执行器动作记录用于跨进程重放；与业务写入在同一事务提交，不能只使用内存缓存。
-- result_json 是内部结果，包含短时凭证时只能由内部桥接读取，公开审计走单独脱敏流程。
CREATE TABLE agent_tool_executions (
    execution_key VARCHAR(255) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    tool_name VARCHAR(80) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    result_json TEXT NOT NULL
);
CREATE INDEX idx_agent_tool_prepare_window ON agent_tool_executions(session_id, created_at);
