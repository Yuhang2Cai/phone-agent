# 02: 首页视觉重建与搜索入口

**What to build:** 用户进入首页后看到首页 B 的完整视觉主体，并通过搜索入口 A 的搜索框进入搜索页；首页同时提供地址、通知、AI 助手、快捷建议和附近好店。

**Blocked by:** 01: Android UI 基线与登录壳

**Status:** in-review

- [ ] 首页完全按 `ui-redesign.md` 首页 B 图片重建，包含顶部区域、主视觉、层级、间距、颜色、卡片和底部栏。
- [ ] 搜索入口完全按搜索入口 A 图片实现，点击后进入独立搜索页并自动聚焦。
- [ ] 首页包含地址栏、通知入口、AI 助手入口、清淡午餐、30 元以内和“再来一单”快捷建议、附近好店。
- [ ] 首页不展示独立历史订单卡片；无地址仍可搜索和获得 AI 推荐。
- [ ] 实现首页 Initial、Loading、Content、Empty、Error 状态及重试操作。
- [ ] 首页组件提供稳定 Resource ID/content description，并通过 ViewModel 测试验证状态转移。
- [ ] 提供首页到搜索页的模拟器 Maestro 流程，并附设计图对照证据。

**Maestro:** passed on emulator-5554 with the latest debug APK via `maestro/ticket-02-home-visual.yaml`.
