-- 秒杀订单保持券有效期不变，新增独立支付截止和活动定义快照；旧数据不回填5分钟。
ALTER TABLE group_buy_orders ADD COLUMN seckill_activity_id BIGINT NULL;
ALTER TABLE group_buy_orders ADD COLUMN seckill_activity_version BIGINT NULL;
ALTER TABLE group_buy_orders ADD COLUMN payment_expires_at TIMESTAMP(6) NULL;
ALTER TABLE group_buy_orders ADD CONSTRAINT fk_order_seckill_activity FOREIGN KEY (seckill_activity_id) REFERENCES seckill_activities(id);
CREATE INDEX idx_seckill_payment_expiry ON group_buy_orders(status,payment_expires_at,id);
-- 唯一额度行由专属套餐锁保护首次插入；已付款及退款均保留committed计数。
CREATE TABLE seckill_quotas (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    activity_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    reserved_count INTEGER NOT NULL,
    committed_count INTEGER NOT NULL,
    CONSTRAINT uq_seckill_quota UNIQUE(activity_id,user_id),
    CONSTRAINT fk_quota_activity FOREIGN KEY(activity_id) REFERENCES seckill_activities(id),
    CONSTRAINT fk_quota_user FOREIGN KEY(user_id) REFERENCES app_users(id),
    CONSTRAINT ck_quota_counts CHECK(reserved_count>=0 AND committed_count>=0)
);
-- 每个订单最多一条预留账，终态结算时间用于重复扫描及恢复核查。
CREATE TABLE seckill_reservations (
    order_id BIGINT PRIMARY KEY,
    quota_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    settled_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_reservation_order FOREIGN KEY(order_id) REFERENCES group_buy_orders(id),
    CONSTRAINT fk_reservation_quota FOREIGN KEY(quota_id) REFERENCES seckill_quotas(id),
    CONSTRAINT ck_reservation_quantity CHECK(quantity>0),
    CONSTRAINT ck_reservation_status CHECK(status IN ('RESERVED','COMMITTED','RELEASED'))
);
