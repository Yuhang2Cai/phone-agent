# 04: 地址管理与设置页视觉重建

**What to build:** 用户可以在“我的”中按地址管理 C 图片维护收货地址，并在设置页按自动化策略 A 图片配置安全的 AI 助手策略；没有有效地址或默认地址时，相关自动化操作会被明确阻断。

**Blocked by:** 01: Android UI 基线与登录壳

**Status:** complete

- [x] 地址管理页完全按地址管理 C 图片重建，支持加载、空列表、新增、编辑、删除和设置默认地址。
- [x] 默认地址不能直接删除；删除、编辑和设置默认提交中禁止重复操作。
- [x] 访客进入地址或设置功能时要求登录，登录后返回原操作。
- [x] 设置页完全按设置与自动化策略 A 图片重建，只展示“AI 助手”用户语言，不展示 Agent、模型、API Base URL 或 API Key。
- [x] 区分本次自动下单和长期自动下单；长期模式必须绑定有效默认地址和最高金额。
- [x] 覆盖无地址、失效地址、无默认地址、金额超限和授权阻断状态。
- [x] 提供地址/设置 ViewModel 与 Repository 测试和模拟器 Maestro 流程。

**Maestro:** passed on emulator-5554 with a freshly installed debug APK and H2-backed authenticated data via `maestro/ticket-04-address-settings-visual.yaml`. The flow covers guest login enforcement and return, address create/edit/delete, settings navigation, policy enablement, and visual screenshots. Evidence: `C:\Users\caid0\.maestro\tests\2026-09-15_125754\ticket-04-address-settings-visual\takeScreenshot\`.

**Verification:** `:app:testDebugUnitTest` for profile and Agent ViewModel plus `:app:assembleDebug` passed after implementation. Address mutation is retained in a ViewModel across recreation, protected navigation preserves its pending destination, policy controls are locked while saving, and one-time automation preference is scoped to the signed-in user and consumed by the next AI conversation. No TDD was used for this UI ticket.
