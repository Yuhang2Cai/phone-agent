# 02: 首页视觉重建与搜索入口

**What to build:** 用户进入首页后看到首页 B 的完整视觉主体，并通过搜索入口 A 的搜索框进入搜索页；首页同时提供地址、通知、AI 助手、快捷建议和附近好店。

**Blocked by:** 01: Android UI 基线与登录壳

**Status:** complete

- [x] 首页完全按 `ui-redesign.md` 首页 B 图片重建，包含顶部区域、主视觉、层级、间距、颜色、卡片和底部栏。
- [x] 搜索入口完全按搜索入口 A 图片实现，点击后进入独立搜索页并自动聚焦。
- [x] 首页包含地址栏、通知入口、AI 助手入口、清淡午餐、30 元以内和“再来一单”快捷建议、附近好店。
- [x] 首页不展示独立历史订单卡片；无地址仍可搜索和获得 AI 推荐。
- [x] 实现首页 Initial、Loading、Content、Empty、Error 状态及重试操作。
- [x] 首页组件提供稳定 Resource ID/content description，并通过 ViewModel 测试验证状态转移。
- [x] 提供首页到搜索页的模拟器 Maestro 流程，并附设计图对照证据。

**Maestro:** passed on emulator-5554 with the latest debug APK via `maestro/ticket-02-home-visual.yaml`.

## Implementation notes

- UI 按 Figma 首页 B / 搜索入口 A 逐态实现，未使用 TDD；验证在实现完成后执行。
- 默认首页隐藏顶部搜索框，点击头部搜索图标后展开叠层搜索入口，再进入独立搜索页。
- 首页 AI 卡换用 Figma 导出的圆润五瓣 mascot，并补齐右下角悬浮 AI 球与提示气泡。
- 附近商铺保持纵向排列，补齐右侧餐品氛围图渐隐和绿色距离胶囊。
- `:app:assembleDebug` 通过；ASCII 映射路径下 `com.fandazi.app.feature.home.*` 单元测试通过。
- 最新 APK 重装后，`ticket-02-home-visual.yaml` 在 `emulator-5554` 完整通过；视觉证据位于 `C:\Users\caid0\.maestro\tests\2026-09-15_005526\ticket-02-home-visual\takeScreenshot\`。
