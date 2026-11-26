-- 长会话恢复追加迁移：保留全部旧记录和旧Flyway校验和，不截断正文以适配历史列长度。
-- 每条语句只修改一个字段，使MySQL生产库与H2的MySQL兼容测试模式执行相同迁移。
ALTER TABLE agent_messages MODIFY COLUMN content LONGTEXT NOT NULL;
ALTER TABLE agent_messages MODIFY COLUMN trace_id VARCHAR(100) NULL;
ALTER TABLE agent_messages MODIFY COLUMN run_id VARCHAR(100) NULL;
ALTER TABLE agent_messages ADD COLUMN client_message_key VARCHAR(100) NULL;
ALTER TABLE agent_messages ADD COLUMN record_key VARCHAR(64) NULL;
-- 旧记录没有唯一写入键，NULL允许历史共存；新输入trace/角色的固定摘要防止重复终态增加消息。
CREATE UNIQUE INDEX ux_agent_message_record ON agent_messages(record_key);
CREATE INDEX idx_agent_message_snapshot ON agent_messages(user_id,session_id,id);
-- 终态摘要实际承载完整助手回复，公开事件可能含长正文或大卡片；同样保留完整脱敏载荷。
ALTER TABLE agent_events MODIFY COLUMN summary LONGTEXT NOT NULL;
ALTER TABLE agent_events MODIFY COLUMN payload LONGTEXT NOT NULL;
CREATE INDEX idx_agent_event_snapshot ON agent_events(user_id,session_id,id);
