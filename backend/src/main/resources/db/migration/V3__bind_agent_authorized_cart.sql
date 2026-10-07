-- 将助手授权与购物车草稿绑定。
ALTER TABLE agent_sessions ADD COLUMN confirmed_total DECIMAL(12, 2);
