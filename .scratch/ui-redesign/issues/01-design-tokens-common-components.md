# 01: 设计 Token 与通用组件

**What to build:** 建立 Android UI 重构的唯一视觉基线和可复用 XML 组件，使后续页面在 412 × 915 dp 主画板及 360/393 dp 兼容宽度上使用同一套颜色、字体、间距、圆角、按钮、输入框、卡片、图片占位、底部导航和浮动入口。

**Blocked by:** None (can start immediately)

**Status:** in-progress

## UI 实现范围

- 按 `docs/ui-redesign.md`、`docs/ui-design-references/*.png` 和 `.scratch/ui-redesign/spec.md` 建立 XML 主题、颜色、字号、间距、圆角、阴影、状态色和点击态；不得以 HTML、旧截图或旧 XML 为视觉依据。
- 封装通用页面容器、顶部栏、四项底部导航、主/次按钮、输入框、搜索框、胶囊筛选、白色卡片、商品图片占位、Snackbar、加载骨架、空/错/禁用状态和 AI 浮球。
- 主画板为 412 × 915 dp；实现只用 `dp/sp`，不写死 1080 × 2400 px。

## 业务逻辑复用范围

- 复用现有单 Activity、多 Fragment、MVVM、SessionRepository、ApiClient、ImageLoader/Glide 和页面路由；不改后端接口、Agent 编排或支付逻辑。
- 如发现接口缺陷，只记录为阻塞问题，不在本 Ticket 修后端。

## 页面状态

- 覆盖通用 `Initial/Loading/Content/Empty/Error/Submitting/Disabled/Success` 展示约定。

## 导航和返回栈
- 认证页不显示底部导航和 AI 浮球；业务页显示四项导航：首页、商家、订单、我的。

## 进程重建和旋转屏幕恢复
- 旋转与进程重建恢复当前路由、临时输入和访客/登录状态；组件本身不得清空宿主页面状态。

## 设计图对应关系

- `auth-b.png`：认证表单、品牌、引导卡片。
- `home-b.png`、`home-search-entry-a.png`：底部导航、搜索框、AI 入口、浮球共性。
- `ai-panel-a.png`、`checkout-c.png`：按钮、卡片、输入区和状态样式。
- `docs/ui-redesign.md` 第 2、3、11、13 节为 Token 和组件约束。

## 需要新增或修改的文件

- `android-app/app/src/main/res/values/colors.xml`、`themes.xml`、`strings.xml`。
- `android-app/app/src/main/res/drawable/` 下通用背景、按钮、卡片、输入框和占位资源。
- `android-app/app/src/main/res/layout/` 下通用组件布局、`activity_main.xml`、`bottom_navigation_menu.xml`。
- `android-app/app/src/main/java/com/fandazi/app/core/` 下 UI 状态、导航和图片加载辅助类及对应测试。

## 阻塞依赖

- 后续 02–08 均依赖本 Ticket 的 Token、组件 ID 和导航契约。

## 完成标准

- [ ] 结构与对应设计图一致；颜色、字体、间距、圆角和按钮形态一致。
- [ ] 不使用默认 Material 样式替代设计稿组件；关键图片占位、卡片、底部栏和输入区完整可复用。
- [ ] 360dp、393dp、412dp 不出现遮挡、截断或底部覆盖；控件树具有稳定 Resource ID 或 content description。
- [ ] 先卸载 `com.fandazi.app`，再卸载 `com.dirror.music`，安装最新 debug APK。
- [ ] Maestro 实际检查启动、登录/访客入口、四项导航、截图和控件树；提供模拟器截图作为视觉证据。

## Maestro 验收流程

- 新建/更新 `maestro/ticket-01-design-foundation.yaml`：清理两个包 → 安装最新 APK → 启动 → 检查认证页 → 跳过登录 → 依次点击四项 Tab → 截图 → hierarchy 控件树检查。

## 模拟器截图验收点

- 412dp：认证页完整首屏、四项底部导航、通用按钮/输入框/卡片。
- 360dp、393dp：同一页面无横向溢出、底部导航文字完整且不被系统区域遮挡。
