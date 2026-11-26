# 团购业务后端

Spring是用户、门店/菜品、套餐、报价、团购订单、模拟支付、团购券、退款申请、评价、投诉和FAQ的权威来源，并代理新Agent与长期记忆管理。

普通启动使用MySQL、Flyway追加迁移和Hibernate validate；种子默认关闭。配置DB_URL/DB_USERNAME/DB_PASSWORD/JWT_SECRET/AGENT_INTERNAL_TOKEN，不在源码写凭据。AGENT_SERVICE_URL默认http://127.0.0.1:8091，失败不回8090。

根目录scripts/Start-Services.ps1 -Service Spring为本机入口；可移植环境执行mvn -f backend/pom.xml spring-boot:run。工具只开放短时JWT保护的/internal/v1/tools，旧路径拒绝。

mvn -f backend/pom.xml test执行本期有效套件。原外卖/旧Agent契约测试保留但退出默认CI，NoTakeawayRuntimeDependencyTest防止回流；CouponComplaintToolTest已迁到V1实际执行器，公开事件安全测试仍执行。历史实体/表供审计保留，不代表旧交易启用。

V1–V13不修改；V12/V13是Java迁移，不能只数SQL。V14只登记停用状态，不DROP或更新用户事实。备份/恢复证据保存在忽略.runtime；回滚镜像不能替代恢复数据。
