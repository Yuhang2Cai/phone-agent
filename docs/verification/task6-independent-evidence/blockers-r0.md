# Task6 独立验收首次阻塞

发现时间：2026-10-02 08:23:31，独立验收 Agent 亲跑冻结最终源。

## B1：金额测试仍依赖可被全量测试清空的共享门店

- 位置：`backend/src/test/java/com/fandazi/feature/purchase/PurchaseConfirmationTest.java:38`。
- 操作：`pwsh -NoProfile -File docs/verification/task6-independent-evidence/Invoke-IndependentChecks.ps1 -Mode Backend`，内部在新建无 runtime/target/链接的同源快照执行 `mvn -q test`。
- 实际结果：exit=1；217 项，0 failures、1 error、0 skipped。
- 失败：`displayedDiscountReconcilesWithPayableAmount`，`stores.findAll().get(0)` 抛出 `IndexOutOfBoundsException: Index 0 out of bounds for length 0`。
- 原始失败证据：独立目录 `Backend-full-xml/TEST-com.fandazi.feature.purchase.PurchaseConfirmationTest.xml`，汇总 `Backend-full-summary.json`；完整日志仅忽略 `.runtime/task6-independent/backend-full.log`。
- 冻结测试源 SHA256：`CD5D9B40A0516313DC9C1B0AF50E02F97128A1F4737CF2F5C9AEF8560E117760`；快照复制逐文件核验。
- 请求：由实现者修复测试隔离，重新冻结变更；验收者不修改产品或产品测试断言。必须亲跑同源全量复验，不能以定向 3 项通过替代。

此时其他独立 API、Android/Maestro、Figma 验收仍在运行；整体未通过。
