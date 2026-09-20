# 08: AI 助手半屏/全屏面板视觉重建与完整回归

**What to build:** 用户可在首页、搜索、商家和订单页打开设计稿 A 的 AI 助手半屏/全屏面板，完成文字或录音输入、执行进度、候选订单修改/确认，并通过全链路回归验收。

**Blocked by:** 01: 设计 Token 与通用组件；02: 首页视觉重建；03: 搜索页与商家浏览视觉重建；04: 商家菜单视觉重建；05: 菜品规格、购物车与结算视觉重建；06: 模拟支付、订单列表与配送时间线视觉重建；07: 地址管理、我的页面与自动化设置视觉重建

**Status:** in-progress

## UI 实现范围

- 按 `ai-panel-a.png` 重建浮球、半屏 BottomSheet、上拉全屏、拖拽条、对话气泡、快捷建议、麦克风/输入/发送栏、波形/识别中状态、自然语言执行进度和确认订单卡片。
- 仅展示“AI 助手”及“正在理解你的需求/正在寻找附近好店/正在查看菜品/正在准备订单”等用户文案；候选卡展示商家、图片、菜品规格数量、地址、配送费、优惠、总价、修改/确认。
- 确认后复用普通“待支付订单→模拟支付→订单详情”链路；不绕过后端授权。

## 业务逻辑复用范围

- 复用 AgentSheetFragment、AgentViewModel/Repository、SSE 会话、ASR 结果回填、普通结算/支付流程和现有授权/错误处理；不改 Agent 或后端接口。

## 页面状态

- `Hidden → Half → Full → Recording → Recognizing → Sending → Executing → AwaitingConfirmation → CreatingOrder → OrderCreated/Error`。
- 覆盖无地址、价格/库存变化、会话过期、取消、重试和自动化阻断。

## 导航和返回栈

- 全屏返回先收起半屏，半屏返回关闭并保留会话；修改回面板，确认进入普通结算/支付；登录/支付页面隐藏浮球。

## 进程重建和旋转屏幕恢复

- 恢复面板层级、会话、事件、输入文本、录音/识别结果和候选订单；SSE 重连从最后事件继续，不重复写操作；无法恢复显示会话过期并可重新开始。

## 设计图对应关系

- `ai-panel-a.png`：面板、输入区、对话和执行状态。
- `checkout-c.png`：候选订单/费用信息的业务视觉参照。
- `mock-payment-c.png`、`orders-timeline-c.png`：确认后普通链路不可分叉。

## 需要新增或修改的文件

- `feature/agent/AgentSheetFragment.kt`、`AgentViewModel.kt`、`AgentRepository.kt`、入口策略及测试。
- `res/layout/fragment_agent_sheet.xml`、`item_agent_event.xml`、`item_order_confirmation.xml`、相关 drawable/strings。
- `maestro/ticket-08-ai-assistant-regression.yaml`，并更新 Maestro README 的全量执行清单。

## 阻塞依赖

- 依赖 01–07；本 Ticket 是完整 UI 回归的收口，不得在单独生成 YAML 后标记完成。

## 完成标准

- [ ] 结构、颜色、字体、间距、圆角、按钮形态、关键图片、卡片、底部栏和输入区与设计图一致；不使用默认 Material 替代。
- [ ] 360/393/412dp 无截断、遮挡、键盘覆盖或底部覆盖；所有控件有稳定 Resource ID/content description。
- [ ] 文字/ASR、半屏/全屏、进度、确认/修改/取消、会话恢复和普通支付链路测试通过。
- [ ] 每次 Maestro 运行先卸载 `com.fandazi.app`、卸载 `com.dirror.music`、安装最新 debug APK，再实际执行完整普通点餐和 AI 点餐路径。
- [ ] 同时检查截图和 hierarchy/控件树；提供 360/393/412dp 模拟器截图作为视觉证据。只有功能和视觉均通过才可标记完成。

## Maestro 验收流程

- 清理两个包 → 安装最新 debug APK → 登录/进入首页 → 从首页打开助手 → 输入主场景“我感冒了，中午清淡一点，不要香菜，30 元以内，按默认地址帮我点” → 检查执行进度 → 打开/收起全屏 → 修改候选订单 → 确认 → 待支付 → 模拟支付 → 订单时间线 → 旋转/杀进程恢复 → 重跑普通流程 → 每个关键节点截图和 hierarchy。

## 模拟器截图验收点

- 412dp 对照 `ai-panel-a.png` 检查半屏、全屏、对话气泡、输入区和候选确认卡。
- 360/393dp 检查键盘弹出、波形、确认按钮、浮球与底部导航的避让。
- 最终提交普通点餐、AI 点餐、支付成功/失败、恢复和错误状态截图证据。
