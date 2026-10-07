# Task3 最小团购 12 类意图迁移开发记录

日期：2026-10-01。新 Agent 根目录：`D:/project/-Demo-master`。
分支：`codex/group-buying-protocol-service`。
依据：`docs/superpowers/specs/2026-09-29-group-buying-agent-refactor-design.md` 第 9 节及两项目 `AGENTS.md`。

状态：**开发离线自测通过，待独立验收**。本报告不表示整个 Task3 服务接入已完成或验收通过。
当前实现会话没有可调用的独立验收子 Agent 工具，未启动独立验收 Agent；按项目规范保留待验收状态，由父任务另行安排独立运行与复验。
没有勾选计划，没有提交 Git，没有启动 Maven、HTTP 服务或旧手机 `agent-service`。

版本说明：第 1 至 5 节完整保留初次迁移 v1 的 25 条规则、37 条影子样例与 217 项回归历史记录。
当前授权扩充为 v2 的 27 条规则，实际结果见第 6 节；第 7 节保留补充读工具测试的三个失败历史，并记录父侧修复后 65/65 开发复验通过。最终交付仍不表示整个服务在线验证或独立验收通过。

## 1. 迁移行为

当前统一标签为：

```text
greeting/chitchat/store_recommendation/deal_recommendation/store_info/review_query/group_buy_order/coupon/refund_after_sale/complaint/faq/out_of_scope
```

保持三层顺序：审核规范化整句哈希查询 → 一次 TypeSafe `system_one` 的 `is_composite + intent` → 原重型客户端的同 12 类 JSON → `out_of_scope`。
快模型保留业务背景 `state`，背景改为餐饮到店团购；意图与复杂度门槛均为 0.80，候选分差门槛为 0.10。
复杂度按独立目标数量判断；购买、查券并申请退款等单目标中的多个工具或确认步骤不自动构成复合。

模板重写了门店、固定套餐、团购订单/券、优惠券只读、退款申请与待处理进度、投诉、评价和帮助的边界。
未把旧 `dish_recommendation` 的下饭菜等文案改名为套餐；外卖配送、骑手、地址、商家核销执行、优惠券写操作和真实退款到账不属于本期能力。
每类有至少 6 条正例、3 条反例、2 条边界例；模板版本 `group-buying-intent-v1`，Planner 版本 `group-buying-dag-planner-v1`。

第一层当前仅启用 **25 条**逐句检查的最小新版团购文案，覆盖 11 个可本地终结标签，`out_of_scope` 不设规则。
规则版本 `group-buying-local-exact-v1`，规范化仍为 `local-exact-normalization-v1`。
未审核输入、子串/前缀近似、否定、复合、可能有损的清洗、配置异常都继续进入第二层。
Task9 的千级语料扩充没有在本子任务中实施。

完整旧 1760 条、11 类各 160 条规则原样保存在 `D:/project/-Demo-master/config/baselines/local_exact_intents.restaurant-v1.yaml`。
默认加载器不会读取或合并基线；显式将完整旧基线交给当前分类器也会因旧标签整体禁用。
迁移前后文件 SHA-256 完全相同：`C769B2DB0C4EE7DDD2320574752A19147FAB884B4693E89F1AED7F6A400636A5`。

Planner 向模型提供共享 `allowed_intents`；旧主意图在模型调用前被拒绝。
Planner JSON 解析、Validator 的直接计划对象检查、LangGraph 状态恢复均拒绝旧四标签。
当前图映射保留整数状态兼容：问候进入固定回复，9 个业务标签进入业务子图，闲聊与范围外进入聊天；复合结果进入 DAG Planner。
这只是意图入口与协议迁移，确定性售后执行和 17 工具接入由父任务负责。

## 2. 父任务接入接口

新 Agent 原有真实公开接口是：

```python
IntentDecisionOrchestrator.decide(query: str, trace_id: str | None = None) -> IntentDecision
```

当前根项目没有名为 `IntentRouter` 的公开类；不应假定存在 `IntentRouter.classify`。
`DecisionModelAdapter.classify(query)`、`RouterFallbackAdapter.classify(query)` 和 `LocalRuleClassifier.classify(query)` 是各层接口。
生产装配可继续使用 `node.base_node.intentRouterNode._build_orchestrator(audit_repository=...)`，随后调用 `decide`。
只读核对父侧 `service/providers.py` 时，其 `ProductionClassifier.decide` 已调用该接口，本侧未编辑服务文件。

返回 `IntentDecision` 包含 `intent`、`is_composite`、`complexity_confidence`、`confidence`、`scores`、`source`、`fallback_reason`、`execution_mode`、模板/规则版本、清洗元数据、`trace_id` 和 `audit_error`。
`execution_mode` 为 `single_intent` 或 `dag`，结果不含执行计划；审计失败保留路由，仅附带诊断字段。
重型结果按原有策略接受合法结构输出，本次未新增重型 0.80 拒绝策略。

## 3. 实际运行

项目 `.venv` 的 Python 3.12.14 未安装 pytest；实际使用系统 `C:/Users/caid0/anaconda3/python.exe`。
Windows 默认编码与系统临时目录限制通过 `-X utf8` 和本任务独立 `--basetemp` 处理，没有改写环境配置。

测试先出现预期红灯 55 失败、9 通过；迁移首轮 64 通过。最终完整关键回归实际命令为：

```powershell
python -X utf8 -m pytest tests/test_group_buying_intent_migration.py tests/test_intent_template_loader.py tests/test_decision_model_adapter.py tests/test_router_fallback_adapter.py tests/test_intent_orchestrator.py tests/test_intent_policy.py tests/test_intent_local_rules.py tests/test_local_exact_template_loader.py tests/test_local_exact_corpus.py tests/test_local_text_cleaner.py tests/test_intent_router_node.py tests/test_dag_planner.py tests/test_dag_validator.py tests/test_dag_models.py tests/test_dag_nodes.py tests/test_dag_executor.py tests/test_dag_tool_registry.py tests/test_mysql_audit_repository.py tests/test_main_graph_routing.py tests/test_evaluate_intent_experiments.py -q --tb=short -p no:cacheprovider --basetemp=.scratch/task3-regression-final-20261001
```

实际结果：**217 passed in 1.42s**，退出码 0。
覆盖两分类器 12 类输出、旧四标签拒绝、一次双问题调用、阈值边界、复杂度回退、模型异常、最终兜底、审计故障、本地规则禁用、规则清洗、图入口、DAG 解析/校验/恢复/两节点依赖、执行器确认与失败传播以及审计序列化。
执行器和注册表相关测试使用受控工具验证其原有机制，不代表真实 Spring 工具已经接通。

新版规则影子评测实际命令：

```powershell
python -X utf8 tests/evaluate_local_exact_shadow.py --cases D:/project/-Demo-master/tests/data/group_buying_local_exact_shadow_cases.yaml --output D:/project/-Demo-master/tests/results/group_buying_local_exact_shadow_20261001.json
```

实际结果：37/37 符合预期，命中 17/37（45.95%），命中正确率 100%；复合误终结 0、有损误终结 0、规范化冲突 0、规则加载异常无。
p50 0.0092 ms、p95 0.0418 ms 仅是本机合成样例的本地清洗/查询观察，不是模型或在线性能。
首次传相对样例路径时旧评测脚本的 `relative_to(PROJECT_ROOT)` 报错，改用绝对路径重跑成功；历史脚本未修改。
结果文件在新 Agent `tests/results/`，统一实验记录追加到 `docs/experiments/intent-routing-experiments.md` 的 EXP-012。

**未完成在线测量**：未请求真实模型、未连接业务服务、未运行 Android/Maestro，不能给出模型语义准确率、在线耗时或真实退款/投诉闭环结论。

## 4. 本侧全部改动

下列路径以 `D:/project/-Demo-master` 为根，仅列本侧创建或修改的文件，不计父任务并发改动。

| 文件 | 改动 |
|---|---|
| `intent/models.py` | 共享 Literal 与白名单 12 类、独立目标复杂度定义、中文契约说明 |
| `intent/template_loader.py` | 共享团购标签校验与中文输入/规范化/构造说明 |
| `intent/decision_model_adapter.py` | 团购背景 state、原有一次双问题调用与中文协议说明 |
| `intent/router_fallback_adapter.py` | 团购重型提示词和共享复杂度规则，保留严格 JSON 解析 |
| `intent/orchestrator.py` | 真实 decide 接口与三层回退说明，不改原路由/审计策略 |
| `intent/local_exact_template_loader.py` | 支持新版规则版本，保留显式离线基线版本解析 |
| `intent/local_rules.py` | 明确默认最小团购规则与旧基线不合并，整句查询机制不变 |
| `config/intent_templates.yaml` | 12 类团购定义、正反边界例与两模型提示词 |
| `config/local_exact_intents.yaml` | 新版 25 条审核整句与基线/Task9 范围说明 |
| `config/baselines/local_exact_intents.restaurant-v1.yaml` | 新增非激活文件，完整旧规则字节原样保留 |
| `config/dag_planner.yaml` | 团购规划约束与新版本，不写入工具实现 |
| `dag/planner.py` | 主意图校验与 allowed_intents 注入 |
| `dag/validator.py` | 独立检查任务意图白名单，稳定错误码 unknown_intent |
| `node/base_node/intentRouterNode.py` | 当前业务标签映射与中文接口说明 |
| `node/base_node/dagPlannerNode.py` | 状态恢复拒绝旧标签 |
| `tests/test_group_buying_intent_migration.py` | 新增迁移、边界、复合、回退和基线测试 |
| `tests/data/group_buying_local_exact_shadow_cases.yaml` | 新增 37 条团购本地影子样例 |
| `tests/test_intent_template_loader.py` | 当前版本断言与中文场景说明 |
| `tests/test_decision_model_adapter.py` | 当前团购响应标签 |
| `tests/test_router_fallback_adapter.py` | 团购售后场景与合法标签的非法分数测试 |
| `tests/test_intent_orchestrator.py` | 团购标签/售后场景及 0.80 策略 |
| `tests/test_intent_policy.py` | 团购标签、0.80 与分差分支覆盖 |
| `tests/test_intent_local_rules.py` | 新版审核句与规则版本，保留未审核拒绝测试 |
| `tests/test_local_exact_template_loader.py` | 当前最小规则规模与新版本 |
| `tests/test_local_exact_corpus.py` | 当前 25 条覆盖、规范化一致性，不虚报旧千级规模 |
| `tests/test_intent_router_node.py` | 当前 12 类映射与状态断言 |
| `tests/test_dag_planner.py` | 当前团购标签、合成工具与新版引用格式 |
| `tests/test_dag_validator.py` | 合成计划使用当前标签，原校验机制场景保留 |
| `tests/test_dag_models.py` | 当前意图契约标签与版本 |
| `tests/test_dag_nodes.py` | 当前状态及计划标签，原重试/异常场景保留 |
| `tests/test_dag_executor.py` | 合成任务使用当前标签，未编辑执行器源码 |
| `tests/test_main_graph_routing.py` | 当前标签与单目标/复合入口回归 |
| `tests/test_mysql_audit_repository.py` | 当前标签、规则版本与审计阈值 |
| `tests/test_evaluate_intent_experiments.py` | 仅迁移现行 Validator 单测替身标签，历史实验脚本不动 |
| `docs/experiments/intent-routing-experiments.md` | 追加 EXP-012 离线结果及在线未测边界 |

另新增本手机报告 `docs/verification/2026-10-01-task3-intent-migration-development.md`，并产生新离线结果 `D:/project/-Demo-master/tests/results/group_buying_local_exact_shadow_20261001.json`（该目录已被 Git 忽略）。

## 5. 保护边界与待验收项

`decisionNode.py`、`routerNode.py`、`dag/executor.py`、`dag/tool_registry.py` 的迁移前后 SHA-256 一致。
历史实验脚本、历史样例/结果及旧手机 `agent-service` 源码未编辑；父侧 service、Spring 工具、契约与依赖文件未编辑。
父任务并发新增的 service/requirements/接口测试不属于本报告的改动归属。
本子任务的当前图业务入口仍兼容已有子图，不能独立作为新服务的 17 工具完成证据。

独立验收 Agent 需要亲自重跑以上离线命令，核对设计第 9 节及当前分支，再随父任务验证新服务身份、真实模型与售后流程。
本侧没有独立验收通过证据，不勾选实施计划，不宣称整个 Task3 完成。

## 6. 授权追加两条明确问候（v2）

2026-10-01，用户/父侧反馈真实 smoke 中 `你好` 未命中原版审核清单，快模型置信度 0.64 后转重型，观察到约 20 秒耗时。
这不是本会话自行完成的在线实测。本侧在明确授权下只追加 `你好`、`hello` 两条纯问候；`你好请问`、`hello请问` 及其余原 25 条均逐句保留，现共 27 条。
规则版本为 `group-buying-local-exact-v2`，规范化仍为 `local-exact-normalization-v1`；仅增加加载器对新版的支持，整句哈希、三层顺序、背景 `state`、0.80 阈值与分差策略均不变。
旧规则基线仍非激活，未做 Task9 扩充；没有替换或改写本报告前述 v1 历史结果。

语料测试独立列出原 25 条，断言当前索引恰好等于原清单加两条新问候，防止用换句、错标或额外规则冒充扩充。
真实编排器测试预设快模型替身置信度 0.64，验证新问候仍由第一层直接返回 `greeting`、`source=local_rule`，两层模型调用均为零。
`你好呀`、`hello there`、问候加退款/订单请求、否定表达及 URL 有损输入继续进入模型，不允许子串或前缀命中。

影子数据升级为 `group-buying-local-exact-shadow-v2`，保留原 37 条并追加 10 条样例，共 47 条。
实际运行命令如下，EXP-012 也已追加同版本记录：

```powershell
python -X utf8 -m pytest tests/test_local_exact_corpus.py tests/test_intent_local_rules.py tests/test_local_exact_template_loader.py tests/test_group_buying_intent_migration.py -q --tb=short -p no:cacheprovider --basetemp=.scratch/task3-greeting-v2-red-20261001
python -X utf8 -m pytest tests/test_local_exact_corpus.py tests/test_intent_local_rules.py tests/test_local_exact_template_loader.py tests/test_group_buying_intent_migration.py -q --tb=short -p no:cacheprovider --basetemp=.scratch/task3-greeting-v2-green-20261001
python -X utf8 -m pytest tests/test_group_buying_intent_migration.py tests/test_intent_template_loader.py tests/test_decision_model_adapter.py tests/test_router_fallback_adapter.py tests/test_intent_orchestrator.py tests/test_intent_policy.py tests/test_intent_local_rules.py tests/test_local_exact_template_loader.py tests/test_local_exact_corpus.py tests/test_local_text_cleaner.py tests/test_intent_router_node.py tests/test_mysql_audit_repository.py -q --tb=short -p no:cacheprovider --basetemp=.scratch/task3-greeting-v2-intent-regression-20261001
python -X utf8 tests/evaluate_local_exact_shadow.py --cases D:/project/-Demo-master/tests/data/group_buying_local_exact_shadow_cases.yaml --output D:/project/-Demo-master/tests/results/group_buying_local_exact_shadow_v2_20261001.json
```

实际结果：预期红灯 **28 失败、88 通过**，修复后 **116 通过（0.89 秒）**；意图与审计回归 **187 通过（1.29 秒）**，两轮通过命令退出码均为 0。
v2 影子 **47/47 符合预期**，本地命中 **21/47（44.68%）**、命中正确率 100%；复合误终结 0、有损误终结 0、规范化冲突 0、加载异常无。
p50 0.0100 ms、p95 0.0436 ms 仅为本机本地查询观察。样例规模与内容改变，不将 v1 的 17/37 和 v2 的 21/47 直接作为线上覆盖率提升比较。

仅生成新结果 `D:/project/-Demo-master/tests/results/group_buying_local_exact_shadow_v2_20261001.json`，没有覆盖原 v1 结果。
原 `group_buying_local_exact_shadow_20261001.json` 扩充前后 SHA-256 均为 `35259E071C5AEE53AE30FBA6B2F26C2DFFAD61D632BBE121BFF82170DB898F5E`。
旧完整规则基线 SHA-256 仍为 `C769B2DB0C4EE7DDD2320574752A19147FAB884B4693E89F1AED7F6A400636A5`。

本次扩充全部手工改动如下，不包含并发父侧改动：

| 文件 | 本次追加 |
|---|---|
| `D:/project/-Demo-master/config/local_exact_intents.yaml` | 两条问候、27 条范围说明及 v2 版本 |
| `D:/project/-Demo-master/intent/local_exact_template_loader.py` | 支持 v2，保留 v1 和旧基线解析能力 |
| `D:/project/-Demo-master/tests/test_local_exact_corpus.py` | 27 条断言及原 25 条逐句保留检查 |
| `D:/project/-Demo-master/tests/test_intent_local_rules.py` | 纯问候、安全规范化、近似/业务追加/有损拒绝 |
| `D:/project/-Demo-master/tests/test_local_exact_template_loader.py` | v1/v2 加载和当前规模断言 |
| `D:/project/-Demo-master/tests/test_group_buying_intent_migration.py` | 低置信度替身下零模型调用及拒绝后进入模型 |
| `D:/project/-Demo-master/tests/data/group_buying_local_exact_shadow_cases.yaml` | 原 37 条保留，追加 10 条并标记 v2 |
| `D:/project/-Demo-master/docs/experiments/intent-routing-experiments.md` | EXP-012 追加 v2 实际命令、结果和历史保留说明 |
| 本手机报告 | 保留初版记录，追加本节与补充工具开发测试结果 |

当前仅为意图扩充开发离线自测通过，**未完成在线测量、待独立验收**；未复跑真实 smoke，不能声称已在线验证消除 20 秒延迟。
本侧未编辑 service、Spring、执行器、ToolRegistry、历史实验脚本/结果或旧手机 `agent-service`；未勾选计划、未提交、未启动 Maven/服务。

## 7. 继续补充读工具开发测试

本节前半部分的 62 通过、3 失败是父侧修复前的实际历史结果，保留原始问题与证据；修复后的最终开发复验见第 7.1 节。

独立写范围只新增 `D:/project/-Demo-master/tests/test_agent_service_reads.py` 和 `D:/project/-Demo-master/tests/test_spring_tool_client.py`，本次继续重跑但未修改服务实现。
前者使用真实 `RunEngine/create_app/SpringToolClient`，仅模型边界、状态仓库和 Spring HTTP 使用替身；后者直接使用真实客户端、契约与 `MockTransport`。
这是离线开发测试，不是独立验收或在线 Spring/数据库结果。

```powershell
python -X utf8 -m pytest tests/test_agent_service_reads.py tests/test_spring_tool_client.py tests/test_agent_service_api.py tests/test_agent_service_dag.py -q --tb=short -p no:cacheprovider --basetemp=.scratch/task3-greeting-v2-read-client-20261001
```

实际结果：**62 通过、3 失败（7.99 秒）**，退出码 1，另有一条 Starlette/AnyIO 弃用警告。
读工作流 7 项全部通过；Spring 客户端 50 项中 47 项通过、3 项失败；现有基础 6 项和 DAG 2 项均通过。
订单 `operation=query` 不调用购买准备；门店/评价/套餐缺 `storeId`、门店推荐缺 `cityCode` 的追问及重建引擎恢复、`category` 正确字段均通过，当前不存在仅由追问键契约未融合造成的失败。
17 工具白名单、内部 JWT `sub`、可信身份、派生幂等键、非法输入、JSON/信封异常与传输故障下的写结果不确定性均通过。

三个失败来自 `service/spring_client.py:44`：合法外层信封中的订单缺字段、非法退款状态、订单金额非整数分触发 `contracts.tool_output` 的原始 `ValueError`，未包装为 `ToolFailure`。
失败用例为 `tests/test_spring_tool_client.py::test_invalid_business_data_uses_tool_failure_and_preserves_write_uncertainty` 的三个参数分支，实际错误路径依次为 `orders/0`、`status`、`totalCents`。
需父侧将业务输出校验异常转为稳定 `DEPENDENCY_UNAVAILABLE`；写调用需保留 `uncertain=True`、`retryable=False`，只读调用按预期可重试。
本侧保留失败，不跳过、不标记预期失败、不修改实现；父侧统一回归与独立验收另行执行。

### 7.1 父侧修复融合后最终开发复验

父侧已将 `SpringToolClient.invoke` 的业务输出校验异常包装为 `ToolFailure`，保留写结果不确定性和禁止盲目重试的行为。
本侧只读核对该变更并重跑既有测试，没有修改服务、扩大规则/测试场景或进行独立验收；以上三个失败不抹除，以便复核修复前后的证据。

```powershell
python -X utf8 -m pytest tests/test_agent_service_reads.py tests/test_spring_tool_client.py tests/test_agent_service_api.py tests/test_agent_service_dag.py -q --tb=short -p no:cacheprovider --basetemp=.scratch/task3-v2-handoff-read-client-20261001
python -X utf8 -m pytest tests/test_group_buying_intent_migration.py tests/test_intent_template_loader.py tests/test_decision_model_adapter.py tests/test_router_fallback_adapter.py tests/test_intent_orchestrator.py tests/test_intent_policy.py tests/test_intent_local_rules.py tests/test_local_exact_template_loader.py tests/test_local_exact_corpus.py tests/test_local_text_cleaner.py tests/test_intent_router_node.py tests/test_mysql_audit_repository.py -q --tb=short -p no:cacheprovider --basetemp=.scratch/task3-v2-handoff-intent-20261001
```

实际结果：工具/服务补充集 **65 passed in 6.59s**；意图与审计集 **187 passed in 1.32s**，均退出码 0，两组不同测试共 **252/252 通过**。
工具组仍有一条 Starlette/AnyIO 弃用警告，无失败；其中新增只读 7 项和 Spring 客户端 50 项全部通过，原基础 6 项及 DAG 2 项通过。
订单查询不准备购买、门店/城市追问恢复、套餐门店约束、品类字段、17 白名单、JWT 身份、派生幂等键、非法响应和写结果不确定性均得到离线覆盖。

收尾只读取并核对已保存的 v2 影子结果：47/47 符合预期、命中 21/47、复合/有损误终结均为 0；没有覆盖任何影子 JSON。
v1 结果 SHA-256 仍为 `35259E071C5AEE53AE30FBA6B2F26C2DFFAD61D632BBE121BFF82170DB898F5E`，旧基线仍为 `C769B2DB0C4EE7DDD2320574752A19147FAB884B4693E89F1AED7F6A400636A5`。

本次收尾手工只更新本报告和 `D:/project/-Demo-master/docs/experiments/intent-routing-experiments.md`；规则/测试 v2 的其余文件见第 6 节清单，补充测试文件见第 7 节开头，未新增文件或继续扩范围。
最终交付状态：**本范围离线开发自测通过，在线 smoke 与独立验收待父侧执行**。不代表真实 Spring、模型、数据库或 Android 流程已经通过，本侧未启动服务/Maven、未提交、未勾选计划。
