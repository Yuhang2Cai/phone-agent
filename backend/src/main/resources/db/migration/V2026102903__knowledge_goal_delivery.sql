-- 知识目标修订只使用工具事务核验过的围栏；旧记录不回填猜测的目标身份。
ALTER TABLE agent_tool_executions ADD COLUMN knowledge_goal_fence LONGTEXT NULL;
-- 保存原交付确切来源数，区分已验证的纯业务交付与意外缺失的许可，内部字段不进入公开协议。
ALTER TABLE agent_events ADD COLUMN knowledge_delivery_scope_count INT NULL;
-- 保存已校验门店查询是否缺位置，仅供最终固定提示核对；旧空值不能当作无定位证明。
ALTER TABLE agent_tool_executions ADD COLUMN knowledge_location_absent BOOLEAN NULL;
