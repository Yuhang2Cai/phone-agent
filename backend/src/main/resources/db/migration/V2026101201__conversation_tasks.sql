-- 会话焦点与AG02来源顺序分开；旧协议活动run和授权字段不迁移。
ALTER TABLE agent_sessions ADD COLUMN current_conversation_task_id VARCHAR(100) NULL;
ALTER TABLE agent_sessions ADD COLUMN task_focus_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_sessions ADD COLUMN task_event_sequence BIGINT NOT NULL DEFAULT 0;

-- active_scope_key只在active时非空，数据库唯一键是跨进程并发的最后一道防线。
-- 主流程仍按session→task→run锁序操作，不能依赖进程内Map或覆盖已有焦点。
CREATE TABLE agent_conversation_tasks (
    conversation_task_id VARCHAR(100) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    epoch VARCHAR(100) NOT NULL,
    task_kind VARCHAR(30) NOT NULL,
    intent VARCHAR(50) NOT NULL,
    title VARCHAR(120) NOT NULL,
    lifecycle VARCHAR(30) NOT NULL,
    task_version BIGINT NOT NULL,
    execution_generation BIGINT NOT NULL,
    stop_kind VARCHAR(30) NOT NULL,
    awaiting_field VARCHAR(100) NULL,
    goal_id VARCHAR(100) NULL,
    active_run_id VARCHAR(100) NULL,
    last_run_id VARCHAR(100) NULL,
    active_scope_key VARCHAR(255) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    last_paused_at TIMESTAMP(6) NULL,
    CONSTRAINT ux_task_single_active UNIQUE(active_scope_key),
    CONSTRAINT fk_task_session FOREIGN KEY(session_id) REFERENCES agent_sessions(session_id),
    CONSTRAINT fk_task_user FOREIGN KEY(user_id) REFERENCES app_users(id),
    CONSTRAINT ck_task_state CHECK(lifecycle IN ('active','paused','completed','cancelled','failed')),
    CONSTRAINT ck_task_versions CHECK(task_version >= 1 AND execution_generation >= 1)
);
CREATE INDEX idx_task_directory ON agent_conversation_tasks(user_id,session_id,epoch,lifecycle,last_paused_at);

-- 终态run清空inflight_task_key；同task新run由Spring分配，永不重开终态run。
CREATE TABLE agent_task_runs (
    run_id VARCHAR(100) PRIMARY KEY,
    conversation_task_id VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL,
    inflight_task_key VARCHAR(100) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6) NULL,
    CONSTRAINT ux_task_one_inflight_run UNIQUE(inflight_task_key),
    CONSTRAINT fk_run_conversation_task FOREIGN KEY(conversation_task_id) REFERENCES agent_conversation_tasks(conversation_task_id),
    CONSTRAINT ck_task_run_status CHECK(status IN ('running','waiting_input','waiting_confirmation','succeeded','cancelled','failed'))
);
CREATE INDEX idx_task_run_history ON agent_task_runs(conversation_task_id,created_at);
