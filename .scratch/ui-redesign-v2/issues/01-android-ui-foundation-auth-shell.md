# 01: Android UI 基线与登录壳

**What to build:** 用户打开 Android 应用后看到与确认设计图一致的登录/注册页面；可以登录或跳过登录进入统一的四项底部导航，并在旋转或进程重建后恢复认证输入和当前路由。

**Blocked by:** None (can start immediately)

**Status:** complete

**Maestro:** passed on `emulator-5554` with the latest debug APK.

- [x] 直接依据 `ui-redesign.md` 已确认图片建立 Android XML UI 基线：颜色、字体、间距、圆角、按钮、卡片、输入框和图标；HTML 与旧 XML 不作为视觉基准。
- [x] 按登录与注册设计图重建页面，用户文案全部为中文且不暴露 Agent、模型、API Base URL 或 API Key。
- [x] 支持登录、注册、访客入口；访客可进入首页、商家、订单、我的四项导航。
- [x] 认证页隐藏底部导航和 AI 入口；业务页显示统一底部导航。
- [x] 登录草稿、当前路由和访客状态支持旋转与进程重建恢复。
- [x] 提供 ViewModel/路由快速测试和可在模拟器运行的登录/访客 Maestro 流程。
- [x] 完成设计图逐页对照记录后，才可进入下一个 ticket。

## Comments

- 2026-09-15：按 Figma `32:94` 重构认证视觉，并从 Figma `48:2` 导入圆润五瓣吉祥物矢量路径；纯 UI 修改未使用 TDD。
- `:app:assembleDebug` 通过；认证与路由测试通过（通过 ASCII 盘符映射规避 AGP 中文路径类加载问题）。
- 最新 APK 已在清空后的 `Medium_Phone_API_36.1` 安装，`maestro/ticket-01-auth-and-tabs.yaml` 完整通过。
- 全量 Android 单元测试另有 4 个既有网络测试因 JVM 环境调用 `android.util.Log` 失败，不属于本工单 UI 改动。
