# Task1 独立验收报告

当前结论：**PASS（修复后独立复验通过）**。2026-10-01 21:51:09 已完成本轮复验：核心 30 项、原新增验收 10 项、嵌套/历史/实时 SSE 补验 4 项全部通过；Python 跨仓 34 项、独立载荷 106 项及 6 文件换行复现全部通过。当前 Task1 阻塞为 0，允许父线程按门禁进入 Task3。本验收 Agent 不勾选计划、不提交、不执行后续任务。

首次验收结论为 FAIL：两项 P1 阻塞均由本 Agent 实际复现。第 2/3 节保留首次发现、命令和失败证据；第 5 节记录修复后的独立复验，不能把首次失败覆盖为从未失败。

验收日期：2026-10-01，Asia/Shanghai。验收者与实现者隔离，本报告所有测试结果均由本验收 Agent 亲自运行取得，不引用开发报告的通过结论替代证据。未修改生产实现、未提交、未修改计划勾选。

## 1. 环境和范围

- 手机工作区：`C:/Users/caid0/Documents/ChatGPT/手机agent/.worktrees/fandazi-ai`，HEAD `07b865a8d4f9cc333e0dc32f465f0d358b37ba5e`，存在大量原有未提交改动，验收保留它们。
- 新 Agent 工作区：`D:/project/-Demo-master`，HEAD `878c109e90e7b0490198d0f84c569b7cd1a30368`，存在原有未提交改动。
- JVM：指定 IntelliJ JBR Java 21.0.8；Maven 使用指定内置路径，编译目标 Java 17，显式 UTF-8 JVM 参数。
- Python：`C:/Users/caid0/anaconda3/envs/pinnT/python.exe`；显式设置 `MOBILE_PROJECT_ROOT`，跨仓检查没有因缺配置跳过。
- Spring 集成测试实际启动 Spring/H2/Flyway；NDJSON 测试实际启动临时本地 HTTP 服务。领域准备与外部 Agent 使用隔离替身，不调用线上模型或生产数据库。
- 依据：两仓 `AGENTS.md`、`docs/superpowers/plans/2026-09-29-group-buying-agent-refactor-implementation.md` Task1、同名 design 第 7/8 节、`docs/verification/2026-10-01-task1-runtime-development.md`。
- 本轮明确排除尚未实施的 Task3 新 FastAPI 入口、版本化工具真实业务、真实进程切换和完整卡片 Spring 领域重查；这些未实施项没有计入 Task1 失败。
- Android 本次未修改，本轮未构建/安装 APK、未运行 Maestro，不宣称 APK 或 UI 通过。

## 2. 首次阻塞发现（已修复并复验）

### B1：公开工具事件泄露内部确认凭证，P1，已复验关闭

位置：`backend/src/main/java/com/fandazi/feature/agent/AgentToolService.java:88`；公开发布链路为 `AgentEventService.java:17`、`:37`、`:42`，公开 DTO 为 `AgentGatewayController.java:16`。

实际执行 `prepare_refund` 时，工具出口正确把旧字段归一为 `confirmationGrantId`，随后却将完整 `result.data()` 写入与公开 SSE 共用的事件。真实 H2 历史查询加实际 `EventResponse.from` 序列化后，公开 payload 包含 `confirmationGrantId` 和隔离测试凭证引用。这违反 design 7.3 公开摘要不含确认凭证的要求；NDJSON 契约拦截敏感字段不能保护 Spring 自己产生的工具事件。

验收用例：`Task1PublicToolAuditAcceptanceTest.preparationGrantIsInternalAndAbsentFromPublicEventHistory`，测试文件第 29 行，失败断言第 46 行。实测 1 项，失败 1。内部工具返回应继续保留凭证供受控执行使用，公开审计/SSE 载荷必须另行脱敏；公开历史与实时推送都须复验。

### B2：合法 tool_result.error=null 被审计转换误判为协议失败，P1，已复验关闭

位置：`backend/src/main/java/com/fandazi/feature/agent/AgentMessageService.java:50`，调用点 `HttpAgentServiceClient.java:114`。

1.1 契约的成功 `tool_result` 必须携带 `error: null`。生产审计转换使用 `Collectors.toMap`，遇到该空值抛出 `NullPointerException`，且转换发生在审计容错的 try 块外。临时 HTTP 服务发送合法工具事件再发送合法 `run_completed/succeeded`，生产客户端在第一行就抛出“Agent 服务返回格式无效”，无法返回成功结果。

验收用例：`Task1IndependentAcceptanceTest.successfulToolResultWithNullErrorMustReachCompletion`，测试文件第 30 行。实测该项错误，堆栈明确为 `HashMap.merge → Collectors.toMap → AgentMessageService.payload:50 → record:31 → consumeStream:114`。这是合法协议输入失败，不能视为 Task3 未接通。

## 3. 首次验收命令和结果（保留原证据）

以下 Maven 命令都在手机工作区执行，进程预先设置：

```powershell
$env:JAVA_HOME='D:/software/IntelliJ IDEA 2025.2.4/jbr'
$env:MAVEN_OPTS='-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8'
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8'
```

### 3.1 指定 Maven 组合

```powershell
& 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' -f backend/pom.xml '-Dtest=AgentContractSchemaTest,AgentGatewayV1ContractTest,AgentProtocolBoundaryTest,AgentRunIdentityTest,AgentToolFieldBoundaryTest,AgentGatewayTest' test
```

首次运行 29 项全通过。父线程通知冻结请求 runId 的补丁后，本验收重新执行同一命令：2026-10-01 21:40:36，**30 项，失败 0、错误 0、跳过 0，BUILD SUCCESS**。

| 测试类 | 实际项数 | 结果 |
|---|---:|---|
| AgentContractSchemaTest | 5 | PASS |
| AgentGatewayV1ContractTest | 4 | PASS |
| AgentProtocolBoundaryTest | 9 | PASS |
| AgentRunIdentityTest | 3 | PASS |
| AgentToolFieldBoundaryTest | 3 | PASS |
| AgentGatewayTest | 6 | PASS |

亲自核对身份场景：选券/填原因/明确确认保持 1 个 runId、产生 3 个不同 traceId；成功后新目标取得新 runId；`delayedOldResponseCannotOverwriteNewWaitingTask` 实际通过，旧响应未覆盖交错新任务的等待状态。网关本轮 runId 已在第 22 行冻结，成功与失败路径均使用冻结值。

### 3.2 独立追加 Maven 负例

```powershell
& 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' -f backend/pom.xml '-Dtest=Task1IndependentAcceptanceTest,Task1PublicToolAuditAcceptanceTest' test
```

2026-10-01 21:42:10，**10 项，8 项通过、失败 1、错误 1、跳过 0，BUILD FAILURE，退出码 1**。

| 验收类 | 项数 | 失败 | 错误 | 关键覆盖 |
|---|---:|---:|---:|---|
| Task1IndependentAcceptanceTest | 9 | 0 | 1 | 合法空 error、审计故障、4 种敏感字段、取消兼容、别名冲突、传输中断 |
| Task1PublicToolAuditAcceptanceTest | 1 | 1 | 0 | 内部准备结果与实际公开事件历史隔离 |

通过证据包括：NDJSON 四种敏感字段实际拒绝；取消保留内部 CANCELLED，公开旧 DTO 为 COMPLETED 且 nextAction=cancelled；新旧凭证冲突拒绝；HTTP 200 但没有暂停/终态的半截流拒绝；NDJSON 审计服务抛异常时业务终态仍返回。最后一项只证明该客户端的审计故障分支，不扩大为所有业务写入路径的故障容错已通过。

失败原始日志已另存，避免后续复验覆盖：

- `.scratch/group-buying-refactor/task1-independent-acceptance/first-failure/Task1IndependentAcceptanceTest.txt`
- `.scratch/group-buying-refactor/task1-independent-acceptance/first-failure/Task1PublicToolAuditAcceptanceTest.txt`

原始 Surefire 报告位于 `backend/target/surefire-reports/`，测试名称与上述类名一致。日志仅包含人工隔离样例，没有真实凭证。

### 3.3 指定 Python 跨仓测试

在新 Agent 工作区执行：

```powershell
$env:MOBILE_PROJECT_ROOT='C:/Users/caid0/Documents/ChatGPT/手机agent/.worktrees/fandazi-ai'
$env:PYTHONIOENCODING='utf-8'
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -m pytest tests/test_cross_repo_contracts.py -q
```

首次 **34 passed in 1.49s**；增加两仓换行属性后再次执行，**34 passed in 1.38s**，没有跳过。

### 3.4 独立实际载荷校验

在手机工作区执行验收者自行编写的测试：

```powershell
$env:MOBILE_PROJECT_ROOT='C:/Users/caid0/Documents/ChatGPT/手机agent/.worktrees/fandazi-ai'
$env:PYTHONIOENCODING='utf-8'
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -m pytest .scratch/group-buying-refactor/task1-independent-acceptance/test_task1_payload_acceptance.py -q -p no:cacheprovider
```

实际 **106 passed in 0.51s**。17 个工具分别验证人工输入、成功输出、缺失必需字段、错误类型及未知字段；准备工具内部凭证正例、业务写工具缺凭证负例、8 类公开卡片、完整动作、10 类事件、金额负数/小数/字符串/布尔及公开 grant 负例均执行标准 Draft 7，不只是字符串或名词检查。Python 契约校验通过同时证实 B2 中 `error:null` 是合法输入。

### 3.5 冻结摘要和 Windows 换行复现

两仓执行 `git check-attr text eol -- <契约路径>`：手机两份 YAML、新 Agent 两份 YAML、manifest.yaml 和 source.sha256 均为 `text: set`、`eol: lf`。属性来源：手机 `.gitattributes:2`；新 Agent `.gitattributes:2`、`:3`。

在手机工作区实际执行：

```powershell
& '.scratch/group-buying-refactor/task1-independent-acceptance/verify_contract_checkout.ps1'
```

脚本为两仓分别建立隔离 Git 仓库，复制本轮 `.gitattributes` 和契约，执行 `git -c core.autocrlf=true add` 后 `git -c core.autocrlf=true checkout-index --all --force --prefix=<隔离检出目录>/`，亲自比较检出前后 SHA-256 并检查无 CR 字节。**6 个文件全部 PASS，退出码 0**。没有修改真实仓库索引，也没有提交。

冻结值如下，验收测试同时检查权威原始字节、Agent 副本、Spring 打包资源、manifest 和旧 source.sha256：

| 文件 | schemaVersion | SHA-256 |
|---|---|---|
| agent-protocol-v1.yaml | 1.1 | `60420e0e0aadfe82f952de11ac38efa9c3c79778b23d51d59586f48639acb85f` |
| agent-tools-v1.yaml | 1.1 | `e2b0f42805303ddda0d9fc55af702091364fa62f5ef2eb845a2ca99c4ded21b8` |

隔离 Git 证据目录：`C:/Users/caid0/AppData/Local/Temp/task1-checkout-743cb5e2e16b4e7fb2311803a96fb966`。本次 `.gitattributes` 本身有换行提示，但受其约束的 6 个目标文件检出字节均已实测稳定。

## 4. 当前逐项判定与非阻塞风险

| Task1 要求 | 判定 | 实际证据 |
|---|---|---|
| schemaVersion 1.1、query、可信确认布尔 | PASS | 实际 HTTP 请求测试与独立载荷负例 |
| 17 工具及两 prepare 的副作用、守卫、确认要求 | PASS | Java/Python 解析与逐工具输入输出实际验证 |
| 等待输入/确认只暂停、取消独立终态 | PASS | 真实临时 HTTP 及公开旧 DTO 序列化 |
| Spring 稳定 runId、新 traceId、终态新任务 | PASS | 真实 H2 会话三项回归，包括交错响应 |
| 公开卡片与动作结构 | PASS（结构） | 8 类人工载荷、嵌套动作及负例；领域重查留 Task3 |
| 公开不泄露确认凭证 | PASS | B1 原用例复验通过；实际嵌套入库、实时 SSE 和旧行重放也通过 |
| 已改 runtime 能消费合法事件 | PASS | B2 原 HTTP 用例通过；真实消息审计与数据库空值往返补验通过 |
| 两 YAML 受控副本、冻结版本及双摘要 | PASS | 原始字节、打包资源、manifest、source.sha256 实测 |
| Windows checkout 摘要可复现 | PASS | 两仓属性实效与隔离 Git 入库/检出 6 文件实测 |

非阻塞风险和证据限制：

- `AgentResult.java:15` 的取消状态暂向旧 Android 映射为 COMPLETED，并通过 nextAction=cancelled 区分，本轮实际验证成功；Task5 后续仍需验证页面不会显示虚假业务成功。本次没有 Android 运行证据。
- JVM 测试有 Mockito 动态加载、H2/Flyway 兼容版本与 networknt 自定义根关键字提示。实测负例证明事件结构仍执行，未作为阻塞。
- 独立 Python 测试初版 96 项出现 `.pytest_cache` 写权限提示；补入事件用例后使用 `-p no:cacheprovider` 执行 106 项，无该提示，不影响载荷结论。
- Task3 尚未开始，真实 MySQL、实际新 Agent 入口、版本化工具全部业务、完整卡片重查和三端端到端仍未验收；不能把本报告的结构 PASS 当作这些路径已可用。

## 5. 修复后独立复验

父线程完成生产修复后通知本 Agent 独占 Maven，期间父线程不运行 Maven、不修改文件。本 Agent 先读取实际五份补丁，再独立运行原有用例，没有修改两份首次失败的验收测试或降低其断言。仅另增中文注释的 `Task1NestedPublicEventAcceptanceTest` 四项补验。

### 5.1 生产修复核对

- `AgentMessageService.java:50` 使用结构化 Map 复制保留 JSON null；`AgentEventEntity.java:22` 和 `:36` 在构造及读取时也保留 null。
- `AgentContractCompatibility.java:74` 递归处理对象、列表和嵌套列表，按敏感字段键脱敏，不替换用户正文或业务字符串。
- `AgentEventService.java:18` 在入库前执行公开脱敏；`AgentGatewayController.java:20` 在公开 DTO 出口再次脱敏，保护部署前已含凭证的历史行。
- 内部 `ToolResult.data()` 仍包含规范化的 `confirmationGrantId`，原 `Task1PublicToolAuditAcceptanceTest` 同时验证内部保留与公开隔离，两项要求均满足。

本轮五份生产源文件 SHA-256：

| 文件 | SHA-256 |
|---|---|
| AgentMessageService.java | `3a90a33863d237d1510173f684f7fabd474de11cc32952b458ee3aae8f567dd3` |
| AgentEventEntity.java | `c401dac3828c393cc5924df8d3ed528025a298d71b8e18bce98b404622e2364a` |
| AgentContractCompatibility.java | `5e7fc8ba4f9f8e1f79a8000c2afaf450999e54ecf2a79da7328c5018f0ceac9e` |
| AgentEventService.java | `f2dbab7c261694555742f13b4c757370985e25efda7ccba9e370b4ed81ed61ff` |
| AgentGatewayController.java | `096233011de071592b83546b48792656ff5cd7ede744bdbe0c4e009b1876719f` |

### 5.2 原核心与失败用例复跑

在手机工作区设置第 3 节的 JAVA_HOME 与 UTF-8 参数，亲自运行：

```powershell
& 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' -f backend/pom.xml '-Dtest=AgentContractSchemaTest,AgentGatewayV1ContractTest,AgentProtocolBoundaryTest,AgentRunIdentityTest,AgentToolFieldBoundaryTest,AgentGatewayTest,Task1IndependentAcceptanceTest,Task1PublicToolAuditAcceptanceTest' test
```

2026-10-01 21:49:32，**40 项，失败 0、错误 0、跳过 0，BUILD SUCCESS，退出码 0**。六类核心 30 项和两类原新增验收 10 项均重新执行；B1 原断言通过、B2 原合法 HTTP 输入通过。审计故障测试主动产生的错误日志为隔离故障注入的预期证据，不是本轮测试失败。

### 5.3 嵌套、历史重放、实时 SSE 与空值持久化补验

```powershell
& 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' -f backend/pom.xml '-Dtest=Task1NestedPublicEventAcceptanceTest' test
```

2026-10-01 21:51:09，**4 项，失败 0、错误 0、跳过 0，BUILD SUCCESS，退出码 0**。与 5.2 合计实际运行 Maven 44 项，全部通过，分两条命令执行。

| 用例 | 测试行号 | 实际证据 |
|---|---:|---|
| legalNullErrorSurvivesRealDatabaseRoundTrip | 41 | 真实消息审计写入 H2，flush/clear 后重新加载，error=null 与 ok=true 均保留，公开 JSON 仍为 null |
| nestedSecretsAreRemovedBeforePersistenceWithoutChangingBusinessValues | 60 | 顶层、对象、列表、嵌套列表中的凭证/鉴权键均删除；金额、布尔、原文、普通 tokenCount、null 和原内部源对象保持 |
| historicalUnfilteredRowIsSanitizedByActualSseReplay | 78 | 绕过新入库边界写入旧含凭证行，实际 MockMvc SSE 重放不泄露；旧数据库行本身保持原值，证明是出口保护 |
| liveSsePublishesOnlySanitizedNestedPayload | 101 | 先建立实际 MVC SSE 订阅，再发布嵌套事件，真实响应不含凭证/鉴权字段和值，保留正常业务字段 |

补验首次编译因验收者新增嵌套样例括号错误未运行测试；只修正验收测试的样例构造后执行上述 4 项并全部通过。此问题不是生产缺陷，也未修改原失败用例。

本轮九个 Surefire 文本报告已另存至 `.scratch/group-buying-refactor/task1-independent-acceptance/recheck/`，类名与文件名对应。首次 `first-failure/` 中两份原日志仍原样保留。

### 5.4 Python 和冻结摘要复验

按第 3.3 节命令在新 Agent 仓亲自重新执行：**34 passed in 1.55s**，无跳过。

按第 3.4 节命令在手机仓亲自重新执行：**106 passed in 0.56s**，退出码 0。再次核对权威 YAML、Agent 副本、Spring 打包资源与 manifest/source.sha256，1.1 冻结摘要与第 3.5 节完全相同。

再次实际执行第 3.5 节换行复现脚本：**6 文件全部 PASS**，退出码 0。最新隔离证据目录为 `C:/Users/caid0/AppData/Local/Temp/task1-checkout-2e8ff724cb804e7091fda023963bb643`。

### 5.5 门禁决定

**Task1 独立验收 PASS，当前阻塞 0。** 原两项阻塞已由实现者修复并由独立验收亲自复验；公开凭证隔离、合法空值、任务身份、契约字段、双摘要和换行可复现性均有实际证据。父线程可以按已确认顺序进入 Task3。

Task3 的新服务入口、版本化工具真实业务、完整卡片领域重查与运行来源仍未实施/验收，不由本报告替代；Android APK/Maestro/视觉仍无本轮验收声明。第 4 节非阻塞风险继续保留。本 Agent 未提交、未勾选计划、未推进后续任务。
