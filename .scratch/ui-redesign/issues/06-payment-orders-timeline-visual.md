# 06: 模拟支付、订单列表与配送时间线视觉重建

**What to build:** 用户从结算创建待支付订单，进入模拟支付；取消/失败可回到原上下文并重试，成功后在订单列表和详情中看到设计稿 C 的中文配送时间线。

**Blocked by:** 01: 设计 Token 与通用组件；05: 菜品规格、购物车与结算视觉重建

**Status:** in-progress

## UI 实现范围

- 按 `mock-payment-c.png` 重建模拟支付页，明确“模拟支付”、金额、订单摘要、确认/取消/失败/成功状态。
- 按 `orders-timeline-c.png` 重建订单 Tab、状态筛选、订单卡片、详情和纵向时间线：商家已接单→制作中→配送中→已送达。

## 业务逻辑复用范围

- 复用现有 PaymentFragment、OrdersFragment、OrderingRepository、订单幂等/支付重试/状态刷新；不修改支付或配送状态机。

## 页面状态

- 订单区分待支付、已支付、商家已接单、制作中、配送中、已送达、失败、取消；支付页覆盖 `Submitting/Success/Error`。

## 导航和返回栈

- 结算→待支付→模拟支付；取消/返回→结算；成功→订单详情；失败→原订单并可重新支付，不创建新订单。

## 进程重建和旋转屏幕恢复

- 恢复待支付订单 ID、支付结果、订单 Tab/滚动位置；已支付订单重启后以后端为事实来源，结束状态停止刷新。

## 设计图对应关系

- `mock-payment-c.png`：支付页。
- `orders-timeline-c.png`：订单列表、状态 Tab 和配送时间线。

## 需要新增或修改的文件

- `feature/order/PaymentFragment.kt`、`OrdersFragment.kt`、订单 ViewModel/Repository 测试。
- `res/layout/fragment_payment.xml`、`fragment_orders.xml`、订单卡片/时间线 item、成功/失败资源。
- `maestro/ticket-06-payment-orders-visual.yaml`。

## 阻塞依赖

- 依赖 01、05；08 最终回归依赖本 Ticket。

## 完成标准

- [ ] 结构、颜色、字体、间距、圆角、按钮和状态图标与设计图一致；不使用默认 Material 替代。
- [ ] 360/393/412dp 无遮挡、截断或底部覆盖；控件树 ID/描述稳定。
- [ ] 订单幂等、支付失败重试、取消返回和时间线测试通过；Maestro 实际运行并提供截图/hierarchy。

## Maestro 验收流程

- 清理两个包 → 安装最新 debug APK → 从结算创建待支付订单 → 取消支付返回 → 再次支付并模拟失败 → 重试成功 → 查看订单详情/时间线 → 截图/hierarchy。

## 模拟器截图验收点

- 412dp 对照 `mock-payment-c.png` 检查支付层级和成功/失败反馈。
- 412dp 对照 `orders-timeline-c.png` 检查四个状态 Tab、订单卡片和纵向时间线。
