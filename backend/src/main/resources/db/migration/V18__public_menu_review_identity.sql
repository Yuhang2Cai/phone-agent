-- 只补齐公开浏览稳定身份，不修改旧迁移、自增主键、业务事实或读取顺序。
-- 先为历史行逐条生成UUID，再收紧非空及唯一约束；应用新建记录由实体构造时固定UUID。
ALTER TABLE dishes ADD COLUMN public_id VARCHAR(36);
UPDATE dishes SET public_id = UUID() WHERE public_id IS NULL;
ALTER TABLE dishes MODIFY COLUMN public_id VARCHAR(36) NOT NULL;
CREATE UNIQUE INDEX uk_dishes_public_id ON dishes(public_id);
ALTER TABLE store_reviews ADD COLUMN public_id VARCHAR(36);
UPDATE store_reviews SET public_id = UUID() WHERE public_id IS NULL;
ALTER TABLE store_reviews MODIFY COLUMN public_id VARCHAR(36) NOT NULL;
CREATE UNIQUE INDEX uk_store_reviews_public_id ON store_reviews(public_id);
