-- 团购评价绑定订单；历史无订单种子评价保留 NULL，MySQL 唯一索引允许多个 NULL。
ALTER TABLE store_reviews ADD COLUMN order_id BIGINT NULL;
ALTER TABLE store_reviews ADD CONSTRAINT uk_store_reviews_order UNIQUE (order_id);
ALTER TABLE store_reviews ADD CONSTRAINT fk_store_reviews_order FOREIGN KEY (order_id) REFERENCES group_buy_orders(id);
