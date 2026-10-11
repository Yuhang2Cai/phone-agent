-- TL-06：原始网关输入与业务子句受理同事务绑定，拒绝同请求ID追加另一任务控制或确认对象。
-- 已应用迁移不改写；旧路径无需该摘要，只有显式2.2网关写入。
ALTER TABLE agent_task_turns ADD COLUMN gateway_input_hash VARCHAR(64) NULL;
ALTER TABLE agent_task_turns ADD COLUMN gateway_query TEXT NULL;
