-- AG04目标版本绑定在会话任务上；旧行保持2.2与1/1/0，不因部署自动升级。
ALTER TABLE agent_conversation_tasks ADD COLUMN protocol_version VARCHAR(10) NOT NULL DEFAULT '2.2';
ALTER TABLE agent_conversation_tasks ADD COLUMN goal_revision BIGINT NOT NULL DEFAULT 1;
ALTER TABLE agent_conversation_tasks ADD COLUMN plan_revision BIGINT NOT NULL DEFAULT 1;
ALTER TABLE agent_conversation_tasks ADD COLUMN effect_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_conversation_tasks ADD COLUMN goal_projection_json TEXT NULL;
ALTER TABLE agent_conversation_tasks ADD COLUMN pending_goal_request_id VARCHAR(100) NULL;

-- 每个目标版本保留有限公开结果；完整合同、执行键和工具响应只存Agent私有库。
CREATE TABLE agent_task_goals (
    id VARCHAR(100) PRIMARY KEY,
    conversation_task_id VARCHAR(100) NOT NULL,
    goal_id VARCHAR(100) NOT NULL,
    goal_revision BIGINT NOT NULL,
    plan_revision BIGINT NOT NULL,
    effect_version BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    outcome_json TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT ux_task_goal_revision UNIQUE(conversation_task_id,goal_revision,goal_id),
    CONSTRAINT fk_goal_task FOREIGN KEY(conversation_task_id) REFERENCES agent_conversation_tasks(conversation_task_id),
    CONSTRAINT ck_goal_versions CHECK(goal_revision>=1 AND plan_revision>=1 AND effect_version>=0)
);
CREATE INDEX idx_task_goal_current ON agent_task_goals(conversation_task_id,goal_revision);

-- 修订/恢复请求按完整scope和requestId唯一；同体回原回执，异体冲突。
CREATE TABLE agent_goal_recoveries (
    id VARCHAR(100) PRIMARY KEY,
    request_key VARCHAR(64) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    epoch VARCHAR(100) NOT NULL,
    conversation_task_id VARCHAR(100) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    candidate_hash VARCHAR(64) NOT NULL,
    action VARCHAR(40) NOT NULL,
    expected_goal_revision BIGINT NOT NULL,
    expected_plan_revision BIGINT NOT NULL,
    expected_effect_version BIGINT NOT NULL,
    committed_goal_revision BIGINT NOT NULL,
    committed_plan_revision BIGINT NOT NULL,
    committed_effect_version BIGINT NOT NULL,
    state VARCHAR(30) NOT NULL,
    receipt_json TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT ux_goal_recovery_request UNIQUE(request_key),
    CONSTRAINT fk_goal_recovery_task FOREIGN KEY(conversation_task_id) REFERENCES agent_conversation_tasks(conversation_task_id)
);
CREATE INDEX idx_goal_recovery_task ON agent_goal_recoveries(conversation_task_id,created_at);

-- 真实业务效果与幂等键独立留痕；保存效果与effectVersion递增必须同一事务。
CREATE TABLE agent_goal_effects (
    id VARCHAR(100) PRIMARY KEY,
    effect_key VARCHAR(255) NOT NULL,
    conversation_task_id VARCHAR(100) NOT NULL,
    goal_revision BIGINT NOT NULL,
    plan_revision BIGINT NOT NULL,
    effect_version BIGINT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    receipt_json TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT ux_goal_effect_key UNIQUE(conversation_task_id,effect_key),
    CONSTRAINT fk_goal_effect_task FOREIGN KEY(conversation_task_id) REFERENCES agent_conversation_tasks(conversation_task_id)
);
