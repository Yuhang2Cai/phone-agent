-- 建立助手网关的会话、事件和授权表。
CREATE TABLE IF NOT EXISTS agent_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL UNIQUE,
    mode VARCHAR(255) NOT NULL,
    trace_id VARCHAR(100) NOT NULL,
    confirmed BOOLEAN NOT NULL,
    confirmation_workflow_id VARCHAR(100),
    confirmed_cart_draft_id BIGINT,
    confirmed_address_id BIGINT,
    confirmation_consumed BOOLEAN NOT NULL,
    active_workflow_id VARCHAR(100),
    created_order_id BIGINT,
    created_order_workflow_id VARCHAR(100),
    created_order_settled BOOLEAN NOT NULL,
    CONSTRAINT fk_agent_sessions_user FOREIGN KEY (user_id) REFERENCES app_users(id)
);

CREATE TABLE IF NOT EXISTS agent_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    type VARCHAR(40) NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    tool_name VARCHAR(100),
    status VARCHAR(40) NOT NULL,
    payload VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_agent_events_user FOREIGN KEY (user_id) REFERENCES app_users(id)
);

CREATE TABLE IF NOT EXISTS automation_policies (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL,
    default_address_id BIGINT NOT NULL,
    max_amount DECIMAL(12, 2) NOT NULL,
    CONSTRAINT fk_automation_policies_user FOREIGN KEY (user_id) REFERENCES app_users(id)
);

CREATE TABLE IF NOT EXISTS automation_grants (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    address_id BIGINT NOT NULL,
    max_amount DECIMAL(12, 2) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    workflow_id VARCHAR(100),
    cart_draft_id BIGINT,
    order_id BIGINT,
    consumed BOOLEAN NOT NULL,
    CONSTRAINT uk_automation_grants_user_session UNIQUE (user_id, session_id),
    CONSTRAINT fk_automation_grants_user FOREIGN KEY (user_id) REFERENCES app_users(id)
);
