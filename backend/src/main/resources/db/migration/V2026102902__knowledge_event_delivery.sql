-- 原交付身份留在内部事件列，不改变公开事件或工具协议；旧记录不猜测、回填来源。
ALTER TABLE agent_events ADD COLUMN knowledge_delivery_ref VARCHAR(255) NULL;
-- 每次重连按原始交付集合核对来源，避免扫描全部许可或把后续动作误套到历史答案。
CREATE INDEX knowledge_permit_delivery ON knowledge_delivery_permits(delivery_ref);
