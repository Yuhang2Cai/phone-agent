# 07: 地址管理、我的页面与自动化设置视觉重建

**What to build:** 用户可以在“我的”中按地址管理 C 维护地址、查看账户信息，并按自动化设置 A 配置本次/长期自动下单；风险条件被清晰阻断。

**Blocked by:** 01: 设计 Token 与通用组件

**Status:** in-progress

## UI 实现范围

- 按 `address-management-c.png` 重建主题横幅、可单选地址卡片、默认标签、左滑编辑/删除和固定“添加新地址”。
- 重建我的页面入口、账户/偏好信息和 `automation-settings-a.png` 的本次自动下单、长期自动下单、金额上限、默认地址及说明文案。
- 不展示 Agent、模型、API Base URL 或 API Key；加入无地址、失效地址、无默认地址、金额超限、授权过期阻断态。

## 业务逻辑复用范围

- 复用 ProfileFragment、地址/自动化 Repository、默认地址唯一性、访客登录拦截和后端授权校验；不修改后端策略。

## 页面状态

- 地址 `Initial → Loading → Content/Empty/Error`，新增/编辑/删除/设默认 `Submitting`，失效地址 `Disabled`。
- 自动化 `关闭 → 开启配置中 → 已开启/被阻断`。

## 导航和返回栈

- 我的→地址/设置；地址返回我的；访客操作先登录并返回原操作；结算选地址不改长期默认地址。

## 进程重建和旋转屏幕恢复

- 恢复地址表单、选中地址、我的滚动位置、开关和金额草稿；提交状态以服务端重新读取为准。

## 设计图对应关系

- `address-management-c.png`：地址列表与操作。
- `automation-settings-a.png`：自动化策略设置。

## 需要新增或修改的文件

- `feature/profile/ProfileFragment.kt`、新增地址/设置 Fragment、对应 ViewModel/Repository 测试。
- `res/layout/fragment_profile.xml`、地址列表/编辑/设置布局、资源。
- `maestro/ticket-07-address-profile-automation.yaml`。

## 阻塞依赖

- 仅依赖 01；05/08 可引用本 Ticket 的稳定地址选择和阻断控件。

## 完成标准

- [ ] 结构、颜色、字体、间距、圆角、按钮、卡片和输入区与设计图一致；不使用默认 Material 替代。
- [ ] 360/393/412dp 无截断、遮挡或底部覆盖；控件 ID/描述稳定。
- [ ] 地址归属/默认唯一/不能删默认、策略阻断测试通过；按统一规则实际运行 Maestro，并附截图/hierarchy。

## Maestro 验收流程

- 清理两个包 → 安装最新 debug APK → 登录 → 我的 → 地址新增/编辑/设默认/删除非默认 → 自动化开关 → 缺默认地址/超额提示 → 截图/hierarchy。

## 模拟器截图验收点

- 412dp 对照 `address-management-c.png` 检查横幅、地址卡片、默认态和底部按钮。
- 412/360dp 对照 `automation-settings-a.png` 检查开关、金额输入和阻断说明。
