-- 追加工具与凭证栅栏；前两份已验收迁移保持原字节，旧协议行的任务绑定字段为空。
ALTER TABLE confirmation_grants ADD COLUMN conversation_task_id VARCHAR(100) NULL;
ALTER TABLE confirmation_grants ADD COLUMN execution_generation BIGINT NULL;
ALTER TABLE confirmation_grants ADD COLUMN revoked BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_task_grant_binding ON confirmation_grants(user_id,session_id,conversation_task_id,revoked,consumed);
ALTER TABLE agent_sessions ADD COLUMN confirmed_turn_id VARCHAR(100) NULL;
-- 原支付资源独立于已撤销确认摘要保存，恢复时不能让模型任选本人另一订单。
ALTER TABLE agent_conversation_tasks ADD COLUMN pending_payment_order_id VARCHAR(100) NULL;
-- 执行事实绑定原task/epoch/turn；回查可验证原归属，不因当前焦点变化丢失已提交业务事实。
ALTER TABLE agent_tool_executions ADD COLUMN conversation_task_id VARCHAR(100) NULL;
ALTER TABLE agent_tool_executions ADD COLUMN execution_generation BIGINT NULL;
ALTER TABLE agent_tool_executions ADD COLUMN conversation_epoch VARCHAR(100) NULL;
ALTER TABLE agent_tool_executions ADD COLUMN run_id VARCHAR(100) NULL;
ALTER TABLE agent_tool_executions ADD COLUMN turn_id VARCHAR(100) NULL;
CREATE INDEX idx_task_execution_owner ON agent_tool_executions(user_id,session_id,conversation_task_id,conversation_epoch);
