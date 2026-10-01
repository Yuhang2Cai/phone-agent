# Task2 数据开发进度与自测记录

状态：本轮源代码修改与 Maven 已结束，Task2 定向开发自测通过；待真实 MySQL 副本验证与独立验收。未提交，未勾选验收或任务完成。Task8/UI 不在本次范围。

## 范围与环境

- 工作区：`C:/Users/caid0/Documents/ChatGPT/手机agent/.worktrees/fandazi-ai`，`C:/fdz-build` 为同一 junction。
- 起始 HEAD：`f4902b8707cd128a7c726768fd02faf4f1931d58`。已有未提交改动保留，特别是 Task1/3 与其他模块。
- 写集：手机 backend/src/main、backend/src/test、data/seed、必要开发夹具脚本及本报告；不改 contracts、主 plan/spec、Android、D 项目、V1–V11。
- Maven 本轮由本实现 Agent 独占，最后进程于 2026-10-01 23:28:31 +08:00 结束；本报告收尾后释放，不再运行 Maven。`JAVA_HOME=D:/software/IntelliJ IDEA 2025.2.4/jbr`；Maven：`D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd`；`MAVEN_OPTS=-Dfile.encoding=UTF-8`。
- 父侧已备份真实业务库到忽略文件 `.runtime/backups/task2-before-20261001.sql`，报告字节数 462264。实现阶段仅运行 H2；真实 8080/8091 服务不重启，不应用真实迁移。

## 已确认接口字段

- 套餐、订单、券与退款摘要：`imageUrl`、`imageAlt`，均可为 null；无图不挪用其他套餐餐图。
- 订单：原有 `orderId/storeId/dealId/storeName/dealTitle/quantity/totalCents/status/vouchers/canReview/createdAt`，`createdAt` 输出带 Z 的 UTC ISO-8601。
- 券：保留 `publicId`，增加 `amountCents`（未支付为 null）、`createdAt`、可空图片字段；`expiresAt/redeemedAt` 为 UTC 字符串或合法 null。退款资格来自后端。
- 退款 eligibility/preparation/detail：`voucherId/orderId/storeId/dealId/storeName/dealTitle/amountCents/expiresAt/imageUrl/imageAlt`；资格接口包含 `canRefund`；detail 另有 `requestId/reason/createdAt/status`。
- 退款真实待处理值继续为 `REQUESTED`。spec 6.2 的 `PENDING` 为文档差异，父侧修订，当前实现不更改状态枚举。
- 不新增 `paidAmountCents/paidAt` 对外字段；支付快照只在数据库内部保存，避免 strict schema 出现未经确认字段。
- 权威 contracts 可选字段由父侧扩充并同步 D，本 Agent 不改契约文件。

## 设计决策

1. 金额：明确 JPA `Integer <-> BigDecimal` 转换器，保留 Java 整数分 API；权威列为 DECIMAL 元，迁移精确转换旧整数分并让现有字段实际读写新列。旧整数分列保留历史原值并改为可空；新记录不写 legacy 列，不能用 legacy 值作为新记录权威。Task2 不 DROP 旧列，清理由 Task11 与恢复演练单独处理。
2. 支付与退款：保存订单支付金额/时间，券按固定顺序分配商和余数，合计严格等于实付；未支付券金额 null。退款申请保存单券金额快照，套餐涨价不影响历史退款。
3. 时间：已有 LocalDateTime 按已确认 Asia/Shanghai 业务语义解释，创建时也显式使用该时区，对外统一转换 Instant UTC；不直接补 Z。
4. UUID：新增随机 UUID 与 legacy 别名的持久化映射；内部数值外键保持不变。别名仅用于迁移/种子内部引用，公开 API 使用 UUID，不提供可枚举目录别名入口。
5. 夹具：七种状态使用隔离账号，仅明确 development 配置启用；随机账号/密码只写忽略 `.runtime/`，数据库只存密码哈希。核销事实在开发夹具中准备，界面仍延期。
6. 迁移：追加 V12+，兼容 MySQL8/H2，测试必须覆盖从 V11 的已有数据升级及引用/金额不丢失。真实迁移待父侧通知与后续验证。

## 测试登记

计划四类实际均存在：

- `com.fandazi.feature.grouporder.GroupOrderSummaryContractTest`
- `com.fandazi.seed.GroupBuyingSeedDataTest`
- `com.fandazi.feature.refund.RefundEligibilityTest`
- `com.fandazi.feature.review.ReviewEligibilityTest`

实际命令、RED/GREEN、文件与结果将在每轮运行后追加；不以预期结果代替实测结果。

### 首轮 RED

命令：设置上述 JAVA_HOME/MAVEN_OPTS 后，`mvn.cmd -f backend/pom.xml -Dtest=GroupOrderSummaryContractTest,GroupBuyingSeedDataTest,RefundEligibilityTest,ReviewEligibilityTest test`。

实际：13 项，3 failures、1 error，退出码 1。新增回归分别发现：图片/UTC/金额字段缺失；10001 分三券返回 3333/3333/3333（丢 2 分）；过期/取消订单仍可退款；`store-001` 非 UUID。既有 9 项通过。日志 `.runtime/task2-red.log`；原始 XML 在 backend/target/surefire-reports（后续运行会更新）。

### 迁移 RED 与第一轮 GREEN

- 升级回归 RED：`-Dtest=GroupBuyingMigrationTest test`，1 项 error，缺少 `TOTAL_AMOUNT` 列，退出码 1，日志 `.runtime/task2-migration-red.log`。
- 第一轮 GREEN：`-Dtest=GroupOrderSummaryContractTest,GroupBuyingSeedDataTest,RefundEligibilityTest,ReviewEligibilityTest,GroupBuyingMigrationTest test`，14/14，退出码 0，日志 `.runtime/task2-green-first.log`。这是开发自测，不是独立验收。保留 legacy 列调整及夹具扩展仍须复测。
- 开发夹具 RED：`-Dtest=Task2DevelopmentFixtureTest test`，1 failure，尚无开发夹具 Bean，退出码 1，日志 `.runtime/task2-fixtures-red.log`。
- 扩展 GREEN：四计划类 + `GroupBuyingMigrationTest,Task2DevelopmentFixtureTest,CentsDecimalConverterTest`，17/17，退出码 0，日志 `.runtime/task2-green-expanded.log`。已包括保留 legacy 金额列、七隔离账号、H2 `ddl-auto=validate` 夹具与严格金额转换。

### 夹具与后续检查

- 新增 `Task2DevelopmentFixtures`、`application-development.yml` 与 `data/seed/task2-development-fixtures.json`；明确 development + enabled + seed 三条件，prod/production 禁止，无 HTTP 造状态入口。
- 随机账号和密码只写 `.runtime/task2-fixtures.json`（测试用 `.runtime/task2-test-fixtures.json`），不在源码和日志打印；数据库只有哈希。每次开启开发启动创建新七账号，不清理历史数据。
- 七场景 H2 实测：各自独立 owner、待支付券 amountCents=null、可退款三券合计 10001、已申请 REQUESTED/10001、可评价有核销事实、已评价资格关闭、无候选有过期券且缺图。
- 接下来增加公开旧 ID 拒绝、真实图片 HTTP 路径、迁移 JSON/quote/pending 引用、新记录不写 legacy 列、生产禁用与相关业务回归。真实服务尚未重启，迁移尚未应用。
- 图片实测：PowerShell `Invoke-WebRequest` GET 套餐图 URL，HTTP 200、Content-Type=image/jpeg、41914 字节，退出码 0；仅输出状态/类型/字节数，未保存图片或敏感数据。
- 收尾 API/迁移轮：25 项中 24 通过、1 error，`StoreDealApiTest.legacyAliasesAreNotPublicCatalogRoutes` 的真实 UUID 套餐详情在 Controller 构造 DTO 时触发门店 LazyInitializationException。根因是只读服务返回实体后事务关闭；新增事务内 `DealPackageService.detail` 构造 DTO，原失败用例继续复验。日志 `.runtime/task2-green-contract-migration.log`。
- 父侧已恢复隔离真实 MySQL 副本 `task2_upgrade_b7b37c05148a`，同 127.0.0.1:3306；基线 orders=25、total_cents 合计=248200、vouchers=29、refunds=6、Flyway=11。未应用 V12/13，后续由父侧在 Maven 释放后先验证副本，再升级主开发库。
- 修复后相关回归：四计划类和迁移/夹具/精度/目录 API，加订单幂等、购买确认、券生命周期、券令牌、团购 schema、退款/投诉工具、Task1/3 网关与契约类，45/45，退出码 0，日志 `.runtime/task2-green-regression.log`。
- 全量后端命令 `mvn.cmd -f backend/pom.xml test` 实际 194 项、1 failure、5 errors，退出码 1，日志 `.runtime/task2-backend-full-regression.log`。其中 Task2 引入问题：`SeedDataLoaderTest.seedMetadataTablesAreCreatedByMigration` 在标准 H2（非 MySQL mode）不支持 MODIFY COLUMN；已按数据库产品分别执行标准 ALTER COLUMN 或 MySQL MODIFY，须定向复验。
- 另外 5 项：`AgentCardEnrichmentTest.unknownCardTypeIsRejectedWithoutFallbackData` 期望“不支持”，现有未修改补全器先校验缺少资源并返回“卡片缺少资源或动作”；`Task1NestedPublicEventAcceptanceTest` 的 liveSsePublishesOnlySanitizedNestedPayload、historicalUnfilteredRowIsSanitizedByActualSseReplay、nestedSecretsAreRemovedBeforePersistenceWithoutChangingBusinessValues、legalNullErrorSurvivesRealDatabaseRoundTrip 在全量上下文中因共享 H2 app_users/agent_events 表缺失及异常会话后续 flush 失败。后者将单类复跑核对，不修改 Task1 验收测试或补全器、不删除断言。完整后端不能宣称全绿。

### 收尾兼容修复与授权测试隔离

- V12 DDL 修复：`Task2MigrationSupport.moneyColumn` 按 JDBC 产品区分 H2 的 `ALTER COLUMN ... SET DATA TYPE` / `DROP NOT NULL` 和 MySQL 的 `MODIFY COLUMN ... NULL`；仅调整列类型与空值约束，不删除 legacy 金额列。
- 修复后定向命令：`mvn.cmd -f backend/pom.xml -Dtest=GroupOrderSummaryContractTest,GroupBuyingSeedDataTest,RefundEligibilityTest,ReviewEligibilityTest,GroupBuyingMigrationTest,Task2DevelopmentFixtureTest,CentsDecimalConverterTest,BusinessTimeTest,Task2FixturesProductionGuardTest,Task2FixturesDisabledTest,StoreDealApiTest,GroupBuyOrderIdempotencyTest,PurchaseConfirmationTest,VoucherLifecycleTest,VoucherTokenSecurityTest,GroupBuyingSchemaTest,CouponComplaintToolTest,AgentGatewayV1ContractTest,AgentContractSchemaTest,SeedDataLoaderTest test`。
- 实测 65/65，0 failures、0 errors，退出码 0（2026-10-01 23:21 +08:00），日志 `.runtime/task2-final-targeted.log`。包含标准 H2 种子元数据迁移用例、H2 MySQL mode V11→V13 历史引用与金额保留、迁移重跑、七夹具及门禁。
- `Task1NestedPublicEventAcceptanceTest` 未改隔离配置前单类命令 `-Dtest=Task1NestedPublicEventAcceptanceTest test`：4/4、退出码 0，日志 `.runtime/task2-task1-nested-isolated-before.log`；与全量 4 errors 对比支持共享数据库生命周期干扰的根因。
- 父侧本轮明确授权修该测试的隔离配置：新增本类稳定、每次运行随机的 H2 地址与 `DynamicPropertySource`，只改测试连接地址和中文说明，不删断言、不改生产事件逻辑。此前“不修改 Task1 测试”的范围记录由这条新授权覆盖；隔离后单类与全量结果待追加。
- 隔离后单类同一命令：4/4、退出码 0（23:26 +08:00），日志 `.runtime/task2-task1-nested-isolated-after.log`；全部既有断言保留。
- 卡片单类命令 `-Dtest=AgentCardEnrichmentTest test`：3 项中 2 PASS、1 failure、0 errors，退出码 1，日志 `.runtime/task2-card-baseline-single.log`。失败仍为 unknown 类型缺少资源时错误文案优先级；`git diff --name-only` 核对补全器及该测试均无改动，属于本轮未改基线差异，不以放宽断言或改变生产校验顺序掩盖。
- 写集 `git diff --check` 退出码 0；仅 Git 提示 LF/CRLF 策略，无空白错误。V1–V11 迁移目录 `git diff --name-only` 输出为空。

## 最终开发自测与交接

- 最后一轮全量命令：同上述 JDK/Maven/UTF-8 环境执行 `mvn.cmd -f backend/pom.xml test`；实测 194 项、193 PASS、1 failure、0 errors、0 skipped，退出码 1，耗时 1:16（23:28:31 +08:00）。日志 `.runtime/task2-backend-full-after-isolation.log`。不能宣称完整后端全绿。
- 唯一剩余失败为未修改基线 `AgentCardEnrichmentTest.unknownCardTypeIsRejectedWithoutFallbackData`；先缺少资源还是先未知类型的错误文案优先级留待父侧决定，本次不改其生产逻辑或断言。
- 全量内四计划类：`GroupOrderSummaryContractTest` 3/3、`GroupBuyingSeedDataTest` 3/3、`RefundEligibilityTest` 7/7、`ReviewEligibilityTest` 4/4，共 17/17。`SeedDataLoaderTest` 17/17，`Task1NestedPublicEventAcceptanceTest` 4/4；此前 5 errors 均消失。Task2 扩展定向为 65/65。
- V11→V13 H2 升级保留内部主键/外键/归属、旧金额列与历史原值，新旧历史金额相等；10001 分券分摊 3334/3334/3333，无余分丢失，退款支付快照 DB 往返及套餐改价回归通过。旧 quote/grants/pending 失效与历史结构化 JSON 引用迁移、别名稳定重跑均已自测。真实 MySQL8 尚未实测，不能以 H2 代替真实证据。
- 七开发夹具、随机凭据忽略文件、prod/production 拒绝与开关关闭回归均通过。H2 测试生成的 `.runtime/task2-test-fixtures.json` 不是线上账号；原开发库明确启用后才会生成真实 `.runtime/task2-fixtures.json`。建议父侧显式设绝对 `TASK2_FIXTURES_OUTPUT=C:/fdz-build/.runtime/task2-fixtures.json`，避免启动工作目录改变输出位置。凭据不得打印到日志或提交。
- 交接顺序由父侧执行：隔离副本 `task2_upgrade_b7b37c05148a` 先 `SEED_ENABLED=false`、`TASK2_FIXTURES_ENABLED=false`、port 8082 应用 V12/V13，核对 orders=25、旧 total_cents 合计=248200、vouchers=29、refunds=6、legacy 列仍在与内部引用/归属未丢；然后再升级原 8080 开发库并明确开启 development 夹具。本实现 Agent 未重启真实服务、未应用任何真实迁移、未清库。
- 未新增对外 `paidAmountCents/paidAt`；字段名采用上方已确认契约，未支付券 `amountCents=null`，图片允许 null，时间 UTC Z，退款状态继续 `REQUESTED`。spec 的 PENDING 差异由父侧修。
- 本轮源代码与 Maven 工作已结束并释放独占；后续真实 MySQL、副本恢复/升级证据、D 在线只读七夹具及独立验收由父侧接手。开发自测不替代独立验收。

## 本轮文件清单

以下为本 Agent 实际写过的 45 个文件（28 个 main、13 个 test、3 个种子/说明、1 个报告）。部分文件基于工作区已有修改继续叠加，本清单不代表全部 dirty 文件的归属；未列出的 Android、D、contracts 和其他 Task1/3 源码不属于本次写集。完整工作区目录为 `C:/Users/caid0/Documents/ChatGPT/手机agent/.worktrees/fandazi-ai`；下列绝对链接使用其同一 junction。

- [backend/src/main/java/com/fandazi/core/identity/PublicIdAlias.java](C:/fdz-build/backend/src/main/java/com/fandazi/core/identity/PublicIdAlias.java)
- [backend/src/main/java/com/fandazi/core/identity/PublicIdAliasRepository.java](C:/fdz-build/backend/src/main/java/com/fandazi/core/identity/PublicIdAliasRepository.java)
- [backend/src/main/java/com/fandazi/core/identity/PublicIdAliases.java](C:/fdz-build/backend/src/main/java/com/fandazi/core/identity/PublicIdAliases.java)
- [backend/src/main/java/com/fandazi/core/money/CentsDecimalConverter.java](C:/fdz-build/backend/src/main/java/com/fandazi/core/money/CentsDecimalConverter.java)
- [backend/src/main/java/com/fandazi/core/time/BusinessTime.java](C:/fdz-build/backend/src/main/java/com/fandazi/core/time/BusinessTime.java)
- [backend/src/main/java/com/fandazi/feature/agent/AgentDomainProjection.java](C:/fdz-build/backend/src/main/java/com/fandazi/feature/agent/AgentDomainProjection.java)
- [backend/src/main/java/com/fandazi/feature/agent/ConfirmationGrantEntity.java](C:/fdz-build/backend/src/main/java/com/fandazi/feature/agent/ConfirmationGrantEntity.java)
- [backend/src/main/java/com/fandazi/feature/catalog/StoreEntity.java](C:/fdz-build/backend/src/main/java/com/fandazi/feature/catalog/StoreEntity.java)
- [backend/src/main/java/com/fandazi/feature/catalog/DealPackageEntity.java](C:/fdz-build/backend/src/main/java/com/fandazi/feature/catalog/DealPackageEntity.java)
- [backend/src/main/java/com/fandazi/feature/catalog/DealPackageResponse.java](C:/fdz-build/backend/src/main/java/com/fandazi/feature/catalog/DealPackageResponse.java)
- [backend/src/main/java/com/fandazi/feature/catalog/DealPackageService.java](C:/fdz-build/backend/src/main/java/com/fandazi/feature/catalog/DealPackageService.java)
- [backend/src/main/java/com/fandazi/feature/catalog/DealPackageController.java](C:/fdz-build/backend/src/main/java/com/fandazi/feature/catalog/DealPackageController.java)
- [backend/src/main/java/com/fandazi/feature/coupon/CouponEntity.java](C:/fdz-build/backend/src/main/java/com/fandazi/feature/coupon/CouponEntity.java)
- [backend/src/main/java/com/fandazi/feature/purchase/PurchaseQuote.java](C:/fdz-build/backend/src/main/java/com/fandazi/feature/purchase/PurchaseQuote.java)
- [backend/src/main/java/com/fandazi/feature/grouporder/GroupBuyOrderEntity.java](C:/fdz-build/backend/src/main/java/com/fandazi/feature/grouporder/GroupBuyOrderEntity.java)
- [backend/src/main/java/com/fandazi/feature/grouporder/GroupBuyOrderService.java](C:/fdz-build/backend/src/main/java/com/fandazi/feature/grouporder/GroupBuyOrderService.java)
- [backend/src/main/java/com/fandazi/feature/voucher/VoucherEntity.java](C:/fdz-build/backend/src/main/java/com/fandazi/feature/voucher/VoucherEntity.java)
- [backend/src/main/java/com/fandazi/feature/voucher/VoucherService.java](C:/fdz-build/backend/src/main/java/com/fandazi/feature/voucher/VoucherService.java)
- [backend/src/main/java/com/fandazi/feature/voucher/VoucherSummary.java](C:/fdz-build/backend/src/main/java/com/fandazi/feature/voucher/VoucherSummary.java)
- [backend/src/main/java/com/fandazi/feature/refund/RefundRequestEntity.java](C:/fdz-build/backend/src/main/java/com/fandazi/feature/refund/RefundRequestEntity.java)
- [backend/src/main/java/com/fandazi/feature/refund/RefundService.java](C:/fdz-build/backend/src/main/java/com/fandazi/feature/refund/RefundService.java)
- [backend/src/main/java/com/fandazi/feature/review/ReviewService.java](C:/fdz-build/backend/src/main/java/com/fandazi/feature/review/ReviewService.java)
- [backend/src/main/java/com/fandazi/seed/SeedDataLoader.java](C:/fdz-build/backend/src/main/java/com/fandazi/seed/SeedDataLoader.java)
- [backend/src/main/java/com/fandazi/seed/Task2DevelopmentFixtures.java](C:/fdz-build/backend/src/main/java/com/fandazi/seed/Task2DevelopmentFixtures.java)
- [backend/src/main/java/db/migration/Task2MigrationSupport.java](C:/fdz-build/backend/src/main/java/db/migration/Task2MigrationSupport.java)
- [backend/src/main/java/db/migration/V12__decimal_payment_snapshots.java](C:/fdz-build/backend/src/main/java/db/migration/V12__decimal_payment_snapshots.java)
- [backend/src/main/java/db/migration/V13__random_public_ids_and_legacy_aliases.java](C:/fdz-build/backend/src/main/java/db/migration/V13__random_public_ids_and_legacy_aliases.java)
- [backend/src/main/resources/application-development.yml](C:/fdz-build/backend/src/main/resources/application-development.yml)
- [backend/src/test/java/com/fandazi/core/money/CentsDecimalConverterTest.java](C:/fdz-build/backend/src/test/java/com/fandazi/core/money/CentsDecimalConverterTest.java)
- [backend/src/test/java/com/fandazi/core/time/BusinessTimeTest.java](C:/fdz-build/backend/src/test/java/com/fandazi/core/time/BusinessTimeTest.java)
- [backend/src/test/java/com/fandazi/feature/agent/Task1NestedPublicEventAcceptanceTest.java](C:/fdz-build/backend/src/test/java/com/fandazi/feature/agent/Task1NestedPublicEventAcceptanceTest.java)
- [backend/src/test/java/com/fandazi/feature/catalog/StoreDealApiTest.java](C:/fdz-build/backend/src/test/java/com/fandazi/feature/catalog/StoreDealApiTest.java)
- [backend/src/test/java/com/fandazi/feature/grouporder/GroupOrderSummaryContractTest.java](C:/fdz-build/backend/src/test/java/com/fandazi/feature/grouporder/GroupOrderSummaryContractTest.java)
- [backend/src/test/java/com/fandazi/feature/grouporder/GroupBuyingMigrationTest.java](C:/fdz-build/backend/src/test/java/com/fandazi/feature/grouporder/GroupBuyingMigrationTest.java)
- [backend/src/test/java/com/fandazi/feature/refund/RefundEligibilityTest.java](C:/fdz-build/backend/src/test/java/com/fandazi/feature/refund/RefundEligibilityTest.java)
- [backend/src/test/java/com/fandazi/feature/review/ReviewEligibilityTest.java](C:/fdz-build/backend/src/test/java/com/fandazi/feature/review/ReviewEligibilityTest.java)
- [backend/src/test/java/com/fandazi/feature/support/CouponComplaintToolTest.java](C:/fdz-build/backend/src/test/java/com/fandazi/feature/support/CouponComplaintToolTest.java)
- [backend/src/test/java/com/fandazi/seed/GroupBuyingSeedDataTest.java](C:/fdz-build/backend/src/test/java/com/fandazi/seed/GroupBuyingSeedDataTest.java)
- [backend/src/test/java/com/fandazi/seed/Task2DevelopmentFixtureTest.java](C:/fdz-build/backend/src/test/java/com/fandazi/seed/Task2DevelopmentFixtureTest.java)
- [backend/src/test/java/com/fandazi/seed/Task2FixturesDisabledTest.java](C:/fdz-build/backend/src/test/java/com/fandazi/seed/Task2FixturesDisabledTest.java)
- [backend/src/test/java/com/fandazi/seed/Task2FixturesProductionGuardTest.java](C:/fdz-build/backend/src/test/java/com/fandazi/seed/Task2FixturesProductionGuardTest.java)
- [data/seed/deals.json](C:/fdz-build/data/seed/deals.json)
- [data/seed/task2-development-fixtures.json](C:/fdz-build/data/seed/task2-development-fixtures.json)
- [data/seed/README.md](C:/fdz-build/data/seed/README.md)
- [docs/verification/2026-10-01-task2-data-development.md](C:/fdz-build/docs/verification/2026-10-01-task2-data-development.md)

### 历史引用与真实库前置检查

- V13 不改内部数值外键；公开 UUID 使用随机第四版并先写入稳定别名表。公开 API 不解析旧别名。
- 订单与 quote 指纹只换套餐编号首段，保留版本/数量/金额；旧 `ACTIVE` quote 改为 `INVALIDATED`，旧确认全部 consumed，暂停/运行任务取消并清空 pending confirmation，旧动作须重新报价/准备并获得明确确认。
- 当前 V11 没有 `pending_resource_id`，若真实库有扩展列则兼容转换。历史 tool result/event/message JSON 只转换明确资源键，不替换用户正文。原请求 fingerprint 和审计行保留，避免旧幂等键静默授权新载荷。
- 历史支付金额可由已支付订单状态和旧总额证明，因此回填金额；实际付款时间没有历史事实，`paid_at` 保留 null，不伪造下单时间为支付时间。
- 父侧只读真实 MySQL 计数：orders=25、vouchers=29、refunds=6；invalid_quantity_or_vouchers=0、refund_without_paid_fact=0、duplicate_refund_groups=0。当前 preflight 未发现已知真实历史阻塞；未应用迁移。
- 未知负数、数量/券数不一致、无支付事实的申请或重复申请：保留所有历史行，迁移明确失败，按定位证据修复后重试，不清历史。最大迁移版本需用数值比较，不能用版本字符串 MAX。
