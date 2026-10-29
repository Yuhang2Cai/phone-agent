-- 超时关闭是独立持久事实，不能仅由查询时刻临时投影；普通订单保持原状态及空关闭字段。
ALTER TABLE group_buy_orders ADD COLUMN closed_at TIMESTAMP(6) NULL;
ALTER TABLE group_buy_orders ADD COLUMN close_reason VARCHAR(40) NULL;
