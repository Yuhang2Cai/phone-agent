# Task3 独立验收报告

结论：**PASS**。本轮由全新独立验收 Agent 亲自检查源码、执行测试及真实接口，并核对真实 MySQL 状态。最终没有剩余 Task3 阻塞。此结论只覆盖 Task3，不代表整个重构完成；未勾选计划、未提交、未修改实现、未启动 Task2 或 Task4–12。

验收日期：2026-10-01，Asia/Shanghai。执行区间约 22:37–22:52。

## 依据与环境

- 已读双方根目录 `AGENTS.md`，手机实施计划 Task3 和 `docs/superpowers/specs/2026-09-29-group-buying-agent-refactor-design.md`。
- 新 Agent 源码：`D:/project/-Demo-master`，HEAD `878c109e90e7b0490198d0f84c569b7cd1a30368`，存在既有未提交实现。
- 手机源码：`C:/Users/caid0/Documents/ChatGPT/手机agent/.worktrees/fandazi-ai`，HEAD `07b865a8d4f9cc333e0dc32f465f0d358b37ba5e`，存在既有未提交实现。
- `C:/fdz-build` 已核实为上述手机源码的 Junction，构建和接口验收均使用同一源码。
- Python：`C:/Users/caid0/anaconda3/envs/pinnT/python.exe -X utf8`，实际版本 3.10.19。
- Maven：`D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd`；`JAVA_HOME=D:/software/IntelliJ IDEA 2025.2.4/jbr`，测试输出实际 Java 21.0.8。
- MySQL 实连版本 8.0.44；业务库 `fandazi_groupbuy_dev`，Agent 库 `dianping_intent`。验收直接执行 `SELECT DATABASE(), VERSION()` 和本人场景的只读查询；没有以 H2 测试代替真实数据库证据。
- Spring：8080，初始 PID 43968，最终 PID 44312；实际命令行入口 `com.fandazi.FandaziApplication`。运行日志确认调用 URL 为 `http://127.0.0.1:8091`。
- Agent：8091，初始 PID 44156，最终 PID 47440；实际命令行入口 `service.app:create_app`，可执行文件为 pinnT Python。
- 实测 `GET /internal/v1/health` 返回 `service=group-buying-agent`、`schemaVersion=1.1`、`sourceRoot=D:/project/-Demo-master`、`entryPoint=service.app:create_app`、`buildVersion=878c109-working-tree`。
- 真实账号通过注册接口随机建立，密码运行期随机生成；令牌、共享密钥、确认凭证、数据库密码及完整请求未写入本报告或脱敏证据。

## 实际命令与计数

Python 命令的工作目录均为 `D:/project/-Demo-master`；设置 `MOBILE_PROJECT_ROOT=C:/fdz-build`。live 和新增真实验收命令先点加载 `C:/fdz-build/scripts/Initialize-FandaziEnvironment.ps1`，不打印其密钥；live 设置 `RUN_AGENT_LIVE=1`。所有 Python HTTP 客户端均使用 `trust_env=False`；PowerShell 探针设置 `HttpClientHandler.UseProxy=false`。

```powershell
# 离线完整 tests 目录，真实接口文件另行启用运行。
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -X utf8 -m pytest tests --ignore=tests/test_agent_service_live.py --ignore=tests/test_agent_service_e2e_live.py --basetemp=.scratch/task3-independent-20261001-a71c -q
# 346 passed in 14.74s，exit 0。

# 两个真实入口文件，依赖当前 Spring、MySQL、新 Agent 和实际模型。
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -X utf8 -m pytest tests/test_agent_service_live.py tests/test_agent_service_e2e_live.py --basetemp=.scratch/task3-independent-live-20261001-b82d -q --tb=short
# 5 passed in 34.82s，exit 0。

# 独立新增真实验收：契约、JWT、购买、双服务重启、身份与取消。
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -X utf8 -m pytest .scratch/task3-independent-20261001/test_task3_acceptance.py --basetemp=.scratch/task3-independent-gate-20261001-f26b -q --tb=short --junitxml=.scratch/task3-independent-20261001/final-junit.xml
# 5 passed in 31.23s，exit 0。
```

Maven 工作目录为 `C:/fdz-build/backend`；使用上述 Maven 路径和 JAVA_HOME。三次命令串行运行，没有并发 Maven。

```powershell
& 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' '-Dtest=AgentGatewayV1ContractTest,AgentProtocolBoundaryTest,AgentRunIdentityTest,InternalV1ToolIntegrationTest,AgentToolFieldBoundaryTest,Task1IndependentAcceptanceTest,Task1NestedPublicEventAcceptanceTest,Task1PublicToolAuditAcceptanceTest,AgentContractSchemaTest,AgentToolAuthorizationTest' test
# 63 tests，0 failures/errors/skipped，BUILD SUCCESS，exit 0，25.932s。

& 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' -q '-Dtest=AgentGatewayTest' test
# 6 tests，0 failures/errors/skipped，exit 0。

& 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' -q '-Dtest=Task3StreamInterruptionAcceptanceTest' test
# 独立新增 3 tests，0 failures/errors/skipped，exit 0。
```

最终通过计数：Python **346+5+5=356**，Maven **63+6+3=72**。父侧的 283/5 自测数字没有计入本轮独立证据。

## 需求门禁

| Task3 检查项 | 结论 | 实际证据 |
|---|---|---|
| 新运行来源、入口与健康版本 | PASS | 健康根目录完整相等，核对 Python 进程命令行；Spring 日志明确访问 8091。重启后再次核对入口及目录。 |
| 旧服务保留且不参与当前运行 | PASS | 旧 `agent-service/src/fandazi_agent` 源码保留；旧入口有 `/internal/agent/run`、1.0 V1 流和 `/internal/tools` 客户端。新 `service/` 实现使用 1.1、V1 工具边界，启动脚本指向新仓库，无失败回退旧服务分支。 |
| 权威 1.1 与 17 工具 | PASS | 两份契约原始字节与手机 authority 相等，manifest 摘要相等，17 个工具名独立列出比对。准备工具与写工具边界由 Python/Maven 测试验证。 |
| 12 类最小迁移与分类边界 | PASS | 12 类配置、类型、两层适配器、Planner/Validator、工作流名称一致。346 项离线测试包含旧标签拒绝、找店/找套餐、订单/售后、复合与单目标多工具、非法输出及最终兜底。背景 state 保留背景加用户原文的机制，阈值为 0.80。 |
| 本地高精度整句规则 | PASS | 激活配置为 27 条；规范化冲突、未审核子串/前缀/否定/复合、有损清洗和配置异常回到模型的测试实际通过；历史 1760 条清单保留但不激活。 |
| 内部 JWT 与请求结构 | PASS | 无令牌、过期、缺 exp、超过 120 秒窗口、HS512 均拒绝 401；主体/body 不符和已有任务跨主体/跨会话拒绝 403；多余旧 text 字段拒绝 422。 |
| NDJSON 事件身份及顺序 | PASS | 真实问候共 5 事件，sequence 为 0–4，eventId 唯一、runId/traceId 相等；逐事件执行权威 Schema 校验。 |
| Spring 暂停与确认映射 | PASS | 真实退款、投诉与购买分别返回 NEEDS_INPUT/AWAITING_CONFIRMATION；缺输入正常暂停，没有伪成功或 FAILED；结构化槽位、候选卡与摘要经真实客户端/Maven 核对。 |
| 真实业务工具及复合 DAG | PASS | live 实际注册、查订单、创建支付夹具、选券补原因、准备确认后申请退款、准备后投诉；复合只读请求由实际 Planner 执行 `list_user_orders` 和 `query_faq`，isComposite=true。退款结果为待处理，不声称到账。 |
| 创建和支付分别确认 | PASS | 创建确认前本人订单数为 0；“再考虑”不调用工具。首次确认仅 `create_group_buy_order`，订单 PENDING_PAYMENT；继续等待 `pay_mock_order` 的单独确认。最终支付轮只调用 `pay_mock_order`，本人唯一订单变 PAID，金额等于原确认报价。 |
| 取消与禁止提前写入 | PASS | 取消购买支付后订单仍 PENDING_PAYMENT；取消投诉后工单数为 0；另一用例取消购买确认后订单数为 0。各取消轮无业务工具、权威 run_completed.status=cancelled，Spring/Agent 两库状态均 cancelled。 |
| 身份隔离与客户端伪造 | PASS | 隔离账号不能恢复他人会话或读 SSE，实际均 HTTP 400 拒绝；新账号订单列表为空。客户端 body 中伪造 userId/runId/context.confirmationAccepted 不改变身份也不执行创建。内部跨主体/会话拒绝 403。 |
| 重启后恢复 | PASS | 最终完整用例将 Spring PID 45796、Agent PID 47468 重启为 44312、47440；操作前核对确切端口、PID、命令行。真实 MySQL 中付款确认和投诉补输入状态保留，恢复沿用原 runId、每次 traceId 更新；未重新创建/重复支付，投诉仍须确认且可取消。 |
| SSE 重放与凭证保护 | PASS | 实际重放购买会话到数据库当时最后事件，共 36 条，id 严格递增；递归检查公开 JSON/NDJSON/SSE 无确认凭证字段或内部密钥。私有支付凭证只在 Agent 库恢复快照中存在。 |
| 中断、非法流与故障输出 | PASS | 新增真实本机 HTTP 测试发送 run_started 后关流及半截 JSON，生产客户端均抛 AgentServiceUnavailableException；不会构造成功。网关故障/SSE 的 6 项测试通过。 |
| 历史基线保护 | PASS | `decisionNode.py`、`routerNode.py` 与 config/baselines 本轮未修改，指定路径 git diff 无改动。验收仅新增测试、报告和脱敏结果。 |

契约 SHA-256：

- `agent-protocol-v1.yaml`：`a6f6a9f4544c37105b77bee3b9dac357febec0a1a91e010717870bc592cff2d5`。
- `agent-tools-v1.yaml`：`a17702e36789e05ba1dd1c62d7c1a7ff923b019e69d0741cbde3d269aa35b954`。

## 失败记录与判定更正

以下是实际发生的验收脚本问题，保留原失败证据，不删除或冒充首次通过；没有据此修改实现。

1. 初次购买补充断言要求待支付订单零券，单独复跑为 1 failed。实体实际上在创建订单时建立券记录。该断言超出 Task3 的服务确认边界；改为验证未提前创建、未支付、未调用支付工具、未支付券不可退款。最终这些断言全部通过。券何时生成及页面展示属于后续领域/购买任务，本报告不验收或修改该策略。
2. 第一次双服务重启后的验收脚本等待继承给 Windows 后台子进程的管道，测试未正常结束；只停止了本轮 pytest PID 44504，服务未误杀。将测试子进程输出改为 DEVNULL 后，完整重启恢复流程通过。此前被中止运行不计通过。
3. 第二次新增套件为 2 passed/3 failed，分别是购买取消、投诉取消和本人取消。原断言直接要求旧 DTO 返回 CANCELLED，但生产 `AgentResult.Status.publicValue()` 在集中兼容出口返回 COMPLETED，同时 `nextAction=cancelled`，权威事件/数据库仍是 cancelled。错误说明“取消触发了写操作”“取消投诉仍创建工单”是复合断言的过宽文字，不能证明真实写入。本轮补充实际取消事件、两库状态、工具列表及业务计数断言后，最终套件为 5 passed；新增 Java 测试另验证集中兼容行为。结论是测试误读旧 DTO，并非已发现取消失效。

最终 **阻塞项：无**。父侧曾收到三项取消门禁的临时失败通报，本报告以完成定位和复验后的结果更新该临时状态。

## 未测限制与范围

- 本轮不是 12 类模型语义准确率、并发、压力或 P50/P95 实验；离线双分类器用例只证明解析/路由边界。真实 live 确实调用分类、槽位模型与 Planner，但不据此宣称全面模型质量或性能达标。
- 完整千级审核语料和均衡在线评测属 Task9，当前 27 条不是本次阻塞。
- Maven 使用 H2 的隔离业务测试与真实 MySQL 的双服务测试分别记录，没有运行整个项目的全部 Maven 测试，也不据此声称全项目回归完成。
- 中断的精确负面场景使用本机可控 HTTP 服务检验生产客户端；未对真实写操作故意制造未知提交结果、确认超时或大规模并发。相关不确定结果和错误边界在已运行的离线工具/DAG 测试中覆盖，不宣称额外在线故障注入通过。
- Task3 没有 Android 改动，不安装 APK、不运行 Maestro、不假称 Android/Figma 页面验收通过。
- Task2 金额/时间/资源标识和页面数据迁移、Task4–12 的 UI、领域及最终验收不计入本次门禁；未开展这些任务。
- 未修实现、未勾计划、未提交。本轮新增独立 Java 测试需要由父侧决定后续归档/提交；无需以此再次扩大验收范围。

## 证据位置

手机仓库 `docs/verification/task3-independent-20261001-evidence/` 已保存：

- `safe-evidence.json`：最终来源、数据库、JWT、购买/取消、重启及隔离计数，不含敏感值。
- `final-junit.xml`：新增真实验收最终 5/5。
- `junit.xml`：取消旧 DTO 误判时的 2 PASS/3 FAIL 原记录。
- `purchase-junit.xml`：零券假设的 1 FAIL 原记录。
- `TEST-com.fandazi.feature.agent.*.xml`：本轮实际运行的 12 类 Maven 报告，共 72 项。

新增独立 Python 源码：`D:/project/-Demo-master/.scratch/task3-independent-20261001/test_task3_acceptance.py`。重启用例只允许测试中明确列出的当时 PID；再次运行前必须核实并更新测试目标 PID，禁止直接复用过期 PID 或任意杀进程。

新增独立 Java 源码：手机 `backend/src/test/java/com/fandazi/feature/agent/Task3StreamInterruptionAcceptanceTest.java`。

本轮原始运行输出见对话工具记录；最后两次 Maven 的本机临时日志为 `D:/project/-Demo-master/.scratch/task3-independent-20261001/maven-gateway-safe-source.log` 和 `maven-stream-safe-source.log`。报告未复制运行目录中的共享秘密文件或包含完整业务事件的服务日志。

独立门禁：**Task3 PASS，可以由父侧按用户既定顺序决定进入 Task2；本验收 Agent 本身不开始下一任务。**
