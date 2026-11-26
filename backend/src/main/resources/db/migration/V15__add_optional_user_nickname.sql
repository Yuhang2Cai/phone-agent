-- 注册页可选昵称由业务后端持久化；仅追加可空列，已有账号保持NULL，不回填或修改历史身份。
ALTER TABLE app_users ADD COLUMN nickname VARCHAR(40) NULL;
