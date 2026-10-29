-- 幂等回执与订单/库存同事务写入；以用户和原请求唯一约束处理并发、重启及响应丢失。
CREATE TABLE seckill_purchase_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    request_key VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    order_id BIGINT NULL UNIQUE,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_seckill_request UNIQUE(user_id,request_key),
    CONSTRAINT fk_seckill_request_user FOREIGN KEY(user_id) REFERENCES app_users(id),
    CONSTRAINT fk_seckill_request_order FOREIGN KEY(order_id) REFERENCES group_buy_orders(id)
);
