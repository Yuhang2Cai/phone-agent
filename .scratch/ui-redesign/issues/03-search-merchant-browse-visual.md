# 03: 搜索页与商家浏览视觉重建

**What to build:** 用户可在搜索页 A 输入关键词或需求、筛选附近商家并进入商家菜单；搜索结果和商家浏览都按设计图完整呈现。

**Blocked by:** 01: 设计 Token 与通用组件

**Status:** in-progress

## UI 实现范围

- 重建搜索页：返回/品牌/地址、输入框、橙色搜索按钮、AI 建议横幅、综合/距离近/评分高/配送快筛选胶囊和商家卡片。
- 商家浏览结果显示图片、评分、月售、配送时间、标签、价格区间和“去下单”；只导航到菜单，不创建购物车/订单。
- 覆盖 Idle、Searching、Results、Empty、NetworkError、AuthRequired。

## 业务逻辑复用范围

- 复用 SearchFragment、MerchantFragment、OrderingRepository、ApiClient、Glide 和现有商家接口；仅调整呈现和状态映射。

## 页面状态

- 搜索 `Idle → Searching → Results/Empty/NetworkError/AuthRequired`；商家浏览 `Initial → Loading → Content/Empty/Error`。

## 导航和返回栈

- 首页搜索进入后自动聚焦；返回回到进入前页面并保留关键词/筛选；结果点击进入商家菜单，商家返回搜索结果。

## 进程重建和旋转屏幕恢复

- 恢复关键词、焦点意图、筛选项、结果滚动和当前商家；恢复中不闪现空结果。

## 设计图对应关系

- `search-a.png`：搜索页和结果卡片。
- `merchant-menu-a.png`：商家封面、商家信息、分类和菜品浏览框架。

## 需要新增或修改的文件

- `feature/search/SearchFragment.kt`、`feature/merchant/MerchantFragment.kt`、对应 ViewModel/Repository 测试。
- `res/layout/fragment_search.xml`、`fragment_merchant.xml`、商家/搜索 item 和资源。
- `maestro/ticket-03-search-merchant-visual.yaml`。

## 阻塞依赖

- 依赖 01；04 依赖本 Ticket 的商家菜单入口和稳定控件 ID。

## 完成标准

- [ ] 结构、颜色、字体、间距、圆角、按钮、图片和卡片与设计图一致，不用默认 Material 替代。
- [ ] 360/393/412dp 无截断、遮挡、底部覆盖；控件树 Resource ID/content description 稳定。
- [ ] 真实/Fake API 覆盖成功、空结果、网络失败和鉴权状态；Maestro 实际通过并提供截图和控件树证据。

## Maestro 验收流程

- 清理两个包 → 安装最新 debug APK → 进入首页搜索 → 输入“清淡午餐” → 搜索 → 切换筛选 → 打开商家 → 返回 → 截图/hierarchy。

## 模拟器截图验收点

- 412dp 对照 `search-a.png` 的搜索区、AI 建议、筛选胶囊和结果卡片。
- 对照 `merchant-menu-a.png` 检查封面、分类、菜品列表和底部预留空间。
