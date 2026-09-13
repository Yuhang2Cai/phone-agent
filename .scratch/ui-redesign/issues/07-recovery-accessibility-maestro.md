> **历史版本：** 本 ticket 属于旧 UI 增量实现方案，不再作为当前执行依据。当前任务请使用 `.scratch/ui-redesign-v2/issues/08-recovery-accessibility-maestro-regression.md`。

# 07: 状态恢复、控件树、无障碍与 Maestro 回归

**What to build:** 完整 Android 用户流程在旋转屏幕、进程重建、网络失败和返回操作下保持一致；控件具备稳定的可访问标识；在目标屏幕宽度上通过 Maestro 和 TalkBack 验收。

**Blocked by:** 01: Android UI 壳、登录与访客导航；02: 首页、搜索与商家浏览；03: 地址管理、我的页面与自动化边界；04: 菜品规格、单商家购物车与结算；05: 待支付订单、模拟支付与配送时间线；06: AI 助手与确认下单

**Status:** historical

- [ ] 验证登录输入、搜索关键词、购物车、当前地址、AI 会话和待支付订单的旋转/进程恢复。
- [ ] 验证订单和 AI 会话恢复失败时展示可理解的恢复提示，不误显示为空状态。
- [ ] 验证 AI 面板、规格弹层、支付页和各 Tab 的返回栈。
- [ ] 所有可点击控件、图标、输入框和订单状态具备稳定 Resource ID 或 content description。
- [ ] 在 360dp、393dp、412dp 和放大字体下检查截断、遮挡、键盘和底部固定栏避让。
- [ ] 使用 Maestro hierarchy 检查 Android Accessibility 控件树，并用 TalkBack 验证核心流程。
- [ ] 运行完整普通点餐和 AI 点餐 Maestro 流程，记录失败、截图和最终验收结果。
