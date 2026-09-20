# 05: 菜品规格、购物车与结算视觉重建

**What to build:** 用户可在规格底部弹层选择规格/辣度/加料，加入单商家购物车，调整数量并进入结算；结算显示真实校验后的地址、费用和总额。

**Blocked by:** 01: 设计 Token 与通用组件；04: 商家菜单视觉重建

**Status:** in-progress

## UI 实现范围

- 按 `dish-options-a.png` 重建底部规格弹层：标题/当前价、胶囊选项、删除线售罄、加料、确认按钮。
- 按 `checkout-c.png` 重建购物车与结算：商品图、规格、数量、小计、配送费、优惠、地址、总价和主操作。
- 单商家切换提示、空购物车、提交中、价格/库存/地址变化错误完整呈现。

## 业务逻辑复用范围

- 复用 OrderingViewModel/Repository、Room 草稿（若已有）、购物车单商家规则、地址读取和后端 `validate`/订单接口；不改后端业务。

## 页面状态

- 弹层 `Content/Disabled/Submitting`；购物车/结算 `Content → Submitting → Content/Error`；缺地址为 Disabled。

## 导航和返回栈

- 关闭弹层保留选择；菜单返回保留购物车；结算返回菜单/购物车；选择地址只影响当前订单，不修改默认地址。

## 进程重建和旋转屏幕恢复

- 恢复弹层选择、购物车、数量、当前地址、备注、费用快照和提交前页面；提交中恢复为可查询状态，避免重复创建。

## 设计图对应关系

- `dish-options-a.png`：规格弹层。
- `checkout-c.png`：购物车、结算信息层级和固定提交区。

## 需要新增或修改的文件

- `feature/merchant/MerchantFragment.kt`、`feature/ordering/OrderingViewModel.kt`、`OrderingRepository.kt`。
- `res/layout/fragment_merchant.xml`、新增规格 BottomSheet/购物车/结算布局、item 和 drawable。
- `maestro/ticket-05-dish-options-cart-checkout.yaml`。

## 阻塞依赖

- 依赖 01、04；06 依赖本 Ticket 的结算提交和待支付订单入口。

## 完成标准

- [ ] 结构、颜色、字体、间距、圆角、按钮和关键图片与设计图一致；不以默认 Material 替代。
- [ ] 360/393/412dp 无截断、遮挡或底部覆盖；控件 ID/描述稳定。
- [ ] 规格、单商家、数量、价格/库存/地址校验和重复提交测试通过；Maestro 实际通过并附截图/hierarchy。

## Maestro 验收流程

- 清理两个包 → 安装最新 debug APK → 进入菜单 → 打开规格 → 选择/取消规格 → 加购并改数量 → 打开购物车 → 进入结算 → 触发一次校验错误并恢复 → 截图/hierarchy。

## 模拟器截图验收点

- 412dp 对照 `dish-options-a.png` 检查弹层层级、选中态和按钮。
- 412/360dp 对照 `checkout-c.png` 检查费用、地址和固定底部操作区。
