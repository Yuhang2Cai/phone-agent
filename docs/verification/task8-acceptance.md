# Task8 独立验收报告

状态：已暂停 / 待整体验收。2026-10-02 01:50:21（上海时间）按用户明确要求暂停 Task8 并保存进度；计划执行顺序需控制端重新安排，不再启动验收、测试、接口或数据操作。此前服务阶段 PASS 保留为历史，不能代表新 Agent 最新修复版已经完成在线复验；最新 D 源离线 368/368、真实 MySQL 10/10 已亲自运行结束，其余复验暂停。整体验收未完成，Android、模拟器、APK、Maestro 和 Figma 未接管，不能报告整个 Task8 PASS。

## 用户暂停记录与精确恢复点

本轮暂停前的修复由控制端报告：无可信 cityCode 的复合请求改为 waiting_input，补充城市后保留原目标重规划；coupon/faq 分类后省去无用槽位调用。控制端声明新 Agent PID53472 已加载新源，8080 仍 PID44128。本验收没有亲自操作 Android 或复现其最新两项失败，也没有完成 PID53472 的真实健康来源及双服务在线复验，不把实现者自测 31/31 作为独立通过证据。

| 最后实际命令 | 已结束的真实结果 | 时间 / 证据 |
|---|---|---|
| D 项目离线全量，显式排除五个 live 文件，首轮未设 MOBILE_PROJECT_ROOT | 发现 368，367 通过、0 失败、0 错误、1 跳过，退出码 0；跳过跨仓库契约检查，不算全量通过 | 01:47:26 结束；agent-53472/offline.xml，20.825 秒 |
| 原独立真实 MySQL 十项 | 10/10，0 失败、0 错误、0 跳过，退出码 0 | 01:47:50 结束；agent-53472/mysql.xml，8.063 秒 |
| 补齐 MOBILE_PROJECT_ROOT 后重跑同一离线全量 | 368/368，0 失败、0 错误、0 跳过，退出码 0；包含新增四个缺城市回归场景和两个无槽位读取场景 | 01:48:58 结束；agent-53472/offline-final.xml，21.985 秒 |

上述 XML 与安全原始日志均保留在 `C:/fdz-build/.runtime/task8-independent-20261002/agent-53472/`，实际逐份读取 tests/failures/errors/skipped/time 计数，不只依据退出码。首轮环境跳过证据未覆盖或删除。真实 MySQL 十项仍使用隔离模型/业务工具、生产 MySqlRunStore 与新仓库恢复；只验证状态机和检查点，不算正在运行的双服务或真实模型测试。该模块在暂停前结束时按既有 finally 精确清理自身随机 run 行，没有在暂停后追加清理。

暂停到达时已立即停止发起新操作，并向最后 pytest 会话发送安全中止请求；回收会话返回其已结束的 `OFFLINE_FINAL_EXIT=0`，不是中途中止，结果保留为完成。其余两个测试会话也已正常结束；本验收没有尚在运行的测试命令。暂停后仅读取这些已有结果并更新本报告，没有运行新测试或接口。

本轮读取源码时的指纹：`service/engine.py` SHA256 `AF89C65965F2C2D2B56DE3A86729BE2F4F921756DD94737D92497C28AEF499FD`；`service/dag_runtime.py` SHA256 `F063EBB243F8FF1EA0F2AE528FE2D15BE1649C7467955108DE456B39701127D5`。此指纹用于恢复时区分版本，不代替运行进程已加载来源的证明。

最后真正运行的命令如下；后续没有启动 Maven、Android、服务重启或数据准备：

```powershell
. C:/fdz-build/scripts/Initialize-FandaziEnvironment.ps1
$env:PYTHONDONTWRITEBYTECODE='1'
$env:PYTHONPATH='D:/project/-Demo-master'
$env:RUN_TASK8_LIVE='0'
$env:RUN_AGENT_LIVE='0'
# 首轮缺跨仓库路径，真实结果为 367 通过及 1 跳过；日志保留。
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -X utf8 -m pytest tests -q -p no:cacheprovider --tb=short --basetemp C:/fdz-build/.runtime/task8-independent-20261002/agent-53472/pytest-offline-temp --junitxml=C:/fdz-build/.runtime/task8-independent-20261002/agent-53472/offline.xml --ignore=tests/test_agent_service_live.py --ignore=tests/test_agent_service_e2e_live.py --ignore=tests/test_task2_page_data_live.py --ignore=tests/test_task2_fixtures_live.py --ignore=tests/test_task8_support_live.py
# 原独立真实 MySQL 十项，暂停前已结束。
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -X utf8 -m pytest C:/fdz-build/docs/verification/task8-service-evidence/test_agent_mysql_acceptance.py -q -p no:cacheprovider --tb=short --junitxml=C:/fdz-build/.runtime/task8-independent-20261002/agent-53472/mysql.xml
$env:MOBILE_PROJECT_ROOT='C:/fdz-build'
# 最后启动的测试命令：相同源码补齐环境，实际 368/368，无跳过，暂停前已结束。
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -X utf8 -m pytest tests -q -p no:cacheprovider --tb=short --basetemp C:/fdz-build/.runtime/task8-independent-20261002/agent-53472/pytest-offline-final-temp --junitxml=C:/fdz-build/.runtime/task8-independent-20261002/agent-53472/offline-final.xml --ignore=tests/test_agent_service_live.py --ignore=tests/test_agent_service_e2e_live.py --ignore=tests/test_task2_page_data_live.py --ignore=tests/test_task2_fixtures_live.py --ignore=tests/test_task8_support_live.py
```

精确恢复点：已完成最新 D 源离线全量和原 MySQL 十项；额外独立的缺城市恢复归属/取消/恶意计划与槽位异常边界尚未创建或执行。等待输入、空/超长、重启、取消及无用槽位跳过的现有产品回归已随 368 项运行，但尚不能据此证明最新真实模型没有原两项失败。最新在线三项、所需售后在线回归和原优惠券十项均未启动；Android 最新 APK、Maestro 与 Figma 仍未接管。恢复前须由用户/控制端重新确认执行顺序与授权，不自动继续。

隔离 Android 券清单 `.runtime/task8-coupon-ui-fixture.json` 与八条定义、八条领取行按最后已核验状态留存；暂停后未查询、改写、清理或重建这些资源。原 task2 清单及 review-retry 七账号清单均未操作或重置。没有修改产品源码、提交或勾选计划；此前失败、修复及历史服务 PASS 证据全部保留。

## 范围与环境

- 独立验收者亲自执行；没有启动其他 Agent，没有修改产品代码、提交或勾选计划。
- 权威说明：`2026-10-02-task8-independent-brief.md`；计划第 8.1–8.8 节；同名规格；新 Agent 的 `AGENTS.md`。
- 手机目录 `C:/fdz-build` 是 junction，实际目标 `C:/Users/caid0/Documents/ChatGPT/手机agent/.worktrees/fandazi-ai`；本轮开始时 HEAD `d6c64418b33a54805ae800b71ca7f23284e6ce97`。
- 新 Agent 目录 `D:/project/-Demo-master`；HEAD `8265f0815cee526d62447fd0fc7f15f3a6110bc9`；使用其 `service.app:create_app`，没有运行旧手机 `agent-service` 的工作流测试。
- Java `D:/software/IntelliJ IDEA 2025.2.4/jbr`；Maven `D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd`；Python `C:/Users/caid0/anaconda3/envs/pinnT/python.exe -X utf8`。
- 运行前 dot-source `C:/fdz-build/scripts/Initialize-FandaziEnvironment.ps1`，设置 `MOBILE_PROJECT_ROOT=C:/fdz-build`；真实 Agent 检查点库为 `dianping_intent`。
- 原始日志、JUnit XML、Surefire XML 和编译快照仅存 `.runtime/task8-independent-20261002/`。本报告不记录密码、JWT、密钥、完整私有事件或真实用户数据。
- 未输出或重置七场景凭据和开发夹具。初轮真实 API 使用动态注册账号；补正后的原有 live 测试在子进程内读取忽略目录清单并登录三个既有账号，只读验证申请。额外 MySQL 测试仅清理自身生成的随机任务行。
- 运行环境最终复验时 8080 监听 PID6740，进程为 `com.fandazi.FandaziApplication`，Java 路径为上述 jbr；此前 PID42924 的检查保留为历史。新进程来源通过真实目录/售后接口、业务 MySQL 限定账号查询及优惠券状态变更实测共同核对。8091 仍监听 PID47612，亲自读取健康响应为 `sourceRoot=D:\project\-Demo-master`、`entryPoint=service.app:create_app`、`schemaVersion=1.1`、`buildVersion=8265f08-working-tree`。没有仅凭 PID 判定服务来源。
- 控制端声明 PID6740 为 development、显式故障开关 true、Task2 夹具开关 false。本验收实际验证本人故障注入可用且一次 503→200、他人与未认证请求隔离；没有重启服务、调用夹具重置或更改这组开关。

## 历史服务阶段结果

| 检查 | 实际结果 | 阶段状态 |
|---|---|---|
| 新 Agent 离线全量，显式排除五个 live 文件 | 362 项通过，0 失败、0 错误、0 跳过，19.721 秒 | PASS |
| 首轮真实服务原有 live 六项 | 6 项通过，0 失败、0 错误、0 跳过，29.051 秒 | 历史 PASS，补正后七项已复验 |
| 按券进度补正及重启后 live 全套 | 7 项通过，0 失败、0 错误、0 跳过，25.499 秒 | PASS，包含实际源、分类与售后闭环 |
| 额外真实 MySQL 写入边界 | 2 项通过，0 失败、0 错误、0 跳过，4.084 秒 | PASS，准备/拒绝不写，确认后只写一条 |
| 独立真实 MySQL 检查点测试 | 10 项通过，0 失败、0 错误、0 跳过，11.018 秒 | PASS |
| 后端全量 Maven 首轮 | 51 份当轮 Surefire XML，共 200 项，184 通过、16 失败、0 错误、0 跳过；退出码 1 | 历史 FAIL，环境隔离后原失败已复验通过 |
| 后端最终全量 Maven（优惠券修复前） | 51 份当轮 Surefire XML，共 201 项，201 通过、0 失败、0 错误、0 跳过；退出码 0 | PASS，包含新增第八个退款测试 |
| 最新优惠券修复后全量 Maven | 52 份当轮 Surefire XML，共 209 项，209 通过、0 失败、0 错误、0 跳过；退出码 0 | PASS，新增 CouponAvailabilityTest 8/8 |
| 首轮独立优惠券有效期/禁用/归属验证 | 9 项启动，6 通过、3 失败；退出码 1 | 历史 FAIL，原证据保留 |
| 首次修复后的原独立九项复验 | 9 项发现/启动/通过，0 失败；退出码 0，9.521 秒 | PASS，保留独立固定快照 |
| PID6740 阶段原九项最新类复验 | 原九项再次全部通过，9.639 秒；测试 class SHA256 与旧失败/修复后相同，产品 class 为修复版 | PASS |
| PID6740 支持/售后受影响在线范围 | 原七项加两项独立真实写入边界，9/9，0 失败、0 错误、0 跳过，42.617 秒 | PASS |
| PID6740 真实优惠券接口/新 Agent 卡片 | 8 个日期/状态场景、归属认证、双服务卡片共 10/10，0 失败、0 错误、0 跳过，7.856 秒 | PASS，运行环境阻塞关闭 |
| 优惠券修复前计划相关八套件 | 在 201 项全量中实际执行 34 项，34 通过；当时未另运行 `-Dtest` 命令 | 历史覆盖 PASS |
| 最新独立定向 Maven 九套件 | 独立 `-Dtest` 命令，9 份当轮 XML，42 项通过，0 失败、0 错误、0 跳过；退出码 0 | PASS |
| 新增按券查询退款申请接口 | 本人真实 REQUESTED 与 detail 一致；无申请 404、他人 400、未认证 401 | PASS |
| Android 与逐屏 Figma | 本阶段未操作 Android、模拟器或 Figma | 未完成，等待后续接管 |

## 新 Agent 与真实服务覆盖

离线全量显式排除 `tests/test_agent_service_live.py`、`test_agent_service_e2e_live.py`、`test_task2_page_data_live.py`、`test_task2_fixtures_live.py`、`test_task8_support_live.py`。没有将 live 跳过计入通过。

本轮亲自运行的在线六项：FAQ 三类目录、分类与关键词交集、六投诉类型；本人一次 503 后 200 并读取原真实列表；另一账号读取正常且不消耗本人故障；未认证注入、订单和直接 `/error` 访问均 401；新 Agent 健康来源、JWT 缺失及身份不匹配；公开 Spring 网关问候和空订单；投诉补充、准备、明确确认；本人真实购买/模拟支付后选券、填原因、确认退款；复合只读请求使用真实 Planner/DAG 执行 `list_user_orders` 与 `query_faq`。

退款实际完成的是 REQUESTED 申请，在线断言确认回复为等待处理且不包含到账。投诉实际提交 OPEN 工单。上述在线分类来自正在运行的新 Agent，未注入分类或槽位模型；这些场景不等于系统性意图准确率实验。

补正后亲自运行七项，包括 `test_owned_refund_progress_reloads_from_voucher`。本人按券重进响应与同一申请 detail 完全一致，状态为 REQUESTED；既有无候选场景虽 `canRefund=false`，按券查询仍返回真实无申请 404，未以资格字段伪造申请；他人返回 `400 DOMAIN_ERROR/团购券不存在`，未认证返回 401。新增 `RefundService.detailByVoucher` 先查本人券，再按用户与券公开编号查询唯一申请，复用现有 summary；仓储与路由不改变 17 工具契约。

额外两项通过公开 API 创建独立合成账号的真实订单/支付/申请，数据库只执行限定该账号的行数查询。退款准备后为零行，空白/501 字原因及错误会话均为 400 且零行；原准备凭证在合法 500 字原因提交时仍可使用，最终一行 REQUESTED，与按券/按申请读取完全一致；重复提交拒绝且仍一行，其他账号与未认证不能读取。投诉准备后零行，空白/256 字主题、空白/2001 字描述及错误会话拒绝且零行；255 字主题与 2000 字描述合法提交 OPEN 一行，重复提交拒绝且仍一行。两个返回时间均可解析为 UTC。没有为通过断言修改业务表。

独立 MySQL 十项使用生产 `MySqlRunStore`，新建仓库/引擎从真实 `dianping_intent.agent_service_runs` 恢复，模型与外部业务工具仅在验收测试中显式隔离，不调用 8080。覆盖：空白与 501 字退款原因不污染检查点、跨用户拒绝、恢复后非可信确认不写入、可信确认仅写一次、选券/填原因/确认三阶段取消、过期确认拒绝、不确定写响应重建后不重放、明确拒绝不误报不确定、独立 FAQ 成功分支在整体失败时仍发布且失败下游不执行。

源码核查：`service/engine.py` 使用独立槽位副本验证；失败输入继续 `waiting_input`。不确定写标记优先于终态判断；明确拒绝清理进行中标记但仍保持失败终态。`service/dag_runtime.py` 从成功检查点发布独立读取答案。与 Task2 基线比较，意图配置、0.80 阈值、旧 decision/router 基线没有本轮变更。

## 已证实缺陷与复验：优惠券当前可用语义

首轮缺陷位置：`backend/src/main/java/com/fandazi/feature/coupon/CouponService.java` 的 `list`；`UserCouponEntity` 初始化状态；`AgentDomainProjection.coupon` 只转换日期。修复前产品实现直接返回持久化领取状态，未将日期和 `CouponEntity.active` 纳入当前可用判断。

亲自运行独立九项测试，使用后端已编译类的固定副本、真实 Spring/JPA/H2 持久化与 MockMvc `/api/coupons`，没有启动 Maven、监听端口、调用 8080 或写入共享业务库。每例落库后清空一级缓存再查询；同时核对 `CouponService.list`、公开 HTTP 响应和工具日期投影。测试日期为上海业务日 2026-10-02。

| 场景 | 持久化状态 | active | 有效日期 | 实测服务/API/工具投影状态 | 结论 |
|---|---|---|---|---|---|
| expired | AVAILABLE | true | 2026-09-22 至 2026-10-01 | 全部 AVAILABLE | FAIL，已过期仍声明可用 |
| future | AVAILABLE | true | 2026-10-03 至 2026-10-12 | 全部 AVAILABLE | FAIL，尚未生效仍声明可用 |
| disabled | AVAILABLE | false | 2026-10-01 至 2026-10-03 | 全部 AVAILABLE | FAIL，禁用仍声明可用 |
| valid | AVAILABLE | true | 2026-10-01 至 2026-10-03 | 全部 AVAILABLE | PASS |
| first_day | AVAILABLE | true | 2026-10-02 至 2026-10-03 | 全部 AVAILABLE | PASS，首日包含在内 |
| last_day | AVAILABLE | true | 2026-10-01 至 2026-10-02 | 全部 AVAILABLE | PASS，末日包含在内 |
| used | USED | true | 2026-10-01 至 2026-10-03 | 全部 USED | PASS |
| persisted_expired | EXPIRED | true | 2026-10-01 至 2026-10-03 | 全部 EXPIRED | PASS |
| 他人归属 | 本人独立领取行 | true | 当前有效 | 他人查询为空，本人查询一行 | PASS |

原始证据：`.runtime/task8-independent-20261002/coupon-javac-run.log`；快照 `coupon-javac/classes/`；`CouponService.class` SHA256 `4CED77EEC527534124F31C8D8115D83B55D9A02E565165A4A356B0964B3CB94D`。

建议最小语义变更：只在服务读取投影中计算当前展示状态，不更新数据库、不新增领取/抵扣/核销行为。已持久化 USED/EXPIRED 等历史状态保持优先；仅 AVAILABLE 记录在 `active=true` 且上海当前日期位于 `[validFrom, validTo]` 时仍输出 AVAILABLE；到期输出 EXPIRED，未生效/禁用使用明确不可用的展示状态（例如 NOT_YET_VALID/DISABLED），继续保留原日期和归属字段。权威契约的优惠券 `status` 是文本字段，具体展示文案需三端一致；本验收没有实施修改。

首轮及首次修复后的工具日期投影为真实类的隔离执行，当时没有在 8091 通过真实聊天展示这些合成券。后续 PID6740 运行环境复验已单独执行真实聊天和卡片链路，具体证据如下；全程没有执行优惠抵扣。

### 最新类独立复验

控制端更新产品源码与 class 后，本验收者创建新快照 `coupon-javac-fixed/` 并重编译原独立测试；没有改变原九项断言。两轮独立测试 class 的 SHA256 完全相同，均为 `05F86C15BF9EE9DB8CDED5B10DEFFB94DFC4690C91FDACB029B0BB0E21767BCC`。修复后产品 `CouponService.class` SHA256 为 `17C0761B58933FC65AEC87C7B0F565AF42E7AB61D3C9B8712030CD83D7CF9328`，与旧失败快照不同。

00:45:06 完成原九项复验，退出码 0，9 项启动、9 项通过、0 失败，9.521 秒。实际三层读取结果（服务、MockMvc API、工具日期投影）一致：expired 为 EXPIRED、future 为 NOT_YET_VALID、disabled 为 DISABLED；valid、first_day、last_day 为 AVAILABLE；used 为 USED；persisted_expired 为 EXPIRED；他人查询仍为空。原先落库为 AVAILABLE 的三类记录没有为通过测试被改成其他领取状态。

修复源码在 `CouponService.java:23` 固定一次列表的上海业务日，在第 35 行的 `displayStatus` 中先保留历史终态，再判过期、停用、未生效，最后返回 AVAILABLE。方法仅生成返回文本，没有调用保存或更新；原日期/归属仍由同一 Map 返回，权威 17 工具契约未变。

复验命令：`& C:/fdz-build/docs/verification/task8-service-evidence/Invoke-CouponSnapshotCheck.ps1 -RunName coupon-javac-fixed`。证据 `.runtime/task8-independent-20261002/coupon-javac-fixed-run.log`；旧 `coupon-javac-run.log` 与旧快照均保留。此轮不占用 Maven，也不访问 8080。该轮结论当时仅覆盖 class，PID6740 就绪后的线上投影已另行完成真实复验，没有以隔离测试替代在线证据。

### PID6740 运行环境最终复验

控制端随后确认运行后端已经加载最终优惠券源码，Android 仍由实现者独占。按最新指示，本轮没有重复运行已通过的 209 项 Maven；先复制最新产品 class 到新快照 `coupon-javac-live6740/`，亲自重编译并执行原九项，00:51:29 结束，退出码 0，9/9。三轮独立测试 class SHA256 均为上述 `05F86C15...767BCC`，原九项断言未变；最新产品 class SHA256 仍为 `17C0761B...CF9328`，与前一轮全量/定向使用的修复版一致。

支持/售后在线回归于 00:52:13 结束，退出码 0，JUnit XML 实计 9 项，0 失败、0 错误、0 跳过，42.617 秒。覆盖原七项真实分类/目录/筛选/故障隔离/按券进度/投诉/退款/复合流程，以及前述两项真实 MySQL 写入行数边界。本人故障仍实际 503→200，他人不受影响，直接 `/error`、未认证注入和业务读取仍拒绝；REQUESTED/OPEN 不伪造成到账或处理完成。

真实优惠券新增验收模块 `task8-service-evidence/test_live_coupon_projection.py` 只在新注册的合成账号下插入八组独立测试券和领取记录；没有更改七场景、已有用户或业务行。API 与聊天查询结束后再次核对领取状态保持原值，最后按本模块生成的随机编号和所属账号精确删除合成券行，保留合成账号及审计记录。插入仅用于测试夹具，不实现新的领券/抵扣/核销功能。

真实优惠券在线 10 项于 00:53:23 结束，退出码 0，JUnit XML 实计 10 项，0 失败、0 错误、0 跳过，7.856 秒。下表为真实 8080 `/api/coupons` 与同一记录在查询后的 MySQL 状态：

| 合成场景 | 真实 API 展示状态 | 查询后的持久化领取状态 | 结果 |
|---|---|---|---|
| expired | EXPIRED | AVAILABLE | PASS |
| future | NOT_YET_VALID | AVAILABLE | PASS |
| disabled | DISABLED | AVAILABLE | PASS |
| valid | AVAILABLE | AVAILABLE | PASS |
| first_day | AVAILABLE | AVAILABLE | PASS |
| last_day | AVAILABLE | AVAILABLE | PASS |
| used（定义已过期且停用） | USED | USED | PASS，历史终态优先 |
| persisted_expired | EXPIRED | EXPIRED | PASS |

八项均确认原 validFrom/validTo 保留、整数分金额与 DECIMAL 测试定义相符。第九项真实认证边界：另一账号查询为空，传入合成拥有者 userId 参数不能越权，未认证为 401。第十项真实双服务：`查询我的优惠券` 在 D 项目真实分类为 coupon，实际执行且只执行 `query_coupon`，公开结果 COMPLETED，8 张卡片逐一保留同一后端状态；没有私有确认凭据，8 条领取记录仍保持原持久化状态。这不是注入分类或固定文本回答。

本阶段在线合计 19/19（支持/售后 9 加优惠券 10），加原九项最新类复验再次 9/9。运行环境优惠券问题已复验关闭，无新增服务阻塞。证据仅在 `.runtime/task8-independent-20261002/restart-6740/live-regression.xml`、`live-regression.log`、`live-coupon.xml`、`live-coupon.log` 和 `coupon-javac-live6740-run.log`；均被 Git 忽略。

新 Agent 的 `service/engine.py` 与 `service/dag_runtime.py` 复核 SHA256 仍分别为 `F6ED8A47C868CF20860D9E27F5E0882493B7BFDFC485A62738980CCDA53E6947`、`C989D91459060C7CD27A30A73257679F6D13E82A1050D1FA8554D688E2F9BE88`，与离线 362 项及真实 MySQL 10 项测试时一致。这两组证据继续适用于当前源版本，本轮没有宣称再次执行这两组测试。

## 首轮全量 Maven 失败保留

命令于 00:32:14 开始，00:33:46 结束，退出码 1。计数来自仅复制本轮时间范围内生成的 51 份 `TEST-*.xml`，不是日志的 BUILD 字符串。

15 个失败来自 `AgentToolAuthorizationTest`，均在 HTTP 鉴权层得到 403：

| 测试方法 | 预期/实测 |
|---|---|
| permitsConfirmedOrderButTerminatesReorderWhenItsDishHasBeenUnlisted | 200 / 403 |
| rejectsIdempotentRecoveryWhenTheExistingOrderHasAnotherCartOrAddress | 200 / 403 |
| terminatesReorderWhenAnOriginalDishOptionIsNoLongerAvailable | 400 / 403 |
| refusesMissingTokenAndAnyToolOutsideTheEightNameAllowlist | 404 / 403 |
| autoPaymentRejectsAnOrderNotCreatedByTheCurrentSession | 400 / 403 |
| createCartDraftRejectsMissingExplicitMerchantInsteadOfChoosingFirstOpenMerchant | 400 / 403 |
| createCartDraftRejectsMissingExplicitItemsInsteadOfChoosingFirstListedDish | 400 / 403 |
| consumesConfirmedAuthorizationSoItCannotCreateAnotherCartOrder | 200 / 403 |
| returnsExistingAgentOrderBeforeRecheckingStockOrAuthorization | 200 / 403 |
| thisTaskAutoGrantRejectsCartReplacementAfterUserAuthorizedAnotherCart | 400 / 403 |
| confirmedAuthorizationRejectsCartReplacementAfterUserSawAnotherCart | 400 / 403 |
| createCartDraftUsesExplicitMerchantAndItemPayload | 200 / 403 |
| consumesSingleTaskGrantAfterBoundOrderIsPaidAndRejectsAnotherOrder | 200 / 403 |
| createsOneAutoOrderForRepeatedToolRequestAfterServerSideAuthorization | 200 / 403 |
| derivesIdentityFromServerSessionInsteadOfModelSuppliedUserAndKeepsRequestsIdempotent | 200 / 403 |

另一个失败：`SpringBootToolHttpContractTest.pythonSpringBootToolClientAuthenticatesToTheRealInternalToolController`，其隔离 Python 探针得到 Forbidden，进程退出码为 1，预期 0。

源码核对表明这些旧测试使用固定测试内部令牌，而运行前导入的共享 `AGENT_INTERNAL_TOKEN` 会覆盖 YAML 测试配置。最终 Maven 仅在验收子进程移除 `AGENT_INTERNAL_TOKEN` 与 `JWT_SECRET`，恢复原 YAML 的隔离测试配置，未改变正在运行服务或产品配置。原 16 个失败全部通过，最终 201 项通过；保留首轮 FAIL，不将其当作产品鉴权缺陷或删除旧断言。

最终 Maven 于 00:40:32 开始，00:42:04 结束。结果来自 `.runtime/task8-independent-20261002/surefire-final-full/` 当轮 51 份 XML；新增 `RefundEligibilityTest.progressCanBeReloadedFromOwnedVoucherWithoutCachedReceipt` 包含在 8/8 退款测试中。

优惠券修复后的最新全量于 00:45:30 开始，00:46:58 结束，退出码 0。52 份当轮 XML 保存于 `.runtime/task8-independent-20261002/surefire-coupon-final-full/`；逐份汇总为 209 项，0 失败、0 错误、0 跳过，其中新增 `CouponAvailabilityTest` 8 项通过。此前 200 项首轮失败和 201 项补正后通过的快照没有覆盖。

| 计划相关套件 | 最终全量中的实际计数 |
|---|---|
| RefundEligibilityTest | 8/8 |
| ReviewEligibilityTest | 4/4 |
| CouponComplaintToolTest | 4/4 |
| FaqServiceTest | 5/5 |
| GroupOrderSummaryContractTest | 3/3 |
| AgentGatewayV1ContractTest | 4/4 |
| DevelopmentOrderFailureTest | 3/3 |
| AgentCardEnrichmentTest | 3/3 |

六个计划指定套件合计 28/28，补充卡片和故障两套件后合计 34/34。201 项版本时未另外启动 `-Dtest` 命令；优惠券修复完成并释放目标目录后，已亲自运行独立定向命令，原八套件加 `CouponAvailabilityTest` 八项，共 42/42。

最新独立定向于 00:47:34 开始，00:47:53 结束，退出码 0，Maven 耗时 17.809 秒。9 份当轮 XML 位于 `.runtime/task8-independent-20261002/surefire-coupon-final-targeted/`，实际计数为 Refund 8、Review 4、CouponComplaint 4、FAQ 5、OrderSummary 3、Gateway 4、Card 3、DevelopmentFailure 3、CouponAvailability 8；0 失败、0 错误、0 跳过。

## 实际命令与证据

以下命令在各自工作区运行，日志均重定向至上述 `.runtime`；敏感环境变量由初始化脚本设置，不在命令中填值。

```powershell
. C:/fdz-build/scripts/Initialize-FandaziEnvironment.ps1
$env:MOBILE_PROJECT_ROOT='C:/fdz-build'
$env:JAVA_HOME='D:/software/IntelliJ IDEA 2025.2.4/jbr'
# C:/fdz-build：首轮真实全量，退出码 1；保留环境覆盖导致的失败记录。
& 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' -f backend/pom.xml test
# D:/project/-Demo-master：离线全量，退出码 0，362 项。
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -X utf8 -m pytest tests -q -p no:cacheprovider --basetemp C:/fdz-build/.runtime/task8-independent-20261002/pytest-offline-temp --junitxml=C:/fdz-build/.runtime/task8-independent-20261002/agent-offline.xml --ignore=tests/test_agent_service_live.py --ignore=tests/test_agent_service_e2e_live.py --ignore=tests/test_task2_page_data_live.py --ignore=tests/test_task2_fixtures_live.py --ignore=tests/test_task8_support_live.py
# 同一新 Agent 工作区：显式开启真实在线检查，退出码 0，6 项。
$env:RUN_TASK8_LIVE='1'; $env:RUN_AGENT_LIVE='1'
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -X utf8 -m pytest tests/test_task8_support_live.py tests/test_agent_service_live.py tests/test_agent_service_e2e_live.py -q -p no:cacheprovider --junitxml=C:/fdz-build/.runtime/task8-independent-20261002/agent-live.xml
# 真实 MySQL 隔离任务测试，退出码 0，10 项；不访问 8080。
$env:PYTHONPATH='D:/project/-Demo-master'
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -X utf8 -m pytest C:/fdz-build/docs/verification/task8-service-evidence/test_agent_mysql_acceptance.py -q -p no:cacheprovider --tb=short --junitxml=C:/fdz-build/.runtime/task8-independent-20261002/agent-mysql-independent.xml
# 从 Surefire 依赖和字节码副本启动 JUnit，退出码 1，9 项中 3 个业务断言失败。
& C:/fdz-build/docs/verification/task8-service-evidence/Invoke-CouponSnapshotCheck.ps1
# C:/fdz-build：按券进度补正后的最终全量，退出码 0，201 项；仅变更该子进程环境。
Remove-Item Env:AGENT_INTERNAL_TOKEN -ErrorAction SilentlyContinue
Remove-Item Env:JWT_SECRET -ErrorAction SilentlyContinue
$env:PYTHON_EXECUTABLE='C:/Users/caid0/anaconda3/envs/pinnT/python.exe'
& 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' -f backend/pom.xml test
# D:/project/-Demo-master：另一个子进程重新导入共享环境，重启后在线七项，退出码 0。
. C:/fdz-build/scripts/Initialize-FandaziEnvironment.ps1
$env:MOBILE_PROJECT_ROOT='C:/fdz-build'
$env:RUN_TASK8_LIVE='1'; $env:RUN_AGENT_LIVE='1'
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -X utf8 -m pytest tests/test_task8_support_live.py tests/test_agent_service_live.py tests/test_agent_service_e2e_live.py -q -p no:cacheprovider --junitxml=C:/fdz-build/.runtime/task8-independent-20261002/agent-final-live.xml
# 独立公开 API + 真实 MySQL 只读行数断言，退出码 0，两项。
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -X utf8 -m pytest C:/fdz-build/docs/verification/task8-service-evidence/test_live_write_boundaries.py -q -p no:cacheprovider --tb=short --junitxml=C:/fdz-build/.runtime/task8-independent-20261002/live-write-boundaries.xml
# 最新类独立九项，退出码 0；旧三失败快照保持原样。
& C:/fdz-build/docs/verification/task8-service-evidence/Invoke-CouponSnapshotCheck.ps1 -RunName coupon-javac-fixed
# 另一独立验收子进程按前述隔离环境运行最新全量，退出码 0，209 项。
& 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' -f backend/pom.xml test
# 同样隔离环境下的最新独立定向，退出码 0，42 项；已亲自运行。
& 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' -f backend/pom.xml '-Dtest=RefundEligibilityTest,ReviewEligibilityTest,CouponComplaintToolTest,FaqServiceTest,GroupOrderSummaryContractTest,AgentGatewayV1ContractTest,AgentCardEnrichmentTest,DevelopmentOrderFailureTest,CouponAvailabilityTest' test
# PID6740 就绪后的最新类复验，退出码 0，原九项；本轮未重复 Maven。
& C:/fdz-build/docs/verification/task8-service-evidence/Invoke-CouponSnapshotCheck.ps1 -RunName coupon-javac-live6740
# D:/project/-Demo-master，重新初始化共享环境并显式启用 RUN_TASK8_LIVE/RUN_AGENT_LIVE。
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -X utf8 -m pytest tests/test_task8_support_live.py tests/test_agent_service_live.py tests/test_agent_service_e2e_live.py C:/fdz-build/docs/verification/task8-service-evidence/test_live_write_boundaries.py -q -p no:cacheprovider --tb=short --junitxml=C:/fdz-build/.runtime/task8-independent-20261002/restart-6740/live-regression.xml
# 同一共享环境，真实优惠券投影/归属/新 Agent 卡片，退出码 0，10 项。
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -X utf8 -m pytest C:/fdz-build/docs/verification/task8-service-evidence/test_live_coupon_projection.py -q -s -p no:cacheprovider --tb=short --junitxml=C:/fdz-build/.runtime/task8-independent-20261002/restart-6740/live-coupon.xml
```

附加测试源码位于 `docs/verification/task8-service-evidence/`，均有中文模块/方法/核心流程说明。`pom.xml` 只是后续隔离 Maven 运行入口，本阶段没有执行它；实际优惠券证据来自 javac + Java JUnit 启动器。

## 协调暂停与后续

在线六项于 00:33:17 结束，Maven 首轮于 00:33:46 结束。按券进度接口补正后，在线七项于 00:41:01 结束，最终 Maven 于 00:42:04 结束，额外真实写入边界于 00:42:25 结束。所有本验收启动的测试进程已结束，目标目录已释放。

控制端接受优惠券三项失败并开始产品测试 RED/GREEN 和最小只读投影修复。按已批准Task8归属/有效期要求，控制端接受的最小实现语义为：持久化 USED/EXPIRED 优先；AVAILABLE 过期转 EXPIRED、当前停用转 DISABLED、尚未生效转 NOT_YET_VALID；上海日期含首末日，日期/归属保留，数据库与 17 工具契约不变。DISABLED/NOT_YET_VALID 是控制端为现有文本契约选择的实现值，不代表新的人类逐值确认。原独立九项断言没有修改。

最新产品类的原三失败及六个对照已重新编译并亲自复验通过，未复用旧字节码。201/201 仅证明优惠券修复前版本；包含新增 CouponAvailabilityTest 的最新全量已按当轮 52 份 XML 核对为 209/209，独立定向按当轮 9 份 XML 核对为 42/42。PID6740 就绪后最新类原九项再次通过，支持/售后和优惠券在线共 19/19，运行环境加载与投影已亲自验证。按控制端最新指示未重复 Maven。所有本验收启动的命令已结束，后端目标目录未占用。

| Task8 子范围 | 本阶段判定 |
|---|---|
| 8.1 页面数据/目录/一次错误服务前置 | 服务检查 PASS；UI 绑定未验收 |
| 8.2 退款申请/进度及重进读取 | API/持久化 PASS；Android 待接管 |
| 8.3 评价资格 | Maven 4/4 PASS；评分/成功界面未运行 |
| 8.4 FAQ/投诉 | 服务检查 PASS；Android 待接管 |
| 8.4 优惠券归属/有效期/禁用 | 原三项 FAIL 保留；最新类独立 9/9 再次 PASS，真实运行环境 10/10 PASS，服务阻塞关闭 |
| 8.5 新 Agent 售后 | 离线 362、MySQL 10、真实售后/复合检查 PASS；聊天 UI 待接管 |
| 8.6 错误与网络 | 本人 503→200/隔离 PASS；UI21A/B/C 与模拟器断网未运行 |
| 8.7 整体独立验收 | 服务阶段 PASS、无当前服务阻塞；整体未完成，Android 仍待接管 |
| 8.8 提交/勾选 | 未执行，本验收不提交或勾选 |

Android 独占限制仍有效。本阶段没有运行 Gradle、ADB、Maestro、APK 安装或 Figma 对照；没有 APK 哈希或屏幕通过证据。后续控制端通知冻结和接管后，仍须亲自使用最新 APK 跑售后/异常流程及 Figma 逐屏对照，才能判断整个 Task8。

## Android 券隔离数据准备侧任务

2026-10-02 01:16:47 创建、01:18:15 完成二次真实 API 核验；仅按控制端最新授权准备隔离 fixture，没有接管设备、重跑 Maven 或重启服务。此轮优惠券 API 使用当前 8080，端口检查 PID44128；未把此前 PID6740 的其他在线证据改称本轮结果。

新随机账号 2（持券 owner 1、空列表 observer 1），两者真实登录及 /users/me 通过；八类券状态 8/8、金额 8/8、日期 8/8、归属 8/8、负向归属 2/2，持久化领取事实 8/8 保持只读。真实数据库现保留定义 8、领取行 8，实际删除 0；原 task2 与新 review-retry 清单字节摘要 2/2 不变，不解析或输出其凭据。

凭据、完整随机编号及逐券预期仅存 Git 已忽略的 `C:/fdz-build/.runtime/task8-coupon-ui-fixture.json`。创建、二次只读复核和默认清理预检均已亲自执行成功；新增夹具安全断言 12/12（0.51 秒），其中精准删除分支只在内存 SQLite 验证，没有清理实际 MySQL。创建/精准清理脚本、实际命令、日志路径及留存规则见 `docs/verification/task8-service-evidence/coupon-ui-fixture-evidence.md`。

本轮合成券继续独占留存；最终 Android 验收完成后由本验收 Agent 按随机 ID 与 owner 精准清理，禁止清库。Android 当前仍实现 Agent 独占，整体 Task8 仍未完成，原失败历史和服务阶段结论保持不变。

## 2026-10-02 至 2026-10-03 最终独立综合验收入口

原报告已按字节备份至 `.runtime/task8-independent-final/task8-acceptance-original.md`，以上首次失败及暂停历史保留。本次唯一验收者亲跑冻结索引构建、真实接口、最新 APK Maestro 和 Figma 逐屏对照，详见 [独立综合报告](2026-10-02-task8-independent-acceptance.md)。

**综合结论 FAIL，T8-IND-01 未关闭**：验收复用的购买辅助未隔离套餐，导致原套餐库存71→70；属于本次验收夹具隔离失误，不冒称产品退款缺陷。功能测试通过不替代事实保护；没有回滚旧数据或改基线掩盖失败。服务/网络故障开关已恢复，报告落盘后释放设备、服务与构建操作权；待主 Agent 明确处置并由同一验收者定向复验。两仓未提交、任务未勾选，Task9–12/ASR/核销未实施。

## 2026-10-03 R2 最终审计关闭入口

用户明确决定“保留”事故订单及旧库存71→70，作为审计例外，不删除、不回填、不改失败历史。原唯一验收者已确认R1技术修复有效、产品/测试源未变、两库R1后无新增差异，且仍仅同一已披露事故。**当前整体 PASS，剩余阻塞0，允许主 Agent 精确提交 Task8**；实际提交及回写尚由主 Agent 执行。

详见 [R2最终独立报告](2026-10-03-task8-independent-final.md)。上文R0 FAIL与 [R1技术PASS/处置待确认](2026-10-03-task8-independent-r1.md) 均为原阶段事实，保留不改。原guard对事故前基线仍FAIL，不以覆盖基线伪造通过。危险历史副本 `test_live_write_boundaries_current.py` 只作证据，禁止作为可运行验收入口；今后只精确运行修复后的正式测试。
