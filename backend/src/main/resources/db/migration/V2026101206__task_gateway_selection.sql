-- TL-06：只持久Spring已核验的本轮公开选择证据；断流重试仍使用首次受理对象，不借新页面更换身份。
-- 不保存客户端自报候选，不复制旧task条件、停点或确认凭据；已应用迁移保持不可变。
ALTER TABLE agent_task_turns ADD COLUMN gateway_context_json TEXT NULL;
