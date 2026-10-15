-- 轮次必须固化受理时任务协议；目标结果稍后回读时不能退回默认2.2，也不能跟随任务后续升级。
ALTER TABLE agent_task_turns ADD COLUMN protocol_version VARCHAR(10) NOT NULL DEFAULT '2.2';
