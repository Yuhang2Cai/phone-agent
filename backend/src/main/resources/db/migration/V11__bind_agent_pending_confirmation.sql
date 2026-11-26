-- 待确认摘要保存 Spring 校验后的动作、资源、金额、规则和正文；恢复确认不能授权其他动作。
-- 该字段不保存 JWT 或确认凭证，空值表示历史会话需要重新准备确认。
ALTER TABLE agent_sessions ADD COLUMN pending_confirmation TEXT;
