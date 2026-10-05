-- 对比必要公开资料采用可空前向迁移；旧数据未知，不解析标题、描述或改写金额/库存/授权。
ALTER TABLE deal_packages ADD COLUMN people_count INT NULL;
ALTER TABLE deal_packages ADD COLUMN contents_json TEXT NULL;
ALTER TABLE deal_packages ADD COLUMN usage_rules VARCHAR(2000) NULL;
