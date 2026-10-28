-- 活动规则仅由受控服务端配置；本迁移只建结构，不发布示例任务或虚构用户余额。
CREATE TABLE activity_definitions (
    definition_id VARCHAR(36) NOT NULL,
    rule_version INTEGER NOT NULL,
    title VARCHAR(100) NOT NULL,
    task_type VARCHAR(40) NOT NULL,
    target_count INTEGER NOT NULL,
    day_count INTEGER NOT NULL,
    reward_coins INTEGER NOT NULL,
    campaign_id VARCHAR(36) NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    enabled BOOLEAN NOT NULL,
    PRIMARY KEY (definition_id, rule_version),
    CONSTRAINT ck_activity_definition CHECK (rule_version > 0 AND target_count > 0 AND day_count > 0
        AND day_count <= 31 AND reward_coins > 0 AND ends_at > starts_at
        AND (task_type = 'CONSECUTIVE_ORDER_DAYS' OR (task_type = 'DAILY_ORDER_COUNT' AND day_count = 1)))
);

-- 账户行同时作为同用户活动/退款的互斥入口；余额只能伴随账本在同一事务内变化。
CREATE TABLE coin_accounts (
    user_id BIGINT PRIMARY KEY,
    balance INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_coin_account_user FOREIGN KEY (user_id) REFERENCES app_users(id),
    CONSTRAINT ck_coin_balance CHECK (balance >= 0)
);

-- 规则字段在领取时完整复制；后续定义调价或停用不会改变旧实例的窗口及奖励。
CREATE TABLE activity_instances (
    instance_id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    definition_id VARCHAR(36) NOT NULL,
    rule_version INTEGER NOT NULL,
    cycle_key VARCHAR(40) NOT NULL,
    title VARCHAR(100) NOT NULL,
    task_type VARCHAR(40) NOT NULL,
    target_count INTEGER NOT NULL,
    day_count INTEGER NOT NULL,
    reward_coins INTEGER NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    window_end DATETIME(6) NOT NULL,
    claim_deadline DATETIME(6) NOT NULL,
    reward_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
    CONSTRAINT uk_activity_cycle UNIQUE (user_id, definition_id, cycle_key),
    CONSTRAINT uk_activity_owner UNIQUE (instance_id, user_id),
    CONSTRAINT fk_activity_owner FOREIGN KEY (user_id) REFERENCES app_users(id),
    CONSTRAINT fk_activity_rule FOREIGN KEY (definition_id, rule_version) REFERENCES activity_definitions(definition_id, rule_version),
    CONSTRAINT ck_activity_reward_state CHECK (reward_status IN ('NONE','CLAIMED','REVERSED'))
);
CREATE INDEX idx_activity_owner_window ON activity_instances(user_id, window_end);
CREATE INDEX idx_activity_order_paid ON group_buy_orders(user_id, paid_at, id);

-- 每实例只允许一个奖励凭据，冲正保留原凭据和原流水，禁止删除历史或二次发奖。
CREATE TABLE activity_reward_grants (
    receipt_id VARCHAR(36) PRIMARY KEY,
    instance_id VARCHAR(36) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    amount INTEGER NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_activity_grant_owner UNIQUE(receipt_id,user_id),
    CONSTRAINT fk_activity_grant_instance FOREIGN KEY (instance_id,user_id) REFERENCES activity_instances(instance_id,user_id),
    CONSTRAINT ck_activity_grant_amount CHECK (amount > 0)
);
CREATE TABLE activity_reward_orders (
    receipt_id VARCHAR(36) NOT NULL,
    order_id BIGINT NOT NULL,
    paid_at DATETIME(6) NOT NULL,
    PRIMARY KEY (receipt_id,order_id),
    CONSTRAINT fk_activity_proof_grant FOREIGN KEY (receipt_id) REFERENCES activity_reward_grants(receipt_id),
    CONSTRAINT fk_activity_proof_order FOREIGN KEY (order_id) REFERENCES group_buy_orders(id)
);
CREATE TABLE coin_transactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_id VARCHAR(36) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    receipt_id VARCHAR(36) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    amount INTEGER NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_coin_transaction_business UNIQUE(receipt_id,kind),
    CONSTRAINT fk_coin_transaction_grant FOREIGN KEY (receipt_id,user_id) REFERENCES activity_reward_grants(receipt_id,user_id),
    CONSTRAINT ck_coin_transaction_sign CHECK ((kind='REWARD' AND amount>0) OR (kind='REVERSAL' AND amount<0))
);
CREATE INDEX idx_coin_transaction_page ON coin_transactions(user_id,id);

-- 请求键按用户全局唯一，绑定动作、对象及预期版本；同键不同内容必须冲突。
CREATE TABLE activity_requests (
    user_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    action VARCHAR(20) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    expected_version INTEGER NOT NULL,
    instance_id VARCHAR(36) NOT NULL,
    PRIMARY KEY(user_id,request_id),
    CONSTRAINT fk_activity_request_instance FOREIGN KEY(instance_id,user_id) REFERENCES activity_instances(instance_id,user_id)
);
