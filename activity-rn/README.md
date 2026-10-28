# 活动 RN 模块

本模块承载活动页及活动内导航，Android 原生宿主保留首页、AI助手、订单、活动、我的五个底部入口。首期只验收 Android；iOS 适配尚未实现。

## 当前开发状态

已实现 r2 彩色活动页、真实任务领取、服务端订单进度、手动领奖、明细及异常状态，正在按步骤验收。当前开发冒烟不能代替最终交付结论。规格和验收进度位于 Agent 仓库 `docs/superpowers/specs/2026-10-28-activity-rn-design.md`、`docs/superpowers/plans/2026-10-28-activity-rn-implementation.md`。

## 构建约定

- Node 24、JDK 17；RN 0.86.3、React 19.2.3，完整依赖由 `package-lock.json` 固定。
- Android 使用 compileSdk 36、targetSdk 35、NDK 27.1.12297006；AGP 8.12.0，沿用 Kotlin 2.1.20 和 Gradle 8.13。最终兼容性以实际构建/运行报告为准。
- 在本目录运行 `npm ci`、`npm run typecheck`、`npm test`。`npm run bundle:android -- --max-workers 2` 可单独验证 JS 与资源打包。
- 正式 APK 在 `../android-app` 用 Gradle 构建；所有变体内置 Hermes Bundle，宿主关闭开发服务器支持。`npm start` 仅是开发工具入口，不是运行 APK 的前置。
- 现有 ASR AAR/模型仍按项目受控脚本导入，不绕过原 `preBuild` 资产校验。

## 原生边界

`src/host/ActivityHost.ts` 定义跨平台薄接口。Android `ActivityHostModule` 将有限动作交给 `ActivityRequestPolicy`，复用 `ApiClient` 和 `SessionRepository`；JS 无令牌、任意 URL、用户标识或客户端奖励数值入口。

身份变化递增 generation，原生发包与回包均核对，JS 再次拒绝迟到结果。领取/领奖写入不自动重试；未知结果需查询原实例/回执，不能乐观增加金币。活动入口可打开原生登录、首页和本人订单，登录返回不会重放写请求。

原生在发包前将动作、公开目标、版本和原请求键写入不参与系统备份的原子文件，以认证身份摘要隔离。进程重建仅读取薄引用和后端事实；领取核查使用 `GET /activities/tasks?requestId=<原键>` 返回的 `recoveredTask`，领奖核查读取原实例。原请求未核清前，另一任务不能覆盖其引用。JS 不持久化余额、资格、完整任务或令牌。

页面代码按 `screens/ActivityScreens.tsx` 与 `components/ActivityComponents.tsx` 组合，`ActivitySession` 独立管理请求和路由状态，`ActivityClient` 负责会话护栏与请求合并。后端返回 `allowedActions` 决定按钮行为；退款申请冻结未领取奖励，成功退款通过可信内部适配器重算并冲正，页面不提供退款终态写入口。

benchmark 变体记录 `ActivityPerf`：原生底栏点击前的单调时间至 RN 根布局后两帧，并记录运行时数量。它衡量本地结构可交互，不表示网络数据已返回；正式性能报告同时记录接口延迟及完整样本。普通发布变体不输出这些测量日志。

## Figma 资源

资源来自已确认 r2，文件 `FxsWKGs10z7BL1MhFAOqFN`，活动首页 `245:33`、游客页 `245:34`。`src/assets/6ce53.png` 是装饰插画，文字、任务和按钮仍使用真实组件。`80fe4.svg`、`9d141.svg`、`2a49f.svg`、`1db36.svg` 是不同槽位的金币图标。

五个导航图标从同次设计上下文下载，映射为 `3b437.svg`→首页、`677c9.svg`→AI助手、`c72f8.svg`→订单、`59f3c.svg`→活动、`e1d6b.svg`→我的。运行 `scripts/Export-NavigationAssets.ps1` 可把原始路径无损转换为 Android VectorDrawable。底栏仍由原生绘制，RN 不重复显示。

临时 Figma 下载地址不进入运行期配置。资源格式转换不调整原始路径或业务文案。
