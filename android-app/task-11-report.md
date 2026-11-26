# Task 11 Android Agent UI 报告

## 实现范围

- 新增 `feature/agent` 的 MVVM 状态机、Repository 和公开 Agent Gateway 客户端。
- 仅调用公开 `/api/agent/sessions/{sessionId}/messages`、`/events` 和 `/api/automation/task-grants`；APK 只使用用户会话 Bearer Token，不包含也不发送内部 Agent Token。
- SSE 使用 `Last-Event-ID`，在连接结束或网络 `IOException` 后以最近收到的事件 ID 重连。
- 新增 XML BottomSheet：半屏/全屏、快捷指令、文字发送、录音 ASR 回填、事件卡片、错误重试和购物车确认卡片。
- 确认卡片包含商家、菜品/服务端购物车提示、默认地址、配送费、优惠、总价、授权方式和金额上限；支持确认、取消与修改。
- 默认授权方式为 `CONFIRM`；只有用户点击“我主动授权本次任务自动下单”后才创建 15 分钟的 `THIS_TASK_AUTO` grant，且保留默认地址和金额上限。
- 首页、搜索、商家、订单页显示 AI 浮球；认证与模拟支付页没有该入口，并有纯逻辑策略测试覆盖。

## 测试覆盖

新增 `AgentViewModelTest`：

- 打开/关闭、半屏/全屏。
- 题设的“感冒、清淡、不要香菜、30 元以内、默认地址”文字请求与 `CONFIRM` 卡片。
- ASR 文本回填。
- 事件展示、事件游标保存。
- 确认、取消、修改入口。
- `THIS_TASK_AUTO` 的显式选择、默认地址和金额上限。
- 401 认证错误、503 服务端错误和重试状态。
- 页面浮球显示策略。

新增 `HttpAgentRepositoryTest`：

- 公开 Agent message 请求只有 Bearer Token，断言没有 `X-Agent-Token`。
- SSE 断线后以 `Last-Event-ID` 重连并继续消费事件。
- 兼容当前 Gateway 的 `merchants + quote` 购物车返回结构。

## 验证结果

- `git diff --check`：通过。
- Android 资源 XML 解析、字符串引用、应用内 `R.id` 引用检查：通过。
- 生产 Android 源码扫描 `X-Agent-Token`、`/internal/`、`AGENT_INTERNAL`：无匹配。
- `gradlew.bat testDebugUnitTest`：未能执行测试。用户级 `C:\Users\caid0\.gradle\gradle.properties` 将 Maven 请求固定到不可用的 `127.0.0.1:7890`。使用隔离 Gradle 用户目录和本机 Android SDK 后，Gradle 本身可以下载，但 Android Gradle Plugin classpath 未解析，`app/build.gradle.kts` 的 Android DSL 因而在配置阶段报 unresolved references。没有宣称 APK 或 Kotlin 编译成功。

## 剩余集成注意事项

确认 DTO 已在修复轮中补全；客户端仍以 fail-closed 方式对未来的缺字段响应处理，避免把非完整的公开响应展示成可确认订单。

## 修复轮 1（2026-09-12）

- Gateway 在 `AWAITING_CONFIRMATION` 时以服务端 cart quote 与地址补全商家、菜品、规格、地址文本、配送费、优惠和总价；Agent 服务明确携带其权威的金额上限，Gateway 明确携带授权模式。Android 对缺少任一字段的响应只暴露 `candidateCart`，不会创建确认卡片、确认下单或 `THIS_TASK_AUTO` 授权。
- `AgentUiState` 增加 `candidateCart`；取消自动授权通过公开 `DELETE /api/automation/task-grants?sessionId=...` 撤销且使 grant 已消费/过期，只有服务端撤销成功后 UI 才清除授权状态。
- 所有 Repository 的阻塞 JSON OkHttp `execute()` 均进入可注入的 `Dispatchers.IO`；SSE 对首次 404 和非鉴权断线保留游标重试，鉴权失败才终止。会话在首条消息成功后再订阅 SSE，游标和已建立会话标记保存在 `SavedStateHandle`。
- HTTP 200 的 `FAILED/error` 映射为可重试错误并清除确认/候选卡；所有非确认终态也清除旧卡。ASR 拒权、不可用和识别错误进入可见错误 UI，自动授权开关取消会走撤销流程。
- 构建脚本改为 Kotlin DSL `plugins {}`，补齐 AndroidX 与非 ASCII worktree 的 AGP 配置；同时修复原有 OkHttp/SessionStorage/Flow 编译阻塞，使 Android JVM 测试可运行。

### 修复轮验证

- `testDebugUnitTest --tests 'com.fandazi.app.feature.agent.*'`：通过。新增/更新覆盖不完整卡片 fail-closed、candidateCart、HTTP 200 FAILED、首条消息后的 SSE、404 重连 `Last-Event-ID`、公开 revoke 和自动授权撤销。
- 全量 `testDebugUnitTest`：Agent 测试通过；已有 3 个非 Agent 失败仍存在：`ApiBaseUrlBuildTypeTest`、`DebugNetworkPolicyTest`（依赖源路径，ASCII junction 测试路径不满足其假设）和 `SessionRepositoryTest.cancellationDuringSessionWriteIsRethrown`（既有断言失败）。
- 后端未提供 Maven wrapper，且本机没有 `mvn`；Agent 服务环境没有 `python` 命令，因此本轮无法执行它们的编译/测试。未声称其通过。
