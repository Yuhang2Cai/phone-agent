-- 目录型只读工具可能返回超过 64 KiB 的完整结果；执行记录用于跨进程幂等重放，不能截断。
-- 仅扩大列容量，不改写历史结果、执行键、归属字段或索引。
ALTER TABLE agent_tool_executions MODIFY COLUMN result_json LONGTEXT NOT NULL;
