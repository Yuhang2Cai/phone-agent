# 03: 搜索页与商家浏览视觉重建

**What to build:** 用户可以按搜索页 A 图片搜索商家或菜品，看到可理解的结果状态，并进入按商家菜单 A 图片呈现的商家详情和菜品列表。

**Blocked by:** 02: 首页视觉重建与搜索入口

**Status:** complete

- [x] 搜索页完全按 `ui-redesign.md` 搜索页 A 图片重建，不沿用旧 XML 布局或 HTML 原型。
- [x] 支持关键词、筛选、自动聚焦、搜索中、结果、空结果、网络错误和需要登录状态。
- [x] 搜索结果卡片展示设计图要求的图片、评分、月售、配送时间、标签、价格区间和“去下单”入口。
- [x] 点击结果只进入商家菜单，不直接创建购物车或订单。
- [x] 商家页完全按商家菜单 A 图片重建，包含商家封面、评分、配送信息、左侧分类、右侧菜品列表和底部购物车入口。
- [x] 菜品展示图片、名称、描述、价格和加购入口，图片失败显示设计稿规定的占位状态。
- [x] Repository 使用真实或 Fake API，覆盖成功、空结果、鉴权失败和网络失败测试。
- [x] 提供搜索到商家菜单的模拟器 Maestro 流程，并附设计图对照证据。

**Maestro:** passed on emulator-5554 with the latest debug APK and the H2 seed backend via `maestro/ticket-03-search-visual.yaml`. Evidence: `C:\Users\caid0\.maestro\tests\2026-09-15_120127\ticket-03-search-visual\takeScreenshot\`.

**Verification:** search and merchant presentation tests passed after implementation. The broader `OrderingRepositoryTest` is separately blocked by the pre-existing `android.util.Log` JVM stub in `ApiClient`, not by this UI flow. No TDD was used for this UI ticket.
