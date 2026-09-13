# 01: Android UI 基线与登录壳

**What to build:** 用户打开 Android 应用后看到与确认设计图一致的登录/注册页面；可以登录或跳过登录进入统一的四项底部导航，并在旋转或进程重建后恢复认证输入和当前路由。

**Blocked by:** None (can start immediately)

**Status:** in-review

**Maestro:** passed on `emulator-5554` with the latest debug APK.

- [ ] 直接依据 `ui-redesign.md` 已确认图片建立 Android XML UI 基线：颜色、字体、间距、圆角、按钮、卡片、输入框和图标；HTML 与旧 XML 不作为视觉基准。
- [ ] 按登录与注册设计图重建页面，用户文案全部为中文且不暴露 Agent、模型、API Base URL 或 API Key。
- [ ] 支持登录、注册、访客入口；访客可进入首页、商家、订单、我的四项导航。
- [ ] 认证页隐藏底部导航和 AI 入口；业务页显示统一底部导航。
- [ ] 登录草稿、当前路由和访客状态支持旋转与进程重建恢复。
- [ ] 提供 ViewModel/路由快速测试和可在模拟器运行的登录/访客 Maestro 流程。
- [ ] 完成设计图逐页对照记录后，才可进入下一个 ticket。
