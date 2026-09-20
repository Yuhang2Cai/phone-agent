# 04: 商家菜单视觉重建

**What to build:** 用户进入商家后看到封面、商家信息、分类导航和左图右文菜品列表，能够从菜品入口进入规格弹层和购物车；菜单滚动不被固定购物车栏遮挡。

**Blocked by:** 01: 设计 Token 与通用组件；03: 搜索页与商家浏览视觉重建

**Status:** in-progress

## UI 实现范围

- 重建商家封面、评分/配送信息、分类栏、88dp 菜品图片、名称/描述/价格/加购层级、售罄状态和底部购物车入口。
- 预留底部 88dp；固定栏不遮挡最后一行；图片按 1:1、12dp 圆角和失败占位展示。

## 业务逻辑复用范围

- 复用现有 MerchantFragment、OrderingViewModel/Repository、菜单 API、单商家购物车规则和登录拦截；不改价格/库存接口。

## 页面状态

- `Initial → Loading → Content/Empty/Error`；菜品售罄/下架为 Disabled，网络错误可重试。

## 导航和返回栈

- 搜索结果/商家 Tab 进入菜单；返回保留分类、滚动和购物车；点击菜品进入规格弹层，点击购物车进入结算入口。

## 进程重建和旋转屏幕恢复

- 恢复商家 ID、分类、滚动位置、购物车草稿和弹层关闭前的选择；恢复期间避免丢失已加购内容。

## 设计图对应关系

- `merchant-menu-a.png`：封面、分类、菜品列表和购物车栏。

## 需要新增或修改的文件

- `feature/merchant/MerchantFragment.kt`、`feature/ordering/OrderingViewModel.kt`。
- `res/layout/fragment_merchant.xml`、菜品/分类/购物车 item、图片占位资源。
- `maestro/ticket-04-menu-visual.yaml`。

## 阻塞依赖

- 依赖 01、03；05 依赖本 Ticket 的菜品和购物车入口。

## 完成标准

- [ ] 结构、颜色、字体、间距、圆角和按钮与设计图一致；不用默认 Material 替代；关键图片和固定购物车栏完整。
- [ ] 360/393/412dp 无遮挡、截断或底部覆盖；控件 ID/描述稳定。
- [ ] 菜品加载/空/错/售罄状态有测试；Maestro 按规则重装后实际通过，附截图和控件树。

## Maestro 验收流程

- 清理两个包 → 安装最新 debug APK → 进入商家 → 滚动到末尾 → 打开可用/售罄菜品 → 检查购物车栏 → 截图/hierarchy。

## 模拟器截图验收点

- 412dp 对照 `merchant-menu-a.png` 检查封面、菜品卡片、加购和底部栏。
- 360dp 检查最后一行菜品完整可见且不被购物车栏盖住。
