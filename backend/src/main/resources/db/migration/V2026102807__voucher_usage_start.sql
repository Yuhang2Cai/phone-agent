-- 新秒杀券保存使用起日快照，券截止仍沿既有expires_at；不按活动当前配置改写历史券。
-- 历史普通券保持NULL和原语义，不能因此把已存在的普通券升级为新秒杀限制。
ALTER TABLE vouchers ADD COLUMN valid_from DATE NULL;
