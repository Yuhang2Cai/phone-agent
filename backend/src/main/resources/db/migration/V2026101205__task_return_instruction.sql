-- TL-06：明确返回指令按原插入命令持久保存，支持有界恢复；没有原文指令的任务不自动返回。
ALTER TABLE agent_task_commands ADD COLUMN return_status VARCHAR(30) NULL;
ALTER TABLE agent_task_commands ADD COLUMN return_command_id VARCHAR(100) NULL;
CREATE INDEX ix_task_return_status ON agent_task_commands(return_status, state);
