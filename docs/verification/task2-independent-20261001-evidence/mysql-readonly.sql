-- Task2 独立安全证据查询：仅输出匿名计数、金额合计与字段类型，不读取用户正文、凭据或二维码令牌。
-- 主库由用户指定，副本为已经由父侧恢复和升级的隔离库；本脚本不升级、删除或重启服务。
SELECT 'mysql_version', VERSION();
SELECT 'flyway_main', MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;
SELECT 'copy_flyway', MAX(CAST(version AS UNSIGNED)) FROM task2_upgrade_b7b37c05148a.flyway_schema_history WHERE success=1;
-- 保留的非空旧分列限定原始历史订单，避免把后续随机账号的新增订单混入基线。
SELECT 'legacy_main_orders', COUNT(*), SUM(total_cents), SUM(total_amount) FROM group_buy_orders WHERE total_cents IS NOT NULL;
SELECT 'copy_orders', COUNT(*), SUM(total_cents), SUM(total_amount) FROM task2_upgrade_b7b37c05148a.group_buy_orders;
SELECT 'copy_counts',
 (SELECT COUNT(*) FROM task2_upgrade_b7b37c05148a.vouchers),
 (SELECT COUNT(*) FROM task2_upgrade_b7b37c05148a.refund_requests),
 (SELECT COUNT(*) FROM task2_upgrade_b7b37c05148a.stores),
 (SELECT COUNT(*) FROM task2_upgrade_b7b37c05148a.deal_packages),
 (SELECT COUNT(*) FROM task2_upgrade_b7b37c05148a.purchase_quotes),
 (SELECT COUNT(*) FROM task2_upgrade_b7b37c05148a.confirmation_grants);
SELECT 'copy_old_confirm_active', COUNT(*) FROM task2_upgrade_b7b37c05148a.confirmation_grants WHERE consumed=0;
SELECT 'copy_old_quote_active', COUNT(*) FROM task2_upgrade_b7b37c05148a.purchase_quotes WHERE status='ACTIVE';
SELECT 'copy_alias_count', COUNT(*) FROM task2_upgrade_b7b37c05148a.public_id_aliases;
SELECT 'main_order_amount_mismatch', COUNT(*) FROM group_buy_orders WHERE total_cents IS NOT NULL AND total_amount*100<>total_cents;
SELECT 'copy_order_amount_mismatch', COUNT(*) FROM task2_upgrade_b7b37c05148a.group_buy_orders WHERE total_amount*100<>total_cents;
-- 内部数值主键与归属直接和隔离副本对照，两个库分别随机生成的公开 UUID 不需要相同。
SELECT 'order_internal_reference_mismatch', COUNT(*) FROM task2_upgrade_b7b37c05148a.group_buy_orders c LEFT JOIN group_buy_orders m ON m.id=c.id
 WHERE m.id IS NULL OR m.user_id<>c.user_id OR m.store_id<>c.store_id OR m.deal_id<>c.deal_id OR m.quantity<>c.quantity OR m.total_amount<>c.total_amount OR m.status<>c.status OR m.created_at<>c.created_at;
SELECT 'voucher_internal_reference_mismatch', COUNT(*) FROM task2_upgrade_b7b37c05148a.vouchers c LEFT JOIN vouchers m ON m.id=c.id
 WHERE m.id IS NULL OR m.user_id<>c.user_id OR m.order_id<>c.order_id OR m.deal_id<>c.deal_id OR m.status<>c.status OR NOT(m.allocated_amount<=>c.allocated_amount);
SELECT 'refund_internal_reference_mismatch', COUNT(*) FROM task2_upgrade_b7b37c05148a.refund_requests c LEFT JOIN refund_requests m ON m.id=c.id
 WHERE m.id IS NULL OR m.user_id<>c.user_id OR m.voucher_id<>c.voucher_id OR m.amount<>c.amount OR m.status<>c.status;
SELECT 'paid_allocation_mismatch', COUNT(*) FROM group_buy_orders o WHERE o.paid_total_amount IS NOT NULL AND o.paid_total_amount<>(SELECT SUM(v.allocated_amount) FROM vouchers v WHERE v.order_id=o.id);
SELECT 'unpaid_amount_present', COUNT(*) FROM group_buy_orders o JOIN vouchers v ON v.order_id=o.id WHERE o.status='PENDING_PAYMENT' AND (o.paid_total_amount IS NOT NULL OR v.allocated_amount IS NOT NULL);
SELECT 'new_order_decimal_bound', COUNT(*) FROM group_buy_orders WHERE total_cents IS NULL AND total_amount IS NOT NULL;
SELECT 'new_quote_decimal_bound', COUNT(*) FROM purchase_quotes WHERE total_cents IS NULL AND total_amount IS NOT NULL;
SELECT 'schema', table_name,column_name,column_type,is_nullable,datetime_precision FROM information_schema.columns
 WHERE table_schema=DATABASE() AND ((table_name IN ('stores','deal_packages','purchase_quotes','group_buy_orders','vouchers','refund_requests','coupons','confirmation_grants')
 AND column_name IN ('average_spend_amount','price_amount','original_price_amount','subtotal_amount','discount_amount','total_amount','paid_total_amount','allocated_amount','threshold_amount','amount','total_cents','price_cents','amount_cents'))
 OR (table_name IN ('group_buy_orders','vouchers','refund_requests') AND column_name IN ('created_at','paid_at','redeemed_at'))) ORDER BY table_name,column_name;
-- 公开 ID 必须完整第四版随机 UUID；本查询不输出任何实际资源 ID。
SELECT 'uuid_invalid', SUM(public_id NOT REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$') FROM (
 SELECT public_id FROM stores UNION ALL SELECT public_id FROM deal_packages UNION ALL SELECT public_id FROM group_buy_orders UNION ALL SELECT public_id FROM vouchers
 UNION ALL SELECT public_id FROM refund_requests UNION ALL SELECT public_id FROM purchase_quotes UNION ALL SELECT public_id FROM coupons UNION ALL SELECT public_id FROM user_coupons UNION ALL SELECT public_id FROM complaint_tickets) resources;
SELECT 'aliases_invalid', COUNT(*) FROM public_id_aliases WHERE public_id NOT REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$';
