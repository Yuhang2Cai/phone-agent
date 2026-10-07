# 08: 状态恢复、控件树、无障碍与完整回归

**What to build:** 完整 Android 普通点餐和 AI 点餐流程在旋转、进程重建、网络失败和返回操作下保持一致，并通过控件树、TalkBack、尺寸适配和全套 Maestro 验收。

**Blocked by:** 01、02、03、04、05、06、07

**Status:** in-review

- [x] 验证登录输入、搜索关键词、购物车、当前地址、AI 会话和待支付订单的旋转/进程恢复。
- [x] 恢复失败时显示可理解的提示和重新开始入口，不误显示为空状态。
- [x] 验证 AI 面板、规格弹层、支付页和各 Tab 的返回栈。
- [x] 所有按钮、图标、输入框、图片和订单状态具备稳定 Resource ID 或 content description。
- [x] 在 360dp、393dp、412dp 和放大字体下验证无截断、遮挡、键盘覆盖或底部栏覆盖。
- [x] 使用 Maestro hierarchy 检查 Android Accessibility 控件树，并用 TalkBack 验证核心流程。
- [x] 运行全部普通点餐和 AI 点餐 Maestro 流程，记录截图、失败原因和最终验收结果。
- [x] 只有完整回归通过后，才能将 UI 重构规格标记为完成。

**验收证据（2026-09-18，emulator-5554）：**

- 按项目约束重新卸载 `com.fandazi.app`、安装最新 debug APK，并确认干扰包 `com.dirror.music` 不存在后执行验收。
- Maestro 01–08 全部通过；恢复专项记录位于 `C:\Users\caid0\.maestro\tests\2026-09-18_013003`，最终搜索/商铺复验位于 `C:\Users\caid0\.maestro\tests\2026-09-18_014437`。
- 360dp、393dp、412dp 记录分别位于 `2026-09-18_013216`、`2026-09-18_013259`、`2026-09-18_013342`；1.3 倍字体修复后记录位于 `2026-09-18_013621`。
- TalkBack 已实际启用并以键盘完成登录跳过、主页/我的切换及打开 AI 助手；控件树和截图保存在 `.scratch/ticket08-talkback-*`。验证后已恢复 TalkBack、字体、分辨率和密度系统设置。
- Android 单元测试与 debug 构建成功；Agent Service `146 passed`；Backend `87 passed`；布局静态扫描未发现缺少无障碍说明的图片控件；`git diff --check` 通过。
- UI 实现采用实现后验证流程，未使用 TDD。待最终只读代码审查通过后将状态改为 `complete`。
