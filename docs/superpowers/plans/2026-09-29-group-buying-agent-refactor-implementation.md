# 饭搭子 AI 团购化与独立 Agent 服务重构 Implementation Plan

> 执行方式：主 Agent 逐任务开发、自测、修复、文档和提交；每项自测后只启动一名独立验收 Agent，失败复验复用原人。复选框不代替独立报告；任务完成需独立通过及拟提交源闭合。UI 不采用 TDD。

**Goal:** 将手机项目重构为餐饮到店团购 App，并用 `D:/project/-Demo-master` 的新 Agent 服务替换旧 Agent；核销功能本期暂缓。

**Architecture:** Android 只连接 Spring Boot；Spring 提供可信身份、业务接口、确认和白名单工具；新 Agent 负责意图、确定性工作流、DAG 与后续记忆。Task1/3/2及Task4–8已独立验收并提交；2026-10-03 用户追加授权连续执行 Task9 → Task10 → Task11 → Task12。端侧离线 ASR 和核销延期，本期 AI 使用文字输入。

**当前授权（2026-10-03）:** 本轮主 Agent 连续实施 Task9–12；每项开发自测后一名独立验收者，失败复用原验收者，只有验收、精确源码闭合与提交完成才进入下一项。下文“至 Task8 / 仅计划 / 本轮结束”均为此前轮次的历史范围，不限制这次追加授权。原库存事故及用户“保留”的审计例外保持不变，不能改历史失败为通过。

**Tech Stack:** Android XML/MVVM/OkHttp/Gson；Spring Boot/Java 17/JPA/Flyway/MySQL；Python/LangGraph/FastAPI/MySQL/Chroma；JUnit/pytest/Robolectric/MockWebServer/Maestro。

**Spec:** `docs/superpowers/specs/2026-09-29-group-buying-agent-refactor-design.md`。

**UI Spec:** `D:/project/-Demo-master/docs/ui-design/ui-design-spec.md`。

**Figma:** `https://www.figma.com/design/FxsWKGs10z7BL1MhFAOqFN`。

**修订日期:** 2026-10-02。历史记录见 `docs/verification/group-buying-refactor-history-2026-10-01.md`。

**暂停更新（2026-10-02）:** 用户要求暂停 Task8 并保存进度，原因是计划执行顺序需要重新讨论。实现与验收 Agent 均已停止新操作；未提交、未勾选 Task8 完成、不自动进入后续任务。本次只更新状态，未调整原任务顺序。恢复检查点：`D:/project/-Demo-master/docs/verification/2026-10-02-task8-paused.md`。

**后续顺序与延期补正（2026-10-02）:** 以下按最新讨论统一为 Task4 → Task5 → Task6 → Task7 → Task8 → Task9 → Task10 → Task11 → Task12。上段暂停记录保留为当时事实，不再作为待定的后续顺序。端侧离线 ASR 按用户最新要求延期，不新增本期实施或验收步骤。本轮仅修改 spec 与计划，任务执行仍暂停，下一次须用户明确启动 Task4。

**执行恢复（2026-10-02）:** 用户已明确授权从 Task4 连续依次执行至 Task12。现开始 Task4，每项独立验收并提交后自动进入下一项，不再要求重复确认“继续”。此前文档修改和 Task8 暂停仍是历史事实；Task8 依旧等 Task4–7 完成后才恢复。执行证据与检查点逐项保存，ASR 和核销仍延期。

**最新执行范围（2026-10-02）:** 用户回复“y验收完不要停 一直完成到task8”。前述Task4–12为历史授权，本轮从Task5检查点连续执行Task5→Task6→Task7→Task8，每项独立验收通过后自动进入下一项，Task8通过后结束本轮，不启动Task9–12。该回复承接控制端两项确认：现有九个AI聊天Frame作为基线并修复已发现必要设计问题，以及公开事件标识的最小桥接透传，已获继续授权，不再等待同一确认。

**执行规则更新（2026-10-02）:** 本轮用户要求减少交接、探索与无理由重复测试。Task7原Lorentz已完成R3独立验收，需求、质量、运行和拟提交源码闭合均PASS；手机93b97a2、Agent d9fc396已精确提交。Task8由主Agent顺序实施8.1–8.8，最后一名独立验收综合检查；失败主Agent修复，原验收者定向复验。Task9–12本轮只完善下列实施门槛，不实施。旧暂停和旧分派均为历史事实。

## 1. 当前状态与推进顺序

| 任务 | 当前状态 | 当前剩余工作 |
|---|---|---|
| Task1 契约 | 1.1 补正已独立 PASS 并提交 | 后续任务沿用严格契约，新增字段同步双仓 |
| Task2 领域与种子 | 独立 PASS 并提交，阻塞项 0 | 手机 d6c6441 / Agent 8265f08；图片加载失败与旧卡片错误文案在 Task8 复核 |
| Task3 新 Agent 服务 | 新入口、12 类、DAG 与工具桥接已独立 PASS 并提交 | 后续 UI 验收使用新服务，不复用旧服务结论 |
| Task4 浏览推荐 | 独立整体 PASS 并提交 | 手机8d1870d / Agent88c386e；Maven213、Android147、真实在线3、四条Maestro及六页Figma；历史失败和非阻塞关注保留 |
| Task5 AI 页面 | 独立R1整体PASS，旧确认卡P1已关闭；ASR延期 | Jason亲跑64项、最新APK11流程、原事件11配对、游客禁发与全离线恢复；原R0失败保留，范围提交后直接Task6 |
| Task6 购买支付 | 独立R1整体PASS | Tesla亲跑全量217、最新APK真实购买/三页401/断网/过期/变价/历史按钮；原失败与价格审计限制保留 |
| Task7 券查询 | Lorentz R3 独立通过并精确提交 | 手机93b97a2、Agent d9fc396；必要源闭合已验证，核销延期 |
| Task8 售后 | R2独立整体PASS、阻塞0，源码闭合并精确提交 | 手机5476e4a、Agent d3cabc3；1项用户确认留存的库存事故例外，原失败不改。本轮结束 |
| Task9 意图语料 | 独立开发/测量 PASS，Agent 163e8b5 | 1120条、意图116/120、复杂度46/48；真人审批为生产门槛 |
| Task10 记忆 | 独立R2 PASS并提交 | Agent c76645a、手机c6748f5；授权政策/MySQL/Chroma/管理入口完成 |
| Task11 旧路径退役 | 独立PASS并提交 | 手机07545dc、Agent563f3a6；V14非破坏性登记，旧代码/事实保留 |
| Task12 最终验收 | 开发与自测中，待独立验收 | 隔离部署/评测/全链验证；未测与首次失败保留，不提前勾选 |

以上区分历史开发进度与当前验收状态，不撤销历史证据，也不将旧服务自测换算为新服务通过。

此前授权顺序：Task1 协议补正 → Task3 新服务纠偏与最小团购意图迁移 → Task2 页面字段准备 → Task8 分步收尾；前三项已独立验收并提交，Task8 现已暂停。此顺序仅为历史，不再授权跳过 Task4–7。后续依次为 Task4 → Task5 → Task6 → Task7 → Task8 → Task9 → Task10 → Task11 → Task12，每项独立通过、回写并提交后进入下一项。既有实现复用，不推倒重做；Task8 的 AI 子步骤依赖 Task5 公共聊天状态，购买与本人券查询依赖 Task6/7 的已验收路径。

## 2. 全局约束

- `[手机]` 指 `C:/Users/caid0/Documents/ChatGPT/手机agent/.worktrees/fandazi-ai`；`[Agent]` 指 `D:/project/-Demo-master`。
- `C:/fdz-build` 是手机工作区的 junction，不能当作另一份源码同步或覆盖；构建与验收记录解析后的路径和版本。
- 代码、测试、配置新增或修改均配详细中文注释。用户密钥、密码和真实数据不进入源码或提交。
- 不改 Agent 的 decisionNode.py、routerNode.py 和历史实验基线。
- 不修改已执行 Flyway 迁移；V8 已绑定评价订单，后续编号从执行时实际最大版本之后追加。
- Android 业务数据只能取 Spring 接口；失败显示错误与重试，不用静态业务数据兜底。
- 金额协议使用整数分，数据库目标为 DECIMAL，由 Spring 严格转换；目标时间 UTC ISO-8601，用户页面转换为 Asia/Shanghai；价格、资格、归属和确认均由后端权威校验。
- UI 使用已确认 Figma，允许轻微尺寸、间距、字体渲染和同色系颜色差异；不做逐像素一致性验收。
- UI 阻塞项是缺少核心内容、明显布局错位、不可操作、文字重叠溢出、错误导航/图标/选中态和虚假业务结果。
- 底部固定首页、AI助手、订单、我的四图标；AI 使用同一个聊天路由，收到结果不自动跳转。
- 核销暂缓。评价使用后端开发夹具，保持资格规则，Android 不提供假核销。
- 原计划重构阶段只修改文档；当前实际执行以任务复选框及独立报告为准，未勾选项不能视作验收完成。
- 每次只推进一个任务或明确的前置补正；无需每个细小 UI 修改都重跑全量测试，定向自测后在收尾运行一次适用全量回归。
- 发现其他任务缺陷先记录所属任务与影响；当前范围内的阻塞依赖由主Agent记录最小补正及复验，不静默跨任务扩展或跳过前置。改变业务范围或破坏性操作仍须明确授权。
- 端侧离线 ASR 延期，不要求模型资源、录音、离线识别、语音回填或语音 Maestro 在本期通过；系统识别服务和模拟回填不能写成端侧模型已部署。以后恢复时先补 spec、Figma 和独立验收方案。
- 所有 Agent 实验写入 `[Agent] docs/experiments/intent-routing-experiments.md`；任务证据写入手机 `docs/verification/`。
- 实现、自测、独立验收、提交分别记录。独立 Agent 亲自运行适用检查；未运行、失败或证据不足不能勾选验收。
- 保留已有未提交改动；提交仅包含已核对的任务文件。
- 主 Agent负责开发/修复/自测/文档/清单/提交，不再逐Task派实现者；每Task一名独立验收，失败原人复验。额外Agent仅限独立无冲突且可并行省时的产出，派发前说明主Agent同时工作。
- 验收冻结覆盖源码/配置/文档，设备服务独占；待结论并释放后再改。未受影响且版本/配置/环境一致的证据可复用，记录理由；首次失败、跳过、受阻与未运行保留单列。

## 3. 统一验收与环境约定

### 3.1 环境检查

开发自测前记录工作区、提交/改动版本、8080 后端与配置的新 Agent URL、服务健康标识、数据库名称和模拟器设备。端口返回 200 之外，还要确认 Agent 入口来自新项目。

每轮 Android 实施/独立验收按 AGENTS.md 卸载旧 com.fandazi.app 和干扰应用 com.dirror.music，再安装本次 debug APK，记录 APK 哈希与安装时间。后续同轮场景复用该 APK；模拟器恢复旧快照或 APK 更新时重新核对版本。

账号与状态通过后端开发夹具或真实注册接口准备，凭据从环境变量读取。每个场景说明订单和券的初始状态，不依赖上轮执行留下的已退款/已评价数据。

### 3.2 报告要求

每个任务报告包含：需求项、实际命令、环境/版本、结果、截图或日志、阻塞项、非阻塞视觉建议。后端字段值可以不同于 Figma 示例；照片内容不同、少量尺寸或色值差异可以通过，字段来源与交互仍需验证。

全量测试失败需逐项列名、根因、所属任务和修复/退役依据。不可只写“既有失败”，也不可通过删除有效断言获得通过。

普通页面/API 验证与 AI 验证分别标明服务来源；旧 Agent 的历史结果只作为迁移参考。报告以 PASS/FAIL/受阻/延期区分，延期核销不当作失败。

### 3.3 当前缺陷归属

| 问题 | 归属与处理 |
|---|---|
| 运行脚本启动旧 Agent | Task3，纠偏后重新验证 AI |
| waiting_input 被映射为失败、准备工具不在契约 | Task1/3，Task8.5 端到端复核 |
| 图片/订单时间/退款金额/进度摘要缺失 | Task2 与 Task8.1 |
| 评分不可选问题已局部修复，但新布局尚未复验 | Task8.3，不直接沿用旧结论 |
| AI 进度残留、协议英文状态、卡片长标识 | Task5 公共展示与 Task8.5 |
| 独立报告的 Android 131 项中 12 项失败 | Task8.7 先定位归属，最终 Task12 全量通过 |
| UI-21B 尚未独立触发 | Task8.6，给出可重复失败与恢复场景 |
| 后端未知卡片错误文案的历史断言失败 | Task8.7 修复公开卡片错误优先级，保留有效断言，不宣称 Task2 全量后端全绿 |
| 外部餐图源偶发超时/连接中断 | Task8 真实验证缺图、加载失败占位和重试，禁止假餐图兜底 |
| 原视觉失败报告 | 按最新容差重判：轻微差异为建议，核心缺失仍为阻塞；不得自动把 FAIL 改 PASS |

### 3.4 Task7–12 强制交付步骤

每个Task执行下面七步，各自复选框见任务正文；该清单是执行门槛，不代表文档写完已解决问题。

1. 开始：记录前置任务、两仓已提交版本、任务文件和共享依赖；原dirty注明来源、归属及是否需要，不重做已完成任务。
2. 引用核对：沿实际代码到布局、图标、颜色、导航、配置和构建文件递归核对。共享依赖按当前用途判断，必要展示资源纳入当前任务而不宣称未来业务完成。
3. 拟提交清单：分本任务增量、必要既有依赖、未来任务、延期功能；共享文件按块精确投影，保留工作区原文，不全仓暂存。
4. 开发验证：主Agent定向自测、适用回归、真实接口与最新APK/Maestro；保留首次失败和版本，不反复无理由全量测试。
5. 独立综合验收：每Task一名验收者亲跑适用测试、真实接口、完整端到端及当前Figma逐屏，分别判需求/质量/运行；8.1–8.8不单独派验收。
6. 交付源闭合：在不改当前工作区的隔离快照，仅从拟提交源与必要受控环境构建并运行适用回归。验收者亲跑，记录清单/源指纹/命令；缺依赖由主Agent补最小范围，原验收者复验，不借未来功能或延期核销掩盖错误。
7. 收尾：独立PASS且阻塞0之后精确提交，记录提交号、报告、实际结果和遗留项，最后勾选任务完成。没有快照验证只能写待源码闭合，不宣称已交付。

修复只重跑受影响场景及必要回归；未变且不受影响的证据记录版本与复用理由后保留。实际生产密钥、用户数据、旧私有运行目录不复制到交付快照；测试临时目录独立，避免H2输出覆盖原清单。

---

### Task 1: 补正并冻结跨仓库协议

**Files:** `[手机] contracts/agent-protocol-v1.yaml`、`contracts/agent-tools-v1.yaml`、`backend/src/test/java/com/fandazi/feature/agent/AgentContractSchemaTest.java`；`[Agent] config/contracts/`、`tests/test_cross_repo_contracts.py`。

**接口目标:** schemaVersion 1.1；query 为输入字段；waiting_input/input_required；17 个工具；confirmationGrantId 为确认字段；卡片与事件字段统一。

- [x] 原 1.0 权威契约与 Agent 受控副本已建立，历史自测保留在归档。
- [x] 补入 prepare_refund/prepare_complaint，定义准备副作用、输入输出与确认要求。
- [x] 定义等待补充与等待确认、终态、取消；输入候选、确认摘要和卡片动作字段可校验。
- [x] 固定 Spring 分配 runId、追问/确认沿用 runId、每次输入新 traceId；更新三端兼容映射。
- [x] 同步新 Agent 受控副本、版本与 SHA-256，不仅校验工具名，还校验关键请求/响应字段。
- [x] 运行手机 AgentContractSchemaTest 和新 Agent tests/test_cross_repo_contracts.py；独立验收契约一致性。
- [x] 验收通过后提交本任务文件（手机 f4902b8、新 Agent fad28f8；与 Task3 直接依赖一同入库）。

命令：手机 `mvn -f backend/pom.xml -Dtest=AgentContractSchemaTest test`；新 Agent `C:/Users/caid0/anaconda3/envs/pinnT/python.exe -m pytest tests/test_cross_repo_contracts.py -q`。

2026-10-01 独立验收 PASS：Maven 44/44、跨仓 34/34、独立载荷 106/106；首次 FAIL 的空值转换与公开凭证泄漏已修复并复验。证据：`docs/verification/2026-10-01-task1-independent-acceptance.md`。本轮没有 Android UI 改动，不宣称 APK 通过。

### Task 2: 补齐领域、页面数据与确定性夹具

**Files:** `[手机] backend/.../catalog/DealPackageEntity.java`、`DealPackageResponse.java`、`grouporder/GroupBuyOrderService.java`、`refund/RefundService.java`、`voucher/VoucherService.java`、`seed/SeedDataLoader.java`、`seed/Task2DevelopmentFixtures.java`、`core/money/`、`core/time/`、`core/identity/`、`data/seed/`；实际追加 `backend/src/main/java/db/migration/V12__decimal_payment_snapshots.java`、`V13__random_public_ids_and_legacy_aliases.java`（原 SQL V1–V11 不改）；`backend/src/test/java/com/fandazi/feature/grouporder/GroupOrderSummaryContractTest.java`；双仓契约可选字段及新 Agent `tests/test_task2_page_data_contract.py`、`test_task2_page_data_live.py`、`test_task2_fixtures_live.py`。完整写集见 Task2 开发/独立报告。

**接口目标:** spec 6.1 页面字段表；退款来自支付快照分摊；时间明确时区；服务端 canRefund/canReview；真实图片与缺图状态。

- [x] 团购实体、V6/V7 和基础种子已有，历史记录保留。
- [x] 对照 spec 6.1 逐字段检查实际接口，补齐订单/券/退款摘要及图片 URL；给出可空字段规则。
- [x] 校验金额来自订单支付快照，落实 DECIMAL 存储与整数分协议边界、UTC 时间与 UUID publicId；核对现有整数分存储和可枚举目录 ID 的差异，制定字段与引用映射，必要数据库变更仅追加迁移。
- [x] 建立无订单、待支付、可退款、已申请、可评价、已评价、无候选券的隔离开发夹具。
- [x] 运行 GroupOrderSummaryContractTest、GroupBuyingSeedDataTest、RefundEligibilityTest、ReviewEligibilityTest；独立核查真实 MySQL 响应。
- [x] 提交已验收的数据与接口补正（手机 d6c6441、新 Agent 8265f08）。

命令：`mvn -f backend/pom.xml -Dtest=GroupOrderSummaryContractTest,GroupBuyingSeedDataTest,RefundEligibilityTest,ReviewEligibilityTest test`。若历史测试名与实际类不同，先登记实际对应关系，不假称已运行不存在的测试。

2026-10-02 独立验收 PASS：定向 Maven 70/70、新 Agent 离线 352/352、Task2 真实在线 9/9、Task3 真实在线 5/5，另有 40 项实际 HTTP/MySQL 断言与 10 项独立 Java 检查。真实 V13 保留历史 25 单、29 券、6 退款，金额/归属/引用差异为 0。完整后端 194/195，唯一旧卡片文案失败不属于 Task2 数据实现，但留在 Task8.7 修复；餐图首轮超时及复验通过均记录，不掩盖稳定性风险。报告：`docs/verification/2026-10-01-task2-independent-acceptance.md`。本任务未改 Android，不宣称 APK 通过。

### Task 3: 将新 Agent 实际接入并校正运行来源

**Files:** `[Agent] service/__init__.py,app.py,models.py,auth.py,streaming.py`、`requirements.txt`、`tests/test_agent_service_api.py`；`[手机] backend/.../agent/HttpAgentServiceClient.java`、`AgentGatewayService.java`、`AgentGatewayV1ContractTest.java`、`start-fandazi-agent.ps1`、`start-fandazi-services.ps1`。

**接口目标:** 新项目 POST /internal/v1/agent/runs、GET /internal/v1/health；NDJSON → Spring SSE；目标 1.1 契约。

- [x] 记录现有旧服务的 API 与所需迁移行为，保留旧源码，但新业务实现放入新 Agent 项目。
- [x] 实现目标 API、内部 JWT、公开 NDJSON、服务标识与版本健康响应；接入现有三层意图与 DAG，不能用旧规则捷径代替。
- [x] 完成最小团购意图迁移：按 spec 9 统一 12 类名称，同步两份意图 YAML、输出模型、决策模型与重型分类提示词、工作流映射及 Planner/Validator 的意图集合；补齐找店/找套餐、订单、退款、投诉和复合请求的最小边界测试。保留原背景 state 与 0.80 阈值，历史基线不改；Task8 AI 验收必须在此项通过后进行。
- [x] 实现 Spring 权威字段转换、waiting_input/confirmation 终态和中断处理；兼容字段集中在适配器。
- [x] 更新本地脚本指向新入口；确认后端 URL 和运行进程确实使用新项目，启动失败不回退旧 Agent。
- [x] 在新项目运行 tests/test_agent_service_api.py；手机运行 AgentGatewayV1ContractTest。
- [x] 运行新 Agent 的最小团购分类与 Planner 契约测试，核对两层模型输出和工具目标均使用相同的 12 类名称；千级语料和完整评测留在 Task9，不倒置任务依赖。
- [x] 独立 Agent 实测新服务身份、JWT 拒绝、NDJSON 顺序、问候、输入暂停和确认恢复。
- [x] 通过后提交并回写运行命令（手机 f4902b8、新 Agent fad28f8，命令见独立报告）；旧服务退役范围最终由 Task11 核查。

Task3 的旧勾选不再表示新项目接入通过，原记录完整保留在归档。

2026-10-01 独立验收 PASS：Python 356 项、Maven 72 项；真实 MySQL、购买两次确认、取消、输入与确认跨进程恢复、SSE 脱敏有实测证据。报告：`docs/verification/2026-10-01-task3-independent-acceptance.md`。没有 Android 改动，不声明 APK 或 Task2/8 通过。

### Task 4: 复核浏览、排序与套餐推荐

**Files:** `[手机] backend/.../catalog/StoreController.java,StoreService.java,StoreBrowseService.java,StoreBrowseResponse.java,StoreReviewRepository.java,DealPackageRepository.java,DealPackageService.java`；Android `feature/home,search,store,deal/`、共享 `core/image/AfterSaleImageLoader.kt`；`maestro/group-buying-01-browse.yaml` 及三条 Task4 搜索/断网/恢复脚本；`[Agent] service/recommendation.py`、`service/engine.py` 的只读推荐分派、`service/providers.py`、`tests/test_group_buying_read_tools.py`、`tests/test_task4_browse_live.py`。共享图片依赖源于既有工作区，纳入不表示售后流程或 Task8 已通过。

- [x] UI-01–06 设计已确认，历史浏览 Maestro 局部通过记录保留。
- [x] 核对分类、人均/位置排序、无定位路径、门店三 Tab、不同套餐 ID 与详情切换，全部使用真实接口。
- [x] 将推荐工作流接入新 Agent；单个推荐目标可多工具但 is_composite=false。
- [x] 运行 StoreDealApiTest、实际 CatalogRepositoryTest/MerchantNavigationPagesTest、新 Agent 只读/城市补充/在线测试与浏览 Maestro；没有为了旧计划 HomeSortTest 名称复制实现。
- [x] 独立验收按 UI 容差确认结构、真实数据和操作路径；通过后提交（手机8d1870d、新Agent88c386e）。

独立报告：`docs/verification/2026-10-02-task4-independent-acceptance.md`，Goodall 明确需求、质量及实际运行整体 PASS（非阻塞关注）。亲自运行 Maven213/213、Android147/147、Agent37通过/5跳过、真实双服务3/3、意图136/136，最新APK四条规定Maestro及当前Figma UI-01–06对照通过。一次性H2测试输出曾变化，原始失败证据保留；独立核对后撤回将其当真实夹具的范围误判，再在同源隔离快照原样复验1/1，29个原清单/元数据、252个源文件及34张真实库表均未变化。未修改Task2测试，未伪造恢复。既有完整工具审计超过8000字符时缺一条审计、故障注入后额外图片流程瞬态失败列后续关注，不宣称已修复。验收以冻结真实工作区为准，最终Task12须再核对完整提交后的部署。

### Task 5: 完成 AI 公共聊天状态

**Files:** Android `feature/agent/AgentChatFragment.kt,AgentViewModel.kt,AgentModels.kt,AgentRepository.kt,AgentCardRenderer.kt`；`fragment_agent_chat.xml` 与 `item_agent_*.xml`；`AgentChatStateTest.kt,HttpAgentRepositoryTest.kt`；`maestro/task5-ai-chat-page.yaml`。

**状态:** UI-15A–J 同一个聊天路由，设计确认与实现验收分别记录；本期使用文字输入，ASR 延期。售后具体卡片由 Task8.5 扩展。

**前置与交付边界:** Task1–3 已独立通过；Task4 必须先独立验收通过，才启动 Task5，并核对真实只读接口与 Task2 隔离夹具可用。Task5 验证公共组件和已具备接口的消息、事件与卡片，不以 Task6/8 的完整写业务作为本任务前置；购买写入闭环归 Task6，完整订单/券查询归 Task7，售后写入闭环归 Task8。共享确认/取消状态在本任务核对，但不据组件通过宣称后续业务通过。

2026-10-02只读检查点：`docs/verification/2026-10-02-task5-development.md`。已定位十个完整Frame（56:2–56:404），只有H存在明确确认记录。待确认九页A–G/I–J；H当前登录卡超出页面262px，不能照越界稿实施，应恢复已确认视觉结构而非重画风格；E缺规格要求的原价/折扣区，实际缺数据仍不补造。重试重复用户气泡、新轮清旧卡、按文案合并步骤等为源码审计发现，尚未实际运行复现，不写成测试失败或已修复。

最小前置补正已于用户“y”回复后授权：Spring公开HTTP事件/SSE保留新Agent原始eventId、sequence、runId及status，用于Task5消息/步骤合并和去重；数据库SSE游标继续用于Last-Event-ID，不改归属、内部确认凭证或业务工具。归Task1/3接口兼容依赖，本轮纳入Task5最小补正并定向复验，不静默扩展其它业务。九个现有Frame获继续实施授权，必要修复限定H越界、E既定原价/折扣和明确字段/复合语义；先修Figma，再转XML，完成后独立验收。此前未改产品/未运行测试的记录为当时事实，当前已恢复；Task6–8依次在前项通过后启动。

- [x] 初始消息、气泡、卡片和进度布局已有实现，历史自测保留。
- [x] 核对当前 AI Frame 确认记录，只补未确认状态；按 latest UI 容差实施，不重画已通过页面。
- [x] 完成空态、真实进度、文字、门店/套餐/订单、游客、失败重试与多轮；不显示 running 等协议状态或固定耗时。
- [x] 核对正式页面为文字输入，不提供误导性的可用语音入口；不将旧面板的系统 SpeechRecognizer 或回填单测当作本地 ASR 已交付。保留历史代码，不新增语音实现。
- [x] 按 runId/步骤标识合并进度；暂停/终态结束动画；重试不复制用户消息；历史结果可追溯。
- [x] 使用新 Agent 跑 HttpAgentRepositoryTest、AgentChatStateTest 和真实聊天 Maestro。
- [x] 独立核查所有本期可达 AI 状态、消息与卡片、错误恢复和无自动跳转。
- [x] 通过后提交；售后新增交互由 Task8 验收，不把一个空态测试算全部 AI 通过（手机3a3bafe、Agent文档f5a33db）。

2026-10-02 Task5独立R1整体PASS：`docs/verification/2026-10-02-task5-independent-acceptance.md`。原R0实测旧数量1卡误取消新数量2任务，需求/质量/运行FAIL完整保留；修复绑定原run/trace/确认事件及摘要，Jason亲跑64/64、最新APK11个Maestro执行单元、真实HTTP/SSE11/11、旧卡实际点击无效而当前取消有效、游客禁发与设备全离线恢复后独立关闭P1。APK840D115471CE8BE02ADE51B2CB3E5CF6D3D913EE4D1CA74375DF9250A130A724。Task5只验收公共展示/状态，真实购买确认写入留Task6；F聊天跳现有订单页的只读原dirty依赖按冻结SHA登记、完整订单页面交Task7收尾，不把当前工作区验收冒称干净BASE整应用可编译。

### Task 6: 完成购买、确认和模拟支付

**Files:** 手机 `purchase/PurchaseQuoteService.java`、`grouporder/GroupBuyOrderService.java`、`agent/ConfirmationGrantService.java`；Android `feature/purchase/` 与支付页面；`maestro/group-buying-03-purchase.yaml`；新 Agent `workflows/purchase.py`、`tests/test_purchase_workflow.py`。

- [x] 后端报价/创建/支付和 UI-07–09 设计已有，历史开发自测保留。
- [x] 新 Agent 使用真实报价与确认摘要，完成暂停、恢复、取消、过期与价格变化分支。
- [x] Android 底部确认弹窗及支付页面绑定后端金额；鉴权失败引导登录并回到原目标。
- [x] 运行 PurchaseConfirmationTest、GroupBuyOrderIdempotencyTest、新 Agent test_purchase_workflow.py 与购买 Maestro。
- [x] 独立验证不同套餐、数量、重复点击、凭证过期及支付成功，按 UI 容差检查。
- [x] 通过后提交（手机f66d9e5，Agent c339237；仅Task6范围，原Task7/8保持工作区）。

2026-10-02 Task6独立R1整体PASS：`docs/verification/2026-10-02-task6-independent-acceptance.md`。R0金额用例依赖共享门店导致217项1error，改独立H2门店/套餐后Tesla亲跑同源全量217全绿；复用其不受影响Android171与Agent378/21跳过，不能把跳过算在线结果。最新APK5F03AF43…两套餐数量1/2、三页真实401登录返回、真实断网恢复、310秒自然过期、隔离变价与旧卡守卫均实际完成，30段Maestro中27段退出0、3次环境/验证器失败及受影响复验保留，175条断言非175个独立场景。原事实与277保护文件不变；开发历史共享套餐变价审计限制不抹除。当前工作区验收与干净中间HEAD边界延续Task5，订单/券只读依赖交Task7闭合，不把Task7/8或延期核销提前算完成。

### Task 7: 完成本期团购券查询；核销延期

**Files:** 手机 `voucher/VoucherService.java` 与团购订单 API；Android `feature/order/`、`feature/voucher/`；新 Agent 券只读工具；`maestro/group-buying-04-voucher-query.yaml`。

- [x] 7.1 按3.4步骤1–3记录Task6前置及两仓BASE、原券/售后dirty归属；实际依赖清单包括状态资源、格式化、受控导航、只读退款身份接口；最终70/4路径见task7-r3-evidence。
- [x] 7.2 原Lorentz亲跑拟提交源隔离构建：后端32、Agent93、Android69；B1/B2最小修复后R3整体PASS，首次失败保留。
- [x] 7.3 已精确提交手机93b97a2、Agent d9fc396；报告2026-10-02-task7-independent-acceptance.md，真实已有退款申请200及进度UI由Task8继续。

- [x] 订单和券查询、UI-10–12 设计及基础实现已有。
- [x] 验证一份一券、本人归属、订单状态分段、券详情与退款入口资格。
- [x] 接入新Agent只读券卡，不下发可直接核销的令牌；核销入口保持延期。
- [x] 订单 → 详情 → 券详情 → 返回的完整Maestro通过；注册退款页仅准备未消费预览，不提交退款。
- [x] UI-10–12、只读工具、权限及源码闭合独立通过并提交；R3新增16真实HTTP检查及两种APK共110条Maestro命令通过，未受影响历史证据按版本复用。

延期清单：UI-13/14、二维码生成/刷新、模拟商家核销、核销结果与专属 Maestro。保留代码和设计，不在本期复选框和 Task12 PASS 条件中要求完成。

### Task 8: 按步骤收尾退款、评价、帮助、投诉与售后聊天

**当前状态:** Task8已完成并精确提交：手机`5476e4a`、新Agent`d3cabc3`。Kant最终R2整体PASS、阻塞0，包含用户明确接受的1项事故例外：保留同一测试订单及库存71→70；原guard失败、R0 FAIL和R1技术PASS历史不改。产品源与验收树一致，所有必要依赖闭合，最终报告`docs/verification/2026-10-03-task8-independent-final.md`，收尾记录`docs/verification/2026-10-03-task8-closure.md`。本轮结束，不启动Task9–12。

**历史R1补充（2026-10-03 00:42）:** 原验收者亲跑隔离守卫3/3、真实退款/投诉2/2通过，两库36表7412既有行修改0/删除0；产品blob一致，复用未受影响构建和UI证据。技术修复PASS，原事故前guard仍FAIL，T8-IND-01保留至用户确认审计处置后由原验收者给最终结论。报告 `docs/verification/2026-10-03-task8-independent-r1.md`。环境已释放，不能用本次增量无损当作历史事实已恢复。

**恢复门槛:** 检查 Task4–7 的独立报告与提交，重新核对源码、APK、服务来源和剩余夹具资格；已保存实现直接复用，仅补未完成项及受前置修改影响的回归，不重画已确认设计或重置已有业务事实。

**文件边界:** 手机后端 `refund,review,coupon,support,grouporder,voucher,agent/`；Android `refund,review,support,order,voucher,agent/` 与对应 XML；新 Agent 已接入的 `service/engine.py,dag_runtime.py`、`dag/tool_registry.py,tool_adapters.py`；售后与异常 Maestro。使用现有服务状态机，不为了文件名再复制一套工作流；旧 agent-service 仅作迁移参考。

#### 8.1 接口与页面数据准备

- [x] 8.1a 按3.4步骤1记录Task4–7前置提交、源版本/环境与剩余夹具；注明旧售后dirty和已消费事实来源，只恢复剩余项。

- [x] 记录已有后端资格测试 14/14 与原独立失败基线；完整证据见历史归档。
- [x] 确认 Task1/3 新服务、协议与最小团购意图迁移前置；普通 UI 与 AI 路径分别标明运行来源。
- [x] 按 spec 6.1 补齐订单摘要、createdAt、退款金额、图片/缺图、申请进度、投诉类型目录与本人订单。
- [x] 明确客户端只做格式化，资格与金额不在 Android 推断；HTTP/协议错误展示重试。
- [x] 定向接口测试和真实 MySQL 响应验证；数据满足页面绑定后进入 8.2。

8.1 开发证据：后端定向33/33、真实目录/本人订单503→200检查1/1；Task2支付快照与真实MySQL证据复用。具体见 `docs/verification/2026-10-02-task8-service-development.md`，这些步骤勾选不表示 Task8 已独立验收。

相关手机文件：GroupBuyOrderService、VoucherService、RefundService、ComplaintService/Controller、FaqService、DealPackageEntity/Response、VoucherModels/Repository、AfterSaleRepository；必要变更回写 Task2。

#### 8.2 退款申请与进度：UI-18A/B

- [x] 绑定本人券、订单、套餐、真实金额、原因和补充说明；金额/资格未取得时禁止提交。
- [x] 用户显式提交时走后端准备/确认；重复点击不重复申请，已核销/未付款/已申请券拒绝。
- [x] 进度显示后端 REQUESTED（待处理）、申请时间和摘要；不伪造审核完成、到账或处理时限。
- [x] 保持返回路径，输入错误保留所填内容；已申请券退出/重新进入后，使用本人按券查询真实申请重进进度，不能只依赖首次回执；轻微布局/色值差异记录为建议。
- [x] 开发运行退款 API/资格测试、最新 APK 对应 Maestro 并保存两页截图。

文件：RefundRequestFragment、RefundProgressFragment、fragment_refund_request.xml、fragment_refund_progress.xml、RefundService/Controller、RefundEligibilityTest。

#### 8.3 发布评价：UI-19

- [x] 从 canReview=true 的本人订单进入，展示真实摘要/时间与图片或合理缺图状态。
- [x] 一至五星均可点选，评分反馈明确；正文与评分必填、字数上限和错误提示正确。
- [x] 发布成功刷新订单资格，不能重复评价；后端写入再验证核销事实与唯一性。
- [x] 使用开发夹具准备可评价订单，不实施本期延期核销。
- [x] 运行 ReviewEligibilityTest、最新 APK 的 group-buying-05-review.yaml，保存评分与成功截图。

文件：ReviewFragment、fragment_review.xml、ReviewService/Controller、VoucherModels/Repository。照片上传不在范围。

#### 8.4 FAQ、投诉与优惠券只读查询：UI-20A/B

- [x] FAQ 分类/搜索/展开答案取后端；滚动后仍能进入投诉，不承诺已能核销。
- [x] 投诉类型来自后端目录或明确固定 UI 选项契约；关联订单只能选择本人订单，不写死样例。
- [x] 描述校验、准备、明确确认、真实提交结果和返回路径完整；保留输入，失败可重试。
- [x] query_coupon 仅查询本人优惠券，验证归属和有效期；本期不新增券领取/核销/支付抵扣或未经设计的券包页面。
- [x] 运行 FaqServiceTest、CouponComplaintToolTest、group-buying-05-after-sale.yaml 并保存相关状态截图。

文件：HelpFaqFragment、ComplaintFragment、fragment_help_faq.xml、fragment_complaint.xml、Faq/Complaint/CouponService。反馈成功作为 UI-20B 的派生状态登记，不为了设计数量增加独立业务路由。

#### 8.5 新 Agent 售后聊天交互

- [x] 实现真实三层识别后的售后工作流；不继续扩展旧服务作为交付路径。
- [x] 退款：候选券 → 点击选券 → 补原因 → prepare_refund → confirmation 卡 → 明确确认 → request_refund → REQUESTED（待处理）。
- [x] 投诉：类型/描述/可选本人订单 → prepare_complaint → confirmation 卡 → 明确确认 → submit_complaint。
- [x] waiting_input 是等待补充，允许输入且不显示失败；waiting_confirmation 不提前写入。
- [x] 卡片金额、资格、原因和有效期来自后端；券号展示可用末位摘要，操作仍传完整公开 ID；凭证不作为文案泄漏。
- [x] 暂停后结束活动进度，用户取消、凭证过期、资格变化、服务重启、无券和写入结果不确定有明确分支。
- [x] “查退款规则并推荐火锅套餐”中独立只读分支可继续，依赖失败只跳过下游。
- [x] 补登记必要售后聊天派生状态；复用已确认聊天框结构，新增交互才补设计确认。
- [x] 运行新 Agent tests/test_after_sale_workflow.py、test_agent_service_api.py；手机 AgentGatewayV1ContractTest、HttpAgentRepositoryTest；group-buying-05-agent-refund.yaml 与投诉确认场景。

文件：新 Agent service/engine.py、service/dag_runtime.py、工具适配和服务模型；手机 HttpAgentServiceClient、AgentRepository、AgentCardRenderer、AgentChatFragment。售后数据从旧 CandidateCart 分离，集中兼容而不继续扩散外卖字段。

#### 8.6 空态、加载失败与网络异常：UI-21A/B/C

- [x] 无订单账号触发空态，返回首页有效，订单 Tab 选中正确。
- [x] 开发验证环境对本人订单接口产生一次可恢复 5xx，进入 UI-21B；恢复后点重试保留原页面上下文并读取真实结果。
- [x] 临时断开模拟器网络触发 UI-21C；恢复网络后原地重试成功，不清掉已有用户输入。
- [x] 失败注入仅限测试环境，不修改客户端业务数据或生产接口行为；记录触发和恢复步骤。
- [x] 保存三种状态和重试后截图，核对具体图标、可点击按钮与无溢出。

文件：OrdersFragment、view_orders_empty.xml、view_error_retry.xml、view_network_error.xml、对应 Maestro。UI-21B 必须实际触发，不能只检查 YAML 或 XML 存在。

#### 8.7 开发收尾与独立综合验收

- [x] 8.7a 按3.4步骤2–3递归核对售后/聊天/异常实际代码、资源、颜色、导航、认证、配置及构建源，形成四类精确清单，必要共享原依赖说明用途。
- [x] 8.7b 按3.4步骤6由同一独立验收者亲跑仅拟提交源的隔离构建与必要回归；缺依赖主Agent最小补齐复验，不能纳入延期QR/核销来凑编译。

- [x] 将既有 12 项 Android 失败逐项归属；有效回归修复，旧外卖测试的调整/退役有书面依据和替代覆盖。
- [x] 开发定向检查通过后，运行一次 Task8 适用后端、Agent 与 Android 回归；记录版本、命令、结果和已知问题。
- [x] 安装最新 APK，开发跑退款/评价/投诉/售后聊天/三种异常流程，保存可供核对的证据。
- [x] 所有核心缺陷修复后再启动独立验收 Agent；它亲自运行适用命令和真实流程，按最新 UI 容差逐项判断。
- [x] 独立报告为 docs/verification/task8-acceptance.md；每项标 PASS/FAIL/受阻，轻微视觉差异放非阻塞建议。
- [x] 修复独立阻塞项并由独立 Agent 复验；只复验受影响场景，公共协议/鉴权/状态变更时补跨场景回归。

收尾命令：

```text
[手机] mvn -f backend/pom.xml -Dtest=RefundEligibilityTest,ReviewEligibilityTest,CouponComplaintToolTest,FaqServiceTest,GroupOrderSummaryContractTest,AgentGatewayV1ContractTest test
[Agent] C:/Users/caid0/anaconda3/envs/pinnT/python.exe -m pytest tests/test_after_sale_workflow.py tests/test_agent_service_api.py -q
[手机/android-app] .\gradlew.bat testDebugUnitTest assembleDebug
[手机] maestro test maestro/group-buying-05-after-sale.yaml
[手机] maestro test maestro/group-buying-05-review.yaml
[手机] maestro test maestro/group-buying-05-agent-refund.yaml
```

上述 Maestro 增补覆盖 8.6 的可恢复错误和网络场景；验收账号由环境变量提供。独立验收报告必须标明测试脚本实际覆盖项，不能把一条 happy path 算作全部通过。

#### 8.8 提交与任务完成

- [x] 独立验收报告通过，核心阻塞项清零；非阻塞视觉建议不阻止完成。
- [x] 按3.4步骤7在精确提交后记录两仓编号、独立报告、实际结果和遗留，再勾选已完成步骤，不把核销延期计入本任务。
- [x] 核对手机与新 Agent 的任务文件，分别提交，不混入其他任务或本地密钥。
- [x] Task8 标记完成后结束本轮，保留历史失败与复验记录；Task9–12保留后续计划，不在本次执行范围内。

### Task 9: 完整意图模板、千级规则与评测

**Files:** 新 Agent `config/intent_templates.yaml,local_exact_intents.yaml`、`intent/`、`tests/test_intent_templates.py`、团购离线数据与 `docs/experiments/intent-routing-experiments.md`。

- [x] 9.1 前置Task8提交与dirty来源已核对，见Agent开发检查点；历史餐饮千级文件不冒算团购交付。
- [x] 9.2 实际依赖、版本、提示词、SDK及数据集闭合；精确增量排除旧UI/运行库/未来记忆。
- [x] 9.3 Bacon唯一独立验收亲跑隔离全量386通过/20跳过、影子91/91、在线抽样30条，168条指标复算一致；源闭合PASS，未测单列。
- [x] 9.4 Agent产品/证据精确提交163e8b5，手机仅授权和计划/spec回写；下列真人审批仍为生产门槛。

- [x] 完整12类模板各12正例/6反例/4边界，背景state与0.80/0.10不变；历史基线不改。
- [x] 建立1120条固定整句候选，主Agent与独立Agent逐组语义审查，无跨类规范化冲突，有损继续模型；此项是开发交付，不冒称真人审批。
- [ ] 生产启用前由业务审核人员对千级候选及规范化版本完成真人审批并留存记录；Task12生产部署不得绕过该门槛。
- [x] 均衡意图120条、独立复杂度48条实测，层调用率/正确率/置信度/P50/P95写入EXP020。
- [x] 配置/规则/路由与全量回归实际运行；在线opt-in跳过单列不计通过。
- [x] 独立核验控制变量、源闭合和结果后精确提交。

Task9收尾：Agent163e8b5；独立报告`D:/project/-Demo-master/docs/verification/2026-10-03-task9-independent-acceptance.md`。意图116/120、复杂度46/48；严格排除规则与提示词原句为104/108。模型错误、首次环境失败及生产真人审核门槛保留。Task10按当前追加授权立即开始，不重做Task1–8。

### Task 10: 长期记忆与用户管理入口

**Files:** 新 Agent `memory/`、`database/schema/`、`service/`、`tests/test_memory_*.py`；手机记忆代理、Android `feature/profile/`、记忆 Maestro。

- [x] 10.1 按3.4步骤1核对Task9/既有服务提交，列记忆、代理、账户与共享源、原profile/记忆dirty来源；资格和历史数据保护不变。
- [x] 10.2 按3.4步骤2–3沿MySQL/Chroma/补偿、协议、Figma状态、XML/图标/颜色/导航、配置与构建核对依赖；四类清单不混旧外卖清理。
- [x] 10.3 按3.4步骤4–6自测后单一独立验收亲跑跨用户/确认/删除/TTL/索引故障和最新APK/Figma，并验证拟提交源隔离构建；失败原验收者定向复验。
- [x] 10.4 按3.4步骤7精确提交及编号/报告/实际结果/遗留回写，再勾选。已提交Agent c76645a、手机c6748f5；独立Harvey R2最终PASS，B01–B06关闭。

- [x] 按 spec 10 冻结记忆类型、TTL、敏感确认和业务事实排除规则。
- [x] 落实写入授权：用户明确“记住”才直接保存普通偏好，重复偏好仅生成待确认建议；记录来源、确认状态、置信依据和更新时间，冲突/过期记录不注入。独立验收一次普通表达不会自动写入、待确认建议不召回及确认后生效。
- [x] 实现 MySQL 主记录、Chroma 检索/重建、索引补偿、分层注入与 token 预算。
- [x] 删除/清空同步清理主记录、向量索引与缓存；补偿未完成时也不得召回已删除内容。独立验证重试、重启及索引故障下删除仍生效。
- [x] 在 Figma 补齐记忆查看/纠正/删除/清空状态，登记活动清单；按已确认体系派生设计并独立核对，不冒称新页面获真人签字。
- [x] 实现 Spring 归属代理和 Android 正式入口，所有记忆取新 Agent API。
- [x] 独立验证跨用户隔离、删除、TTL、索引故障恢复、最新 APK Maestro 与 UI 容差。
- [x] 通过后提交。

Task10收尾：Agent c76645a、手机c6748f5；独立报告D:/project/-Demo-master/docs/verification/2026-10-03-task10-independent-acceptance.md。R0/R1失败保留，R2新APK177单测与5设备阶段通过、源码闭合、既有事实不变。全局lint5项转Task12；ASR/核销延期。

### Task 11: 退役外卖运行路径与旧 Agent

**Files:** 手机 `agent-service/DEPRECATED.md`、README、启动/配置/Compose/CI、Android 导航；引用扫描后的外卖模块；追加清理迁移；架构扫描测试。

- [x] 11.1 按3.4步骤1核对Task10及替代业务提交，列退役与保留源、共享能力、原dirty来源；备份与恢复独立验证前不做破坏性清理。
- [x] 11.2 按3.4步骤2–3递归核对默认入口/CI/Compose/导航/配置/构建，分类必要非外卖依赖、原旧代码保留、未来评测及延期核销；保持精确删除/提交清单。
- [x] 11.3 按3.4步骤4–6主Agent自测、单一独立验收亲跑引用扫描/迁移备份恢复/新服务启动与拟提交源隔离全量构建，缺依赖最小补正原人复验。
- [x] 11.4 按3.4步骤7提交编号/报告/实际结果/保留与退役说明写回后勾选。2026-10-03追加授权按Task10收尾后实施，备份恢复独立门槛保留。

- [x] 新 Agent 与替代业务路径独立通过后，用 rg 建立地址/购物车/配送/自动下单引用清单。
- [x] 将每项运行引用分配到替代或退役路径，保留菜单查询所需的非外卖能力。
- [x] 清理前完成数据库备份、恢复和前向修复说明；在隔离环境验证恢复可用，记录迁移前后 Flyway checksum 与数据核对依据。独立验收这些证据后才允许执行破坏性清理，回滚 Agent 镜像不等于恢复数据。
- [x] 追加清理迁移，编号取实际最大版本下一号；V8 及所有已执行迁移不改。
- [x] 移除无默认运行引用的旧交易入口（历史源码与事实保留），旧 Agent 源码保留，但退出启动、CI、健康检查和发布。
- [x] 运行 NoTakeawayRuntimeDependencyTest、NoTakeawayNavigationTest、全量构建及 rg 复核。
- [x] 独立确认默认启动只有新 Agent，失败不回旧服务；通过后提交。

Task11收尾：手机07545dc、Agent563f3a6；Descartes唯一独立验收PASS、阻塞0，报告docs/verification/2026-10-03-task11-independent-acceptance.md。隔离后端156、Android179、Agent413通过；20条件跳过、67退役不计通过。三条Maestro和两库实际恢复完成，V14只登记停用且旧事实不变；没有物理DROP、没有上线核销。环境已释放，按追加授权立即Task12。

### Task 12: 可插拔评测、集成部署与本期最终验收

**Files:** 新 Agent `evaluation/`、场景数据、Dockerfile、实验文档；手机 backend/Dockerfile、docker-compose.integration.yml、启动脚本、maestro/group-buying-99-full-regression.yaml、docs/ui-acceptance/README.md、docs/verification-report.md。

- [ ] 12.1 按3.4步骤1核对Task7–11提交/独立报告/遗留，列部署评测及共享源、现有未提交改动来源；ASR/核销仍延期，不改变业务范围。
- [ ] 12.2 按3.4步骤2–3沿镜像上下文、配置/依赖清单、Flyway、Figma/资源/导航、场景和构建递归核对；精确清单排除密钥、运行库及原始私有数据。
- [ ] 12.3 按3.4步骤4–6主Agent自测后单一独立验收亲跑实际集成/全量适用测试/最新APK全链/Figma/真实评测，并从拟提交源隔离构建部署证明不依赖本地遗漏文件；未测指标单列，失败定向复验。
- [ ] 12.4 按3.4步骤7精确提交及编号/最终报告/实际指标/遗留/延期回写，再宣称本期完成。2026-10-03追加授权按Task11收尾后实施，不能提前勾选或把未测项写为通过。

- [ ] 实现版本化 ScenarioCase/EvaluationResult/Scorer，评分器替换不改 Agent 主流程。
- [ ] 建立业务 MySQL、新 Agent/MySQL/Chroma 与 Spring 集成部署、健康标识和故障降级。
- [ ] 建立活动验收状态清单：Frame/nodeId、确认依据、页面入口、所需数据、Maestro、截图、结果；标记 UI-13/14 延期，登记新派生状态与记忆页。
- [ ] 最终报告明确端侧离线 ASR 延期、不算本期缺陷，也不宣称本地语音模型已部署；历史语音方案仅保留后续入口。
- [ ] 运行后端全量测试、新 Agent pytest、Android testDebugUnitTest/lintDebug/assembleDebug；本期有效测试全通过。
- [ ] 运行真实场景评测，记录意图、composite、DAG、工具、确认、记忆、卡片、任务成功和 P50/P95；没有实测不宣称达标。
- [ ] 独立 Agent 使用最新 APK 跑本期全量 Maestro，按 UI 容差逐屏确认，覆盖浏览/登录/聊天/购买/券查询/售后/异常/记忆。
- [ ] 停止新 Agent，独立验证普通业务继续可用与 AI 错误/恢复，不启动旧服务。
- [ ] 输出最终报告、运行说明、实验数据和延期清单；通过后提交，整体完成不宣称核销已完成。

命令：手机 `mvn -f backend/pom.xml test`；新 Agent `python -m pytest -q` 与 `python -m evaluation.runner`；Android `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug`；集成 `docker compose -f docker-compose.integration.yml up --build -d`；本期全量 `maestro test maestro/group-buying-99-full-regression.yaml`。完整参数与环境变量记录在报告，避免依赖聊天上下文。

## 4. 任务依赖与完成门槛

```text
Task1 契约补正 → Task3 新 Agent 实际接入 + 最小团购意图迁移
Task2 领域/字段 → Task4 浏览 → Task6 购买 → Task7 本期券查询
Task3 + Task5 公共聊天 + Task6/7 数据前置 → Task8 售后
Task8 独立通过 → Task9 意图评测 → Task10 长期记忆
替代路径通过 → Task11 旧路径退役 → Task12 本期最终验收
UI-13/14 与核销流程：延期分支，不阻塞本期 Task8–12
```

以上图只说明依赖，不作为跳过任务的调度顺序。唯一后续执行顺序为 Task4 → Task5 → Task6 → Task7 → Task8 → Task9 → Task10 → Task11 → Task12；Task1/3/2 保留已通过记录，不重做。核销与端侧离线 ASR 均延期，不阻塞本期完成，也不能写成已实现。

用户要求依次执行 Task，主Agent负责开发、自测与修复，仅独立验收单独Agent；每Task自测后一名验收者，失败原人定向复验。每项独立验收及拟提交源闭合通过后精确提交、记录并勾选，再自动下一项；Task8子步骤顺序自测，最后一次独立综合验收。本轮已获连续授权至Task8，Task9–12仅完善计划，原暂停记录不再阻止本轮恢复。

文档修订只改变执行依据，不把未运行检查勾成通过；当前代码改动与旧服务结果需要后续实际验证。
