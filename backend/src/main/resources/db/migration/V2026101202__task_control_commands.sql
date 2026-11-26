-- 控制预备栅栏持久化；待确认摘要归属明确，取消后台不能误清前台确认。
ALTER TABLE agent_conversation_tasks ADD COLUMN pending_command_id VARCHAR(100) NULL;
ALTER TABLE agent_sessions ADD COLUMN pending_confirmation_task_id VARCHAR(100) NULL;

-- 命令与原始请求指纹绑定；只记录控制元数据/检查点引用，不复制私有grant或用户正文。
CREATE TABLE agent_task_commands (
    command_id VARCHAR(100) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    epoch VARCHAR(100) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    action VARCHAR(30) NOT NULL,
    target_task_id VARCHAR(100) NOT NULL,
    source_message_id VARCHAR(100) NOT NULL,
    state VARCHAR(30) NOT NULL,
    reconcile_status VARCHAR(30) NOT NULL,
    reason_code VARCHAR(100) NULL,
    focus_version BIGINT NOT NULL,
    state_json TEXT NOT NULL,
    ack_json TEXT NULL,
    dispatch_count INT NOT NULL DEFAULT 0,
    first_dispatched_at TIMESTAMP(6) NULL,
    next_attempt_at TIMESTAMP(6) NULL,
    lease_owner VARCHAR(100) NULL,
    lease_until TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT ux_task_command_request UNIQUE(user_id,session_id,epoch,request_id),
    CONSTRAINT ck_task_command_state CHECK(state IN ('prepared','agent_acknowledged','committed','aborting','aborted','rejected')),
    CONSTRAINT fk_task_command_session FOREIGN KEY(session_id) REFERENCES agent_sessions(session_id)
);
CREATE INDEX idx_task_command_reconcile ON agent_task_commands(state,reconcile_status,next_attempt_at);

-- lease_task_key只在占用执行资格时非空；超时不是释放或重放写入的证据。
-- 无工具短回复可以不绑定task/run，但仍记录来源/指纹/回执，不能取得执行许可。
CREATE TABLE agent_task_turns (
    turn_id VARCHAR(100) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    epoch VARCHAR(100) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    source_message_id VARCHAR(100) NOT NULL,
    conversation_task_id VARCHAR(100) NULL,
    run_id VARCHAR(100) NULL,
    trace_id VARCHAR(100) NOT NULL,
    state VARCHAR(30) NOT NULL,
    may_execute_tools BOOLEAN NOT NULL,
    fence_json TEXT NULL,
    result_json TEXT NULL,
    result_fingerprint VARCHAR(64) NULL,
    lease_task_key VARCHAR(100) NULL,
    lease_until TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    CONSTRAINT ux_task_turn_request UNIQUE(user_id,session_id,epoch,request_id),
    CONSTRAINT ux_task_single_turn_lease UNIQUE(lease_task_key),
    CONSTRAINT fk_task_turn_session FOREIGN KEY(session_id) REFERENCES agent_sessions(session_id),
    CONSTRAINT fk_task_turn_task FOREIGN KEY(conversation_task_id) REFERENCES agent_conversation_tasks(conversation_task_id),
    CONSTRAINT fk_task_turn_run FOREIGN KEY(run_id) REFERENCES agent_task_runs(run_id)
);
CREATE INDEX idx_task_turn_owner ON agent_task_turns(user_id,session_id,epoch,source_message_id);

-- 目录水位与公开事件在同一事务推进；事件永久身份供补读/去重，投递重试不重新应用控制。
CREATE TABLE agent_task_event_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    epoch VARCHAR(100) NOT NULL,
    task_event_id BIGINT NOT NULL,
    event_json TEXT NOT NULL,
    delivery_state VARCHAR(30) NOT NULL,
    lease_owner VARCHAR(100) NULL,
    lease_until TIMESTAMP(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT ux_task_event_watermark UNIQUE(user_id,session_id,epoch,task_event_id),
    CONSTRAINT fk_task_event_session FOREIGN KEY(session_id) REFERENCES agent_sessions(session_id)
);
CREATE INDEX idx_task_event_delivery ON agent_task_event_outbox(delivery_state,lease_until);
