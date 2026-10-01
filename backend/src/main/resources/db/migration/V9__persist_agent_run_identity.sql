-- 团购 Agent 的任务标识与暂停状态独立保存，不能覆盖外卖工作流授权字段。
-- 保留历史会话，首次新请求为尚无任务标识的记录分配新的 UUID。
ALTER TABLE agent_sessions ADD COLUMN active_run_id VARCHAR(100);
ALTER TABLE agent_sessions ADD COLUMN active_run_status VARCHAR(30);
