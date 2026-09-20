# 02: 首页视觉重建

**What to build:** 用户进入首页后看到首页 B 的完整暖色生活方式视觉主体，并能从搜索入口 A 的搜索框、地址、通知、AI 点餐卡片、快捷建议和附近好店继续操作。

**Blocked by:** 01: 设计 Token 与通用组件

**Status:** in-progress

## UI 实现范围

- 重建首页 B：品牌/地址/通知、主视觉食物图、AI 点餐渐变卡片、快捷建议“清淡午餐/30 元以内/再来一单”、附近好店及底部导航。
- 合并首页搜索入口 A：文案“搜索商家、菜品或直接说需求”、语音入口，点击进入独立搜索页并自动聚焦。
- 覆盖首屏骨架、无地址、内容、失败重试和复购不可用提示；不放独立历史订单卡片。

## 业务逻辑复用范围

- 复用现有 HomeFragment、OrderingRepository/ViewModel、地址读取、商家查询、Glide 图片加载和 AI EntryPoint；不改变 API 语义。

## 页面状态

- `Initial → Loading → Content`；另有无地址、`Empty`、`Error`，无地址仍允许搜索和 AI 推荐。

## 导航和返回栈

- 地址栏进入地址管理，搜索框进入搜索页，商家卡片进入商家菜单，AI 卡片/快捷建议打开半屏面板；返回保持首页滚动和输入状态。

## 进程重建和旋转屏幕恢复

- 恢复首页滚动位置、搜索草稿、当前地址、快捷建议和浮球状态；恢复期间显示 Loading/恢复态，不误显示 Empty。

## 设计图对应关系

- `home-b.png`：主体、主视觉和附近好店。
- `home-search-entry-a.png`：地址下方搜索框、语音入口和 AI 入口。

## 需要新增或修改的文件

- `feature/home/HomeFragment.kt`、`feature/ordering/OrderingViewModel.kt`。
- `res/layout/fragment_home.xml`、首页 item/layout、图片和文案资源。
- `maestro/ticket-02-home-visual.yaml`（可合并旧首页入口流程，但不得以旧截图验收）。

## 阻塞依赖

- 依赖 01；03 可与本 Ticket 并行，04 依赖 03。

## 完成标准

- [ ] 结构与设计图一致；颜色、字体、间距、圆角、按钮形态、关键图片、卡片和底部栏一致。
- [ ] 不以默认 Material 组件替代设计稿；360/393/412dp 无截断、遮挡或底部覆盖。
- [ ] 所有入口有稳定 Resource ID/content description；ViewModel 覆盖状态转移。
- [ ] 按统一 Maestro 清理/安装规则实际运行完整路径并通过截图、控件树检查。

## Maestro 验收流程

- 清理 `com.fandazi.app` 和 `com.dirror.music` → 安装最新 debug APK → 启动/访客 → 检查首页 → 点击搜索入口并返回 → 打开 AI 卡片 → 检查地址和附近好店 → 截图与 hierarchy。

## 模拟器截图验收点

- 412dp 首屏完整对照 `home-b.png`；搜索框、AI 卡片、三条快捷建议、附近好店和底部栏均可见。
- 360/393dp 检查主视觉、卡片和浮球不压住底部导航。
