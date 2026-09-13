# 08: 状态恢复、控件树、无障碍与完整回归

**What to build:** 完整 Android 普通点餐和 AI 点餐流程在旋转、进程重建、网络失败和返回操作下保持一致，并通过控件树、TalkBack、尺寸适配和全套 Maestro 验收。

**Blocked by:** 01、02、03、04、05、06、07

**Status:** in-review

- [ ] 验证登录输入、搜索关键词、购物车、当前地址、AI 会话和待支付订单的旋转/进程恢复。
- [ ] 恢复失败时显示可理解的提示和重新开始入口，不误显示为空状态。
- [ ] 验证 AI 面板、规格弹层、支付页和各 Tab 的返回栈。
- [ ] 所有按钮、图标、输入框、图片和订单状态具备稳定 Resource ID 或 content description。
- [ ] 在 360dp、393dp、412dp 和放大字体下验证无截断、遮挡、键盘覆盖或底部栏覆盖。
- [ ] 使用 Maestro hierarchy 检查 Android Accessibility 控件树，并用 TalkBack 验证核心流程。
- [ ] 运行全部普通点餐和 AI 点餐 Maestro 流程，记录截图、失败原因和最终验收结果。
- [ ] 只有完整回归通过后，才能将 UI 重构规格标记为完成。

**Maestro:** smoke flow passed on emulator-5554 with the latest debug APK via `maestro/ticket-08-recovery-smoke.yaml`. Detailed session logs confirmed guest persistence and `AUTH → HOME` navigation. Full recovery, hierarchy and TalkBack acceptance remains open and the UI specification is intentionally not marked complete.
