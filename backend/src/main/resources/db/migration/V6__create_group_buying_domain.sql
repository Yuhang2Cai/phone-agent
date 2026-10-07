-- 团购领域迁移：新增门店、固定套餐、报价、团购订单、团购券和售后基础表。
-- 所有金额统一使用分，所有对外资源使用 public_id，避免把数据库自增主键暴露给 Agent 和 Android。
CREATE TABLE IF NOT EXISTS stores (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(80) NOT NULL UNIQUE,
    brand_name VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    city_code VARCHAR(32) NOT NULL,
    address VARCHAR(255) NOT NULL,
    phone VARCHAR(64) NOT NULL,
    category VARCHAR(64) NOT NULL,
    latitude DECIMAL(10, 7) NOT NULL,
    longitude DECIMAL(10, 7) NOT NULL,
    average_spend_cents INTEGER NOT NULL,
    open BOOLEAN NOT NULL,
    opening_hours VARCHAR(128) NOT NULL,
    merchant_id BIGINT,
    CONSTRAINT fk_stores_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id)
);

CREATE INDEX idx_stores_city_category ON stores (city_code, category, open);

CREATE TABLE IF NOT EXISTS deal_packages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(80) NOT NULL UNIQUE,
    store_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    price_cents INTEGER NOT NULL,
    original_price_cents INTEGER NOT NULL,
    inventory INTEGER NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE NOT NULL,
    active BOOLEAN NOT NULL,
    purchase_limit INTEGER NOT NULL,
    version INTEGER NOT NULL,
    CONSTRAINT fk_deal_packages_store FOREIGN KEY (store_id) REFERENCES stores(id)
);

CREATE INDEX idx_deal_packages_store_active ON deal_packages (store_id, active, valid_to);

CREATE TABLE IF NOT EXISTS purchase_quotes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(80) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    deal_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL,
    subtotal_cents INTEGER NOT NULL,
    discount_cents INTEGER NOT NULL,
    total_cents INTEGER NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(40) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    CONSTRAINT fk_purchase_quotes_user FOREIGN KEY (user_id) REFERENCES app_users(id),
    CONSTRAINT fk_purchase_quotes_deal FOREIGN KEY (deal_id) REFERENCES deal_packages(id)
);

CREATE TABLE IF NOT EXISTS group_buy_orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(80) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    deal_id BIGINT NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL,
    total_cents INTEGER NOT NULL,
    status VARCHAR(40) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    CONSTRAINT uk_group_buy_orders_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_group_buy_orders_user FOREIGN KEY (user_id) REFERENCES app_users(id),
    CONSTRAINT fk_group_buy_orders_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_group_buy_orders_deal FOREIGN KEY (deal_id) REFERENCES deal_packages(id)
);

CREATE INDEX idx_group_buy_orders_user_status ON group_buy_orders (user_id, status, created_at);

CREATE TABLE IF NOT EXISTS vouchers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(80) NOT NULL UNIQUE,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    deal_id BIGINT NOT NULL,
    status VARCHAR(40) NOT NULL,
    qr_token VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMP(6) NOT NULL,
    redeemed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_vouchers_order FOREIGN KEY (order_id) REFERENCES group_buy_orders(id),
    CONSTRAINT fk_vouchers_user FOREIGN KEY (user_id) REFERENCES app_users(id),
    CONSTRAINT fk_vouchers_deal FOREIGN KEY (deal_id) REFERENCES deal_packages(id)
);

CREATE INDEX idx_vouchers_user_status ON vouchers (user_id, status, expires_at);

CREATE TABLE IF NOT EXISTS refund_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(80) NOT NULL UNIQUE,
    voucher_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(40) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_refund_requests_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers(id),
    CONSTRAINT fk_refund_requests_user FOREIGN KEY (user_id) REFERENCES app_users(id)
);

CREATE TABLE IF NOT EXISTS coupons (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    threshold_cents INTEGER NOT NULL,
    discount_cents INTEGER NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE NOT NULL,
    scope_description VARCHAR(500) NOT NULL,
    active BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS user_coupons (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(80) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    status VARCHAR(40) NOT NULL,
    order_id BIGINT,
    claimed_at TIMESTAMP(6) NOT NULL,
    used_at TIMESTAMP(6),
    CONSTRAINT uk_user_coupons_user_coupon UNIQUE (user_id, coupon_id),
    CONSTRAINT fk_user_coupons_user FOREIGN KEY (user_id) REFERENCES app_users(id),
    CONSTRAINT fk_user_coupons_coupon FOREIGN KEY (coupon_id) REFERENCES coupons(id)
);

CREATE TABLE IF NOT EXISTS store_reviews (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    reviewer_name VARCHAR(80) NOT NULL,
    rating DECIMAL(2, 1) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_store_review_seed UNIQUE (store_id, reviewer_name, created_at),
    CONSTRAINT fk_store_reviews_store FOREIGN KEY (store_id) REFERENCES stores(id)
);

CREATE INDEX idx_store_reviews_page ON store_reviews (store_id, created_at, id);

CREATE TABLE IF NOT EXISTS complaint_tickets (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(80) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    order_id BIGINT,
    voucher_id BIGINT,
    subject VARCHAR(255) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    status VARCHAR(40) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_complaint_tickets_user FOREIGN KEY (user_id) REFERENCES app_users(id),
    CONSTRAINT fk_complaint_tickets_order FOREIGN KEY (order_id) REFERENCES group_buy_orders(id),
    CONSTRAINT fk_complaint_tickets_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers(id)
);

CREATE TABLE IF NOT EXISTS confirmation_grants (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    grant_id VARCHAR(80) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    action VARCHAR(80) NOT NULL,
    resource_id VARCHAR(80) NOT NULL,
    amount_cents INTEGER,
    rule_version VARCHAR(40) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    consumed BOOLEAN NOT NULL,
    CONSTRAINT fk_confirmation_grants_user FOREIGN KEY (user_id) REFERENCES app_users(id)
);

CREATE INDEX idx_confirmation_grants_session ON confirmation_grants (user_id, session_id, consumed);

CREATE TABLE IF NOT EXISTS agent_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(80) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    trace_id VARCHAR(100) NOT NULL,
    run_id VARCHAR(100) NOT NULL,
    role VARCHAR(40) NOT NULL,
    content VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_agent_messages_user FOREIGN KEY (user_id) REFERENCES app_users(id)
);

CREATE INDEX idx_agent_messages_session ON agent_messages (user_id, session_id, created_at);
