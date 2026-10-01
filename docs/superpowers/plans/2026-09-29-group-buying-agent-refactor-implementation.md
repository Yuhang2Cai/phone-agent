# 饭搭子 AI 团购化与独立 Agent 服务重构 Implementation Plan

> 执行方式：按 executing-plans 或 subagent-driven-development 逐任务推进。复选框只表示本条行动已完成；任务“验收通过”必须有独立验收报告。UI 视觉开发不采用 TDD。

**Goal:** 将手机项目重构为餐饮到店团购 App，并用 `D:/project/-Demo-master` 的新 Agent 服务替换旧 Agent；核销功能本期暂缓。

**Architecture:** Android 只连接 Spring Boot；Spring 提供可信身份、业务接口、确认和白名单工具；新 Agent 负责意图、确定性工作流、DAG 与记忆。当前先纠正协议和新服务运行来源，再按业务步骤收尾 Task8。

**Tech Stack:** Android XML/MVVM/OkHttp/Gson；Spring Boot/Java 17/JPA/Flyway/MySQL；Python/LangGraph/FastAPI/MySQL/Chroma；JUnit/pytest/Robolectric/MockWebServer/Maestro。

**Spec:** `docs/superpowers/specs/2026-09-29-group-buying-agent-refactor-design.md`。

**UI Spec:** `D:/project/-Demo-master/docs/ui-design/ui-design-spec.md`。

**Figma:** `https://www.figma.com/design/FxsWKGs10z7BL1MhFAOqFN`。

**修订日期:** 2026-10-01。历史记录见 `docs/verification/group-buying-refactor-history-2026-10-01.md`。

## 1. 当前状态与推进顺序

| 任务 | 当前状态 | 当前剩余工作 |
|---|---|---|
| Task1 契约 | 1.1 补正已独立 PASS 并提交 | 后续任务沿用严格契约，新增字段同步双仓 |
| Task2 领域与种子 | 独立 PASS，阻塞项 0 | 提交验收文件；图片加载失败与旧卡片错误文案在 Task8 复核 |
| Task3 新 Agent 服务 | 新入口、12 类、DAG 与工具桥接已独立 PASS 并提交 | 后续 UI 验收使用新服务，不复用旧服务结论 |
| Task4 浏览推荐 | 有历史局部通过记录 | 用新 Agent 验证推荐，核对本期适用页面 |
| Task5 AI 页面 | 未完成完整独立验收 | 公共状态、卡片和多轮，按 UI 容差核验 |
| Task6 购买支付 | 部分开发自测通过 | 新 Agent 确认恢复、完整独立验收 |
| Task7 券查询 | 查询已实现，核销延期 | 验收 UI-10–12；UI-13/14 不计本期 |
| Task8 售后 | 设计已人工通过，实现整改中 | 按 8.1–8.8 执行，独立验收尚未通过 |
| Task9 意图语料 | 未开始 | 12 类完整模板、千级规则、评测 |
| Task10 记忆 | 未开始 | 先设计确认，再记忆与用户入口 |
| Task11 旧路径退役 | 未开始 | 新路径通过后退役，追加无冲突迁移 |
| Task12 最终验收 | 未开始 | 本期集成、全量回归、可插拔评测 |

以上区分历史开发进度与当前验收状态，不撤销历史证据，也不将旧服务自测换算为新服务通过。

当前依次执行：Task1 协议补正 → Task3 新服务纠偏与最小团购意图迁移 → Task2 页面字段准备 → Task8 分步收尾。Task8 的 AI 子步骤依赖 Task3 已统一的目标意图与 Task5 公共聊天状态；购买与本人券查询使用 Task6/7 的现有接口作为前置，失败时归属原任务修复，不扩大 Task8 的视觉重设计范围。

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
- 所有 Agent 实验写入 `[Agent] docs/experiments/intent-routing-experiments.md`；任务证据写入手机 `docs/verification/`。
- 实现、自测、独立验收、提交分别记录。独立 Agent 亲自运行适用检查；未运行、失败或证据不足不能勾选验收。
- 保留已有未提交改动；提交仅包含已核对的任务文件。

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
- [ ] 提交已验收的数据与接口补正。

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

**Files:** `[手机] backend/.../catalog/StoreController.java,StoreService.java,DealPackageController.java,DealPackageService.java`；Android `feature/home,store,deal/`；`maestro/group-buying-01-browse.yaml`；`[Agent] workflows/recommendation.py`、`dag/tool_adapters.py`、`tests/test_group_buying_read_tools.py`。

- [x] UI-01–06 设计已确认，历史浏览 Maestro 局部通过记录保留。
- [ ] 核对分类、人均/位置排序、无定位路径、门店三 Tab、不同套餐 ID 与详情切换，全部使用真实接口。
- [ ] 将推荐工作流接入新 Agent；单个推荐目标可多工具但 is_composite=false。
- [ ] 运行 StoreDealApiTest、HomeSortTest、新 Agent test_group_buying_read_tools.py 与浏览 Maestro。
- [ ] 独立验收按 UI 容差确认结构、真实数据和操作路径；通过后提交。

### Task 5: 完成 AI 公共聊天状态

**Files:** Android `feature/agent/AgentChatFragment.kt,AgentViewModel.kt,AgentModels.kt,AgentRepository.kt,AgentCardRenderer.kt`；`fragment_agent_chat.xml` 与 `item_agent_*.xml`；`AgentChatStateTest.kt,HttpAgentRepositoryTest.kt`；`maestro/task5-ai-chat-page.yaml`。

**状态:** UI-15A–J 同一个聊天路由，设计确认与实现验收分别记录；售后具体卡片由 Task8.5 扩展。

- [x] 初始消息、气泡、卡片和进度布局已有实现，历史自测保留。
- [ ] 核对当前 AI Frame 确认记录，只补未确认状态；按 latest UI 容差实施，不重画已通过页面。
- [ ] 完成空态、真实进度、文字、门店/套餐/订单、游客、失败重试与多轮；不显示 running 等协议状态或固定耗时。
- [ ] 按 runId/步骤标识合并进度；暂停/终态结束动画；重试不复制用户消息；历史结果可追溯。
- [ ] 使用新 Agent 跑 HttpAgentRepositoryTest、AgentChatStateTest 和真实聊天 Maestro。
- [ ] 独立核查所有本期可达 AI 状态、消息与卡片、错误恢复和无自动跳转。
- [ ] 通过后提交；售后新增交互由 Task8 验收，不把一个空态测试算全部 AI 通过。

### Task 6: 完成购买、确认和模拟支付

**Files:** 手机 `purchase/PurchaseQuoteService.java`、`grouporder/GroupBuyOrderService.java`、`agent/ConfirmationGrantService.java`；Android `feature/purchase/` 与支付页面；`maestro/group-buying-03-purchase.yaml`；新 Agent `workflows/purchase.py`、`tests/test_purchase_workflow.py`。

- [x] 后端报价/创建/支付和 UI-07–09 设计已有，历史开发自测保留。
- [ ] 新 Agent 使用真实报价与确认摘要，完成暂停、恢复、取消、过期与价格变化分支。
- [ ] Android 底部确认弹窗及支付页面绑定后端金额；鉴权失败引导登录并回到原目标。
- [ ] 运行 PurchaseConfirmationTest、GroupBuyOrderIdempotencyTest、新 Agent test_purchase_workflow.py 与购买 Maestro。
- [ ] 独立验证不同套餐、数量、重复点击、凭证过期及支付成功，按 UI 容差检查。
- [ ] 通过后提交。

### Task 7: 完成本期团购券查询；核销延期

**Files:** 手机 `voucher/VoucherService.java` 与团购订单 API；Android `feature/order/`、`feature/voucher/`；新 Agent 券只读工具；`maestro/group-buying-04-voucher-query.yaml`。

- [x] 订单和券查询、UI-10–12 设计及基础实现已有。
- [ ] 验证一份一券、本人归属、订单状态分段、券详情与退款入口资格。
- [ ] 接入新 Agent 只读券卡，不下发可直接核销的令牌；延期按钮不得误导用户已能核销。
- [ ] 创建仅覆盖订单 → 详情 → 券详情 → 返回路径的 Maestro，不调用核销功能。
- [ ] 独立验证 UI-10–12、只读工具和权限边界，按 UI 容差验收后提交。

延期清单：UI-13/14、二维码生成/刷新、模拟商家核销、核销结果与专属 Maestro。保留代码和设计，不在本期复选框和 Task12 PASS 条件中要求完成。

### Task 8: 按步骤收尾退款、评价、帮助、投诉与售后聊天

**当前状态:** UI-18A/B、19、20A/B、21A/B/C 已由用户人工确认；实现与独立验收未通过。原独立失败记录不删除，按最新视觉容差重新判定。

**文件边界:** 手机后端 `refund,review,coupon,support,grouporder,voucher,agent/`；Android `refund,review,support,order,voucher,agent/` 与对应 XML；新 Agent `workflows/after_sale.py`、`dag/tool_registry.py,tool_adapters.py`；三个售后 Maestro。旧 agent-service 的现有工作流作为迁移参考，不作为本任务目标代码位置。

#### 8.1 接口与页面数据准备

- [x] 记录已有后端资格测试 14/14 与原独立失败基线；完整证据见历史归档。
- [ ] 确认 Task1/3 新服务、协议与最小团购意图迁移前置；普通 UI 与 AI 路径分别标明运行来源。
- [ ] 按 spec 6.1 补齐订单摘要、createdAt、退款金额、图片/缺图、申请进度、投诉类型目录与本人订单。
- [ ] 明确客户端只做格式化，资格与金额不在 Android 推断；HTTP/协议错误展示重试。
- [ ] 定向接口测试和真实 MySQL 响应验证；数据满足页面绑定后进入 8.2。

相关手机文件：GroupBuyOrderService、VoucherService、RefundService、ComplaintService/Controller、FaqService、DealPackageEntity/Response、VoucherModels/Repository、AfterSaleRepository；必要变更回写 Task2。

#### 8.2 退款申请与进度：UI-18A/B

- [ ] 绑定本人券、订单、套餐、真实金额、原因和补充说明；金额/资格未取得时禁止提交。
- [ ] 用户显式提交时走后端准备/确认；重复点击不重复申请，已核销/未付款/已申请券拒绝。
- [ ] 进度显示后端 REQUESTED（待处理）、申请时间和摘要；不伪造审核完成、到账或处理时限。
- [ ] 保持返回路径，输入错误保留所填内容；轻微布局/色值差异记录为建议。
- [ ] 开发运行退款 API/资格测试、最新 APK 对应 Maestro 并保存两页截图。

文件：RefundRequestFragment、RefundProgressFragment、fragment_refund_request.xml、fragment_refund_progress.xml、RefundService/Controller、RefundEligibilityTest。

#### 8.3 发布评价：UI-19

- [ ] 从 canReview=true 的本人订单进入，展示真实摘要/时间与图片或合理缺图状态。
- [ ] 一至五星均可点选，评分反馈明确；正文与评分必填、字数上限和错误提示正确。
- [ ] 发布成功刷新订单资格，不能重复评价；后端写入再验证核销事实与唯一性。
- [ ] 使用开发夹具准备可评价订单，不实施本期延期核销。
- [ ] 运行 ReviewEligibilityTest、最新 APK 的 group-buying-05-review.yaml，保存评分与成功截图。

文件：ReviewFragment、fragment_review.xml、ReviewService/Controller、VoucherModels/Repository。照片上传不在范围。

#### 8.4 FAQ、投诉与优惠券只读查询：UI-20A/B

- [ ] FAQ 分类/搜索/展开答案取后端；滚动后仍能进入投诉，不承诺已能核销。
- [ ] 投诉类型来自后端目录或明确固定 UI 选项契约；关联订单只能选择本人订单，不写死样例。
- [ ] 描述校验、准备、明确确认、真实提交结果和返回路径完整；保留输入，失败可重试。
- [ ] query_coupon 仅查询本人优惠券，验证归属和有效期；本期不新增券领取/核销/支付抵扣或未经设计的券包页面。
- [ ] 运行 FaqServiceTest、CouponComplaintToolTest、group-buying-05-after-sale.yaml 并保存相关状态截图。

文件：HelpFaqFragment、ComplaintFragment、fragment_help_faq.xml、fragment_complaint.xml、Faq/Complaint/CouponService。反馈成功作为 UI-20B 的派生状态登记，不为了设计数量增加独立业务路由。

#### 8.5 新 Agent 售后聊天交互

- [ ] 实现真实三层识别后的售后工作流；不继续扩展旧服务作为交付路径。
- [ ] 退款：候选券 → 点击选券 → 补原因 → prepare_refund → confirmation 卡 → 明确确认 → request_refund → REQUESTED（待处理）。
- [ ] 投诉：类型/描述/可选本人订单 → prepare_complaint → confirmation 卡 → 明确确认 → submit_complaint。
- [ ] waiting_input 是等待补充，允许输入且不显示失败；waiting_confirmation 不提前写入。
- [ ] 卡片金额、资格、原因和有效期来自后端；券号展示可用末位摘要，操作仍传完整公开 ID；凭证不作为文案泄漏。
- [ ] 暂停后结束活动进度，用户取消、凭证过期、资格变化、服务重启、无券和写入结果不确定有明确分支。
- [ ] “查退款规则并推荐火锅套餐”中独立只读分支可继续，依赖失败只跳过下游。
- [ ] 补登记必要售后聊天派生状态；复用已确认聊天框结构，新增交互才补设计确认。
- [ ] 运行新 Agent tests/test_after_sale_workflow.py、test_agent_service_api.py；手机 AgentGatewayV1ContractTest、HttpAgentRepositoryTest；group-buying-05-agent-refund.yaml 与投诉确认场景。

文件：新 Agent workflows/after_sale.py、工具适配和服务模型；手机 HttpAgentServiceClient、AgentRepository、AgentCardRenderer、AgentChatFragment。售后数据从旧 CandidateCart 分离，集中兼容而不继续扩散外卖字段。

#### 8.6 空态、加载失败与网络异常：UI-21A/B/C

- [ ] 无订单账号触发空态，返回首页有效，订单 Tab 选中正确。
- [ ] 开发验证环境对本人订单接口产生一次可恢复 5xx，进入 UI-21B；恢复后点重试保留原页面上下文并读取真实结果。
- [ ] 临时断开模拟器网络触发 UI-21C；恢复网络后原地重试成功，不清掉已有用户输入。
- [ ] 失败注入仅限测试环境，不修改客户端业务数据或生产接口行为；记录触发和恢复步骤。
- [ ] 保存三种状态和重试后截图，核对具体图标、可点击按钮与无溢出。

文件：OrdersFragment、view_orders_empty.xml、view_error_retry.xml、view_network_error.xml、对应 Maestro。UI-21B 必须实际触发，不能只检查 YAML 或 XML 存在。

#### 8.7 开发收尾与独立综合验收

- [ ] 将既有 12 项 Android 失败逐项归属；有效回归修复，旧外卖测试的调整/退役有书面依据和替代覆盖。
- [ ] 开发定向检查通过后，运行一次 Task8 适用后端、Agent 与 Android 回归；记录版本、命令、结果和已知问题。
- [ ] 安装最新 APK，开发跑退款/评价/投诉/售后聊天/三种异常流程，保存可供核对的证据。
- [ ] 所有核心缺陷修复后再启动独立验收 Agent；它亲自运行适用命令和真实流程，按最新 UI 容差逐项判断。
- [ ] 独立报告为 docs/verification/task8-acceptance.md；每项标 PASS/FAIL/受阻，轻微视觉差异放非阻塞建议。
- [ ] 修复独立阻塞项并由独立 Agent 复验；只复验受影响场景，公共协议/鉴权/状态变更时补跨场景回归。

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

- [ ] 独立验收报告通过，核心阻塞项清零；非阻塞视觉建议不阻止完成。
- [ ] 勾选已实际完成步骤，回写状态表与证据链接，不把核销延期计入本任务。
- [ ] 核对手机与新 Agent 的任务文件，分别提交，不混入其他任务或本地密钥。
- [ ] Task8 标记完成后再执行 Task9，保留历史失败与复验记录。

### Task 9: 完整意图模板、千级规则与评测

**Files:** 新 Agent `config/intent_templates.yaml,local_exact_intents.yaml`、`intent/`、`tests/test_intent_templates.py`、团购离线数据与 `docs/experiments/intent-routing-experiments.md`。

- [ ] 在 Task3 已统一的 12 类与最小示例基础上扩充完整正例/反例/边界，不把基础迁移延后至本任务；保留背景 state 与当前 0.80 配置，变更需单独实验。
- [ ] 建立千级人工审核本地整句文案，无跨意图规范化冲突；有损清洗进入模型层。
- [ ] 均衡评测意图与 composite，记录各层调用率、准确率、置信度和耗时。
- [ ] 运行配置/规则/路由单测与实际评测；没有在线测量时明确记录未测。
- [ ] 独立核验实验控制变量和结果后提交。

### Task 10: 长期记忆与用户管理入口

**Files:** 新 Agent `memory/`、`database/schema/`、`service/`、`tests/test_memory_*.py`；手机记忆代理、Android `feature/profile/`、记忆 Maestro。

- [ ] 按 spec 10 冻结记忆类型、TTL、敏感确认和业务事实排除规则。
- [ ] 落实写入授权：用户明确“记住”才直接保存普通偏好，重复偏好仅生成待确认建议；记录来源、确认状态、置信依据和更新时间，冲突/过期记录不注入。独立验收一次普通表达不会自动写入、待确认建议不召回及确认后生效。
- [ ] 实现 MySQL 主记录、Chroma 检索/重建、索引补偿、分层注入与 token 预算。
- [ ] 删除/清空同步清理主记录、向量索引与缓存；补偿未完成时也不得召回已删除内容。独立验证重试、重启及索引故障下删除仍生效。
- [ ] 在 Figma 补齐记忆查看/纠正/删除/清空状态并确认，登记到活动状态清单。
- [ ] 实现 Spring 归属代理和 Android 正式入口，所有记忆取新 Agent API。
- [ ] 独立验证跨用户隔离、删除、TTL、索引故障恢复、最新 APK Maestro 与 UI 容差。
- [ ] 通过后提交。

### Task 11: 退役外卖运行路径与旧 Agent

**Files:** 手机 `agent-service/DEPRECATED.md`、README、启动/配置/Compose/CI、Android 导航；引用扫描后的外卖模块；追加清理迁移；架构扫描测试。

- [ ] 新 Agent 与替代业务路径独立通过后，用 rg 建立地址/购物车/配送/自动下单引用清单。
- [ ] 将每项运行引用分配到替代或退役路径，保留菜单查询所需的非外卖能力。
- [ ] 清理前完成数据库备份、恢复和前向修复说明；在隔离环境验证恢复可用，记录迁移前后 Flyway checksum 与数据核对依据。独立验收这些证据后才允许执行破坏性清理，回滚 Agent 镜像不等于恢复数据。
- [ ] 追加清理迁移，编号取实际最大版本下一号；V8 及所有已执行迁移不改。
- [ ] 删除无运行引用的旧交易路径，旧 Agent 源码保留，但退出启动、CI、健康检查和发布。
- [ ] 运行 NoTakeawayRuntimeDependencyTest、NoTakeawayNavigationTest、全量构建及 rg 复核。
- [ ] 独立确认默认启动只有新 Agent，失败不回旧服务；通过后提交。

### Task 12: 可插拔评测、集成部署与本期最终验收

**Files:** 新 Agent `evaluation/`、场景数据、Dockerfile、实验文档；手机 backend/Dockerfile、docker-compose.integration.yml、启动脚本、maestro/group-buying-99-full-regression.yaml、docs/ui-acceptance/README.md、docs/verification-report.md。

- [ ] 实现版本化 ScenarioCase/EvaluationResult/Scorer，评分器替换不改 Agent 主流程。
- [ ] 建立业务 MySQL、新 Agent/MySQL/Chroma 与 Spring 集成部署、健康标识和故障降级。
- [ ] 建立活动验收状态清单：Frame/nodeId、确认依据、页面入口、所需数据、Maestro、截图、结果；标记 UI-13/14 延期，登记新派生状态与记忆页。
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

用户要求依次执行 Task，文件边界允许时仅将独立验收作为单独 Agent 工作，不同时启动多个未完成的业务任务。Task8 子步骤可分别自测和记录完成，整个任务通过一次独立综合验收后才标记完成。

文档修订只改变执行依据，不把未运行检查勾成通过；当前代码改动与旧服务结果需要后续实际验证。
