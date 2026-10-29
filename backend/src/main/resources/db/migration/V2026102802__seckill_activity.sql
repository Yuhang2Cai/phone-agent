-- 秒杀活动迁移：专属套餐继续保存唯一可写库存；活动只保存定义及初始库存。
-- 时间使用UTC精确到微秒，购买窗口在服务端按包含开始、不包含结束判断。
CREATE TABLE seckill_activities (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(80) NOT NULL UNIQUE,
    deal_id BIGINT NOT NULL UNIQUE,
    starts_at TIMESTAMP(6) NOT NULL,
    ends_at TIMESTAMP(6) NOT NULL,
    total_stock INTEGER NOT NULL,
    per_user_limit INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    activity_version BIGINT NOT NULL,
    CONSTRAINT fk_seckill_activity_deal FOREIGN KEY (deal_id) REFERENCES deal_packages(id),
    CONSTRAINT ck_seckill_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_seckill_stock CHECK (total_stock BETWEEN 1 AND 100000),
    CONSTRAINT ck_seckill_limit CHECK (per_user_limit BETWEEN 1 AND 99 AND per_user_limit <= total_stock),
    CONSTRAINT ck_seckill_status CHECK (status IN ('PUBLISHED','STOPPED'))
);
-- 列表按时间窗口读取，不能以库存0删除活动，否则超时释放后无法恢复可见性。
CREATE INDEX idx_seckill_window ON seckill_activities(status, starts_at, ends_at);
