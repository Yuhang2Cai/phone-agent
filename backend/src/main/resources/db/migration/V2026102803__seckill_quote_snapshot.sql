-- 仅新增秒杀报价身份/版本快照；既有普通报价保持NULL，避免追溯改变已确认业务含义。
ALTER TABLE purchase_quotes ADD COLUMN seckill_activity_id BIGINT NULL;
ALTER TABLE purchase_quotes ADD COLUMN seckill_activity_version BIGINT NULL;
ALTER TABLE purchase_quotes ADD CONSTRAINT fk_quote_seckill_activity
    FOREIGN KEY (seckill_activity_id) REFERENCES seckill_activities(id);
