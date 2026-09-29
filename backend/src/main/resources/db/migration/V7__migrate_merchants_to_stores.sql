-- 商家兼容迁移：为旧外卖商家补充稳定公开标识，后续团购门店通过 store.merchant_id 关联它。
ALTER TABLE merchants ADD COLUMN public_id VARCHAR(80);
UPDATE merchants SET public_id = CONCAT('merchant-', id) WHERE public_id IS NULL;
CREATE UNIQUE INDEX uk_merchants_public_id ON merchants (public_id);
