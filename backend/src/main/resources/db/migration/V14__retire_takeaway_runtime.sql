-- 实际最高版本为V13 JDBC迁移，追加V14，不修改已执行SQL/JDBC校验和。
-- 仅登记能力退役审计；旧事实表不DROP、不UPDATE，运行时以默认组件/导航边界为准。
CREATE TABLE runtime_capability_retirement (
    capability VARCHAR(64) PRIMARY KEY,
    active BOOLEAN NOT NULL,
    reason VARCHAR(255) NOT NULL,
    retired_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO runtime_capability_retirement (capability, active, reason) VALUES
 ('takeaway_address', FALSE, '团购到店不使用配送地址'),
 ('takeaway_cart_order', FALSE, '改用报价和团购订单确认'),
 ('takeaway_delivery', FALSE, '团购到店不提供外卖配送'),
 ('takeaway_auto_purchase', FALSE, '写入仅使用新服务明确确认'),
 ('legacy_agent_8090', FALSE, '旧Agent仅保留源码，不参与启动或故障降级');
