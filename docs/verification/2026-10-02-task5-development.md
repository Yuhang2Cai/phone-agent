# Task5 开发报告：设计确认前只读审计

- 状态：**NEEDS_CONTEXT**。仅完成本轮设计与源码审计；Task5 产品实现、开发自测和独立验收均未完成。
- 记录日期：2026-10-02（Asia/Shanghai）；本轮现场核查时间约 04:31–04:38，收尾文件一致性核查另记于证据文件。
- 阻塞：UI-15A–G、I–J 共九个完整 Frame 尚未收到人类明确确认。控制端已异步询问，本 Agent 等待控制端传递结果，不自行询问或把连续执行授权解释为 Frame 确认。
- 当前已确认 UI-15H 保留原确认状态，不重开整页评审；本轮发现当前节点发生严重坐标/裁切问题，作为实际缺陷登记，未改图。
- 本轮仅写本报告、SDD 检查点及本任务证据。未修改 Android/Spring/新 Agent 源码、测试或 Maestro；未暂存、提交、创建子 Agent、修改主计划/spec/UI 确认状态，未进入 Task6。
- 独立验收：待控制端安排；本报告不得被当作 Task5 开发自测通过或独立 PASS。

## 1. 依据与起点

首先完整读取 [task-5-brief.md](C:/fdz-build/.superpowers/sdd/2026-09-29-group-buying-agent-refactor-implementation/task-5-brief.md)。仅定向读取规格第 5、7、8、13 节和 [UI 规格第 26、27 节](D:/project/-Demo-master/docs/ui-design/ui-design-spec.md:649)，没有读取整个主实施计划或历史对话。历史确认核查采用限定关键词检索及确认记录附近少量行，不采信历史实施者笼统声称的“已确认”或局部自测。

| 工作区 | 本轮起点与最新已知状态 | 保护要求 |
| --- | --- | --- |
| 手机 C:/fdz-build | 初读 HEAD 8d1870d41516bf536dc5ceafbf03043dd86a9515；控制端归档后亲自复核 HEAD **3aef8988bcfbd0cccc52ee2d718fc6277167709d**；分支 feature/fandazi-ai | junction 指向 C:/Users/caid0/Documents/ChatGPT/手机agent/.worktrees/fandazi-ai；既存聊天/售后改动和真实夹具均不回滚、不覆盖 |
| 新 Agent D:/project/-Demo-master | 产品 HEAD **88c386e0b226c6d49e0f8f01ff1e55d01e66e553**；收尾观察归档 HEAD **ee04c2cd0fa0232e857da915c4ca2773842afb28**；分支 codex/group-buying-protocol-service | service/engine.py、service/dag_runtime.py、tests/test_agent_service_reads.py、实验文档、Task8 测试/证据等原脏文件留存 |

手机最新状态快照有 427 个 Git 路径记录；新 Agent 有 20 个非空路径记录（实际完整列表见证据）。两端所查暂存区均为空。本轮已有大量未跟踪聊天布局/状态测试/Maestro 文件，不能把它们算作本 Agent 新建交付。起点与控制端归档造成的状态变动分开记录，禁止用整树 diff 充作 Task5 写集。收尾观察新 Agent HEAD 从 88c386e 更新为 ee04c2c，git show --stat 证实该提交仅向 docs/experiments/intent-routing-experiments.md 追加 6 行 Task4 复验记录；本 Agent 未创建该提交，原脏状态保留。

源码保护摘要：手机 main/test/backend/contracts/Maestro 共 576 个文件；新 Agent service/tests/config/contracts 共 70 个文件。落盘前 SHA-256 总摘要与收尾重算一致（手机 B0190CDE77B493CBD4EE7CC777E45909FE5BD060989F4107B0990EADCAEE7D9D；新 Agent C977E9EAFE59A6A2658689BE9F8AB634AC5FAE5607C305E54ACCEC6AAEC60BE6），结果保存到 [source-integrity.json](C:/fdz-build/docs/verification/task5-evidence/source-integrity.json)。没有读取或打印凭据、没有注册账号、准备订单夹具、消费券、退款、评价或清库。

## 2. 确认记录核查

本轮在 D 项目 docs/ui-design、docs/verification 及手机 docs/verification、.scratch/ui-redesign 范围定向检索 UI-15 状态与“已确认/用户/人工/确认通过”，并核对以下实际记录：

- [UI 规格:56](D:/project/-Demo-master/docs/ui-design/ui-design-spec.md:56) 仅列 UI-15H 已确认，其余 AI 视觉稿仍需确认。
- [UI 规格:660](D:/project/-Demo-master/docs/ui-design/ui-design-spec.md:660) 明确排除已有确认状态，不能重审 UI-15H。
- [UI 规格:688](D:/project/-Demo-master/docs/ui-design/ui-design-spec.md:688) 至状态表末尾：A–G/I–J 为草稿，H 已确认。
- [既有文档独立验收:205](C:/fdz-build/docs/verification/2026-10-01-refactor-documents-acceptance.md:205) 只交叉证明 H 的确认状态，没有九页人类最终确认。
- 本轮控制端再次明确“人类尚未回复九个 Frame 确认”，与上述记录一致。

**结论：在上述核查范围未找到可解除九个 Frame 门槛的历史明确确认记录。** 不宣称已穷尽所有外部聊天；当前没有新的批准依据可供本 Agent 使用。仅 H 的既有确认有效；授权执行 Task4–12、旧脚本、旧截图和旧自测均不能替代九个 Frame 的具体确认。

## 3. 当前 Figma 实际节点

文件：[FxsWKGs10z7BL1MhFAOqFN](https://www.figma.com/design/FxsWKGs10z7BL1MhFAOqFN)，聊天页 **6:8 / 07 AI 助手**。本轮通过 figma-use 只读查询和截图亲自取得，十个状态均为独立可编辑 **393×852 FRAME**，有文本、消息/卡片、输入区域和房屋/机器人/单据/用户轮廓四导航。没有以 400×300 参考图片 Frame 充作产品稿。

| 状态与当前确认 | 当前完整 Frame/nodeId | 关键节点/核对结果 |
| --- | --- | --- |
| UI-15A 空状态；待确认 | [56:2](https://www.figma.com/design/FxsWKGs10z7BL1MhFAOqFN?node-id=56-2) | 头像 56:36/37、欢迎语 56:38、说明 56:39、快捷问题 56:40/42/44；输入 56:32、发送 56:34 |
| UI-15B 处理中；待确认 | [56:46](https://www.figma.com/design/FxsWKGs10z7BL1MhFAOqFN?node-id=56-46) | 用户消息 56:80、助手消息 56:82、进度卡 56:84；三行步骤必须来自实际事件 |
| UI-15C 文本回复；待确认 | [56:95](https://www.figma.com/design/FxsWKGs10z7BL1MhFAOqFN?node-id=56-95) | 用户消息 56:129、助手消息 56:131、追问提示 56:133；不得照抄示例餐厅判断 |
| UI-15D 门店卡；待确认 | [56:134](https://www.figma.com/design/FxsWKGs10z7BL1MhFAOqFN?node-id=56-134) | 卡 56:172、名称 56:174、评分/距离 56:175、统计 56:176、团购摘要 56:177、详情 56:179 |
| UI-15E 套餐卡；待确认 | [56:180](https://www.figma.com/design/FxsWKGs10z7BL1MhFAOqFN?node-id=56-180) | 卡 56:218、套餐名 56:220、描述 56:221、现价 56:222、详情 56:224；当前稿无原价/折扣区 |
| UI-15F 订单卡；待确认 | [56:225](https://www.figma.com/design/FxsWKGs10z7BL1MhFAOqFN?node-id=56-225) | 卡 56:263、门店/套餐 56:264、状态/有效期 56:265、实付 56:266、查看订单 56:268 |
| UI-15G 复合任务；待确认 | [56:269](https://www.figma.com/design/FxsWKGs10z7BL1MhFAOqFN?node-id=56-269) | 示例用户文本 56:304、进度卡 56:307；工具数量不能决定 is_composite |
| UI-15H 登录拦截；已确认 | [56:318](https://www.figma.com/design/FxsWKGs10z7BL1MhFAOqFN?node-id=56-318) | 登录卡 56:356、立即登录 56:360、稍后再说 56:362；没有套餐结果卡，但当前坐标严重越界 |
| UI-15I 错误兜底；待确认 | [56:363](https://www.figma.com/design/FxsWKGs10z7BL1MhFAOqFN?node-id=56-363) | 用户消息 56:397、错误卡 56:399、重试 56:403；应保留原输入且支持原地恢复 |
| UI-15J 多轮；待确认 | [56:404](https://www.figma.com/design/FxsWKGs10z7BL1MhFAOqFN?node-id=56-404) | 当前提问 56:438、上下文卡 56:440、助手消息 56:443；历史卡与对应轮次必须可追溯 |

关键图层的完整本轮读数见 [figma-audit.json](C:/fdz-build/docs/verification/task5-evidence/figma-audit.json)。截图证据来自本轮工具响应（A–E 及 F–J 两组实际可见图），尚未保存本地 PNG；不得冒充 APK 截图或后续独立验收证据。确认后仍应通过 figma-design-to-code 技能取得 get_design_context（含 skillNames）及最新截图，再转可编辑图层到 XML。

### 3.1 当前画板缺陷和语义边界

1. **F-H-01：已确认 H 的当前坐标缺陷。** Frame 56:318 宽 393，登录卡 56:356 为直接子节点，实际相对位置 x=294、y=16、宽=361、高=246，右边界 655，超出页面 262。父 Frame clipsContent=true。截图可见右上白卡覆盖标题/消息，“立即登录/稍后再说”文字基本被裁出页面。其父子关系与绝对坐标已复核，不能归因为邻页截图浮层。这是实际结构问题，不属可接受的轻微间距偏差。保持 H 原确认记录；报告控制端核对已确认视觉参考及最小坐标修复范围，本轮不改 Figma、不重画 H、不仿照裁切实施。
2. **F-E-01：稿与规格字段不足。** E 当前仅现价，UI 规格要求套餐现价、原价、折扣。Android 也未绑定已有后端 originalPriceCents。先由控制端把缺失区域纳入待确认处理，不自行新增未确认标签或伪造折扣。
3. **F-G-01：示例与复合语义风险。** G 示例“找附近川菜馆，比较团购套餐并推荐性价比高的”仍可能是单一推荐目标；brief 明确多工具推荐 is_composite=false。Figma 名称不能覆盖真实意图/DAG。确认后按真实 isComposite 与 stepId 渲染，同一个公共进度样式可复用，禁止为凑 G 制造复合事件。
4. **F-D-01：门店字段边界待对齐。** D 的 56:176 混有人均与评价数量，而 UI 27.3 白名单为名称、图片、评分、评价数量、距离、团购摘要；不把示例“人均 ¥56”、1.2 万或推荐套餐写成 Android 固定字段。以确认后的字段范围和 Spring 实际投影为准。
5. **F-COMMON-01：标题右侧操作语义。** Figma 各状态右上为更多操作，现 XML 为关闭按钮，另有未绑定处理的“添加内容”入口。确认后仅实现已批准的可用操作，不自行引入语音或无效入口。当前十页均未见正式语音入口，继续文字输入。

## 4. 当前聊天代码实际缺陷

下列为本轮源码确定的行为或缺口，尚未运行产品复现/测试；没有把静态审计写成“实测失败”。既存工作区差异归 Task8/历史公共聊天等前序工作，归属需按最小块对照，不能整文件覆盖。

| 编号/影响 | 当前证据 | 确认后最小处理方案与归属 |
| --- | --- | --- |
| C-01 重试复制用户消息 | [AgentViewModel.kt:128](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentViewModel.kt:128) retry 调 sendText；[105](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentViewModel.kt:105) 无条件追加用户气泡 | Task5 分离新输入与已有请求重试入口，复用原轮次/消息身份；失败后恢复不能追加第二个用户问题 |
| C-02 多轮卡片无法追溯 | [AgentViewModel.kt:107](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentViewModel.kt:107) 新轮清空 cards/events；Fragment 将所有消息先画完、再集中画当前卡 | Task5 按轮次保存消息、结果卡、公开进度快照；当前活动状态与历史结果分离，重放去重；保留 Task8 售后卡逻辑 |
| C-03 进度按文案错误归并 | [AgentChatFragment.kt:139](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentChatFragment.kt:139) distinctBy(summary)；不同状态 label 改变时会重复，同名不同步骤会合并 | Task5 用 runId+stepId 更新；仅 progress 进入步骤列表，不把 intent_decided/text_delta/card 等混作步骤 |
| C-04 事件身份只局部保留；前置兼容缺口 | [AgentMessageService.java:63](C:/fdz-build/backend/src/main/java/com/fandazi/feature/agent/AgentMessageService.java:63) SSE payload 已保留 runId/traceId；[AgentGatewayController.java:16](C:/fdz-build/backend/src/main/java/com/fandazi/feature/agent/AgentGatewayController.java:16) id 是数据库游标；[HttpAgentServiceClient.java:150](C:/fdz-build/backend/src/main/java/com/fandazi/feature/agent/HttpAgentServiceClient.java:150) HTTP event 只取原 payload，未保留顶层身份；原 eventId/sequence 未透传 | **属于 Task1/3 公开桥接最小补正依赖，先报告，未擅自修改 Spring**。公开事件保留原身份和状态，数据库游标继续用于 Last-Event-ID；不能伪造 runId/sequence，也不能笼统宣称 SSE 的 runId 全丢失 |
| C-05 文本/卡片流事件没有归入会话结果 | [AgentViewModel.kt:251](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentViewModel.kt:251) appendEvent 主要更新事件/暂停 summary；applyResult 使用 state.events，未补 result.events；普通 text_delta/card 未立即更新 messages/cards | Task5 对权威事件分别处理，并对 HTTP 完整结果做同一身份归并，避免 SSE+HTTP 重复或 SSE 缺失导致结果断层 |
| C-06 暂停/终态状态不完整 | [AgentModels.kt:11](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentModels.kt:11) 没有独立 CANCELLED；appendEvent 主要依 event.type 判断终态，未完整覆盖 status 终态 | Task5 明确 running、waiting_input、waiting_confirmation、succeeded、failed、cancelled 的 UI 状态；公共兼容映射要保留 nextAction=cancelled，不盲目改公开状态枚举 |
| C-07 真实断流可能无法重试 | [AgentRepository.kt:56](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentRepository.kt:56) 共用零 readTimeout；[119](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentRepository.kt:119) IOException 无限静默重连；parseEvent 非法 JSON 返回 null；[AgentViewModel.kt:199](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentViewModel.kt:199) catch 只写 error 未解除 isSending | Task5 请求与流超时策略分开，重连与不可恢复故障明确区分，协议非法不默默跳过；失败原地可恢复且不复发写动作。必须真实切断/恢复新 Agent 或实际连接验证 |
| C-08 已确认 H 未在聊天内可达 | [MainActivity.kt:36](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/MainActivity.kt:36) 游客 AI 直接跳登录页；[AgentChatFragment.kt:75](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentChatFragment.kt:75) 未渲染游客卡，发送使能仅依忙碌/文本；item_agent_login_gate 仅“立即登录”，没有“稍后再说” | Task5 游客进入同一聊天页显示 H，无业务结果、无真正可用发送；登录返回 AI 原入口。MainActivity/Auth 既存入口需最小联动，保留其他受保护目的地和 Task8 行为 |
| C-09 空态缺核心区块/存在无效入口 | [fragment_agent_chat.xml:50](C:/fdz-build/android-app/app/src/main/res/layout/fragment_agent_chat.xml:50) 仅一段文本；缺 A 头像/欢迎区/三项快捷问题；[88](C:/fdz-build/android-app/app/src/main/res/layout/fragment_agent_chat.xml:88) 添加入口无点击处理 | Task5 获批准后按 56:2 完整可编辑结构补齐，正式页面文字输入；保留历史 ASR 代码但不作为交付 |
| C-10 卡片字段/操作不全 | [AgentCardRenderer.kt:115](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentCardRenderer.kt:115) 订单状态直接原文，124 隐藏按钮；门店缺评价数/团购摘要，套餐缺原价/折扣；ImageLoader 只 placeholder，无明确 error 绑定 | Task5 渲染 Spring 实际字段及面向用户状态，缺失/无图明确占位；真实 ID 点击才导航；订单按钮接既有详情路线，完整订单/券查询仍归 Task7 |
| C-11 通用确认缺失 | renderer 只支持 Task8 after_sale_*，通用 confirmation 落未知卡；ViewModel AWAITING_CONFIRMATION 仍校验外卖 CandidateCart | Task5 公共完整摘要/确认/取消展示独立于旧外卖 cart；保护售后 renderer 和资格判断，实际购买/退款/投诉提交闭环分别归 Task6/8 |
| C-12 现有测试不能证明本期全页 | AgentChatStateTest 只有两场景且明确要求新轮清卡；HttpAgentRepositoryTest 用 MockWebServer；maestro/task5-ai-chat-page.yaml 使用固定测试账号/密码和旧答案断言 | Task5 定向补状态/重试/多轮/重放边界，并重写真实聊天流程；随机隔离账号凭据仅忽略 .runtime，不将替身或旧单空态作为真实失败恢复 PASS |

保留现有 Repository/ViewModel/ApiClient、消息气泡、卡片、真实 HTTP 接口以及单一 agentChatFragment 路由。没有重建第二聊天路由。底部导航已由 Activity 提供，后续实现不得在 fragment 内复制第二套 Tab。

## 5. 必要依赖和确认后执行顺序

1. **人类结果先到。** 控制端传递九个 Frame 的明确批准范围/版本或逐页修改意见；若只批准部分，仍不得实施其他草稿。H 的当前坐标故障由控制端协调确认最小修复范围，保留其已有确认。
2. **Figma 先于 XML。** 获批准后读取 figma-design-to-code，逐页 get_design_context 携带 skillNames 与截图，以完整可编辑图层映射 XML。缺失原价/折扣区或新增确认/取消状态先按设计边界补齐，不能贴整张位图。
3. **前置兼容最小补正先定归属。** C-04 的公开事件身份是实现 eventId/sequence 去重和 HTTP/SSE 同一归并的必要依赖。报告具体所属 Task1/3 与最小字段透传范围后，由控制端协调；本 Agent 不静默扩展 Spring 或更改主计划。
4. **实现公共状态。** 在现有模型/仓库/ViewModel 中区分任务、输入轮次、传输游标和业务步骤；状态正常暂停不写错误，终态停动画，保留历史结果。仅对非 UI 协议/状态逻辑使用必要的测试先行；UI 不采用 TDD。
5. **实现单聊天页映射。** A–J、公用等待补充/确认/取消展示、游客登录返回、真实卡片点击导航和无自动跳转。所有新增/修改模块、公开函数、核心分支、转换、测试及配置说明使用准确中文注释。
6. **真实环境开发自测。** 控制端确认后本 Agent 独占源码/设备/Gradle/Maestro/服务操作。导入 Initialize-FandaziEnvironment.ps1，显式 TASK2_FIXTURES_ENABLED=false、TASK8_FAILURES_ENABLED=false。仅 Android→Spring8080→D 新 Agent8091 service.app:create_app。真实只读账号随机注册隔离；不得使用 Task8 原七场景/八券清单。
7. **收尾与独立验收。** 冻结 Task5 写集，停止本任务测试并释放使用权，把实际证据交控制端另起独立验收 Agent。独立通过后控制端提交，本 Agent 不 stage/commit，不自行开始 Task6。

未来定向 Android 检查命令模板（**本轮未执行**）：

```powershell
# 确认后才运行；沿用指定 JDK 与本次隔离环境，UI 不按 TDD 开发。
$env:JAVA_HOME = 'C:/Users/caid0/.jdks/ms-17.0.17'
$env:TASK2_FIXTURES_ENABLED = 'false'
$env:TASK8_FAILURES_ENABLED = 'false'
. C:/fdz-build/scripts/Initialize-FandaziEnvironment.ps1
# 从 C:/fdz-build/android-app 执行定向开发自测。
./gradlew.bat :app:testDebugUnitTest --tests 'com.fandazi.app.feature.agent.HttpAgentRepositoryTest' --tests 'com.fandazi.app.feature.agent.AgentChatStateTest' --tests 'com.fandazi.app.feature.agent.AgentViewModelTest'
./gradlew.bat :app:assembleDebug
# 卸载旧 com.fandazi.app 和干扰 com.dirror.music 后安装最新 APK，记录 SHA/安装时间，再执行真实聊天流程。
maestro test C:/fdz-build/maestro/task5-ai-chat-page.yaml
```

本轮不安装 APK，因此卸载约束未触发。全量 Maven 仅在本期适用回归阶段使用 Task4 已验证的隔离快照方式，不读取/覆盖原 .runtime、target、链接或真实夹具。没有意图、阈值、耗时或并发实验，本轮不修改 D 实验文档；后续若开展实验另追加，保留 EXP014/015。

## 6. 需求覆盖与待测清单

| 要求 | 本轮事实 | 后续必须取得的证据 |
| --- | --- | --- |
| 十页完整 Frame 与确认记录 | 本轮实际节点/尺寸/截图已核对；九页仍待确认，H 有当前裁切缺陷 | 具体批准依据；修复后的可用基线 |
| 空态/文本/门店/套餐/订单/多轮 | 现有源码已读，缺口见 C-02/05/09/10 | 最新 APK 状态截图，真实后端卡片字段，点击才导航 |
| 真实进度/暂停/取消 | 已核对 1.1 契约及当前适配缺口 | runId+stepId 更新、eventId/sequence 去重、真实等待和终态停动画 |
| 游客 AI 拦截/登录返回 | 入口直接登录与 H 实现缺口已登记 | 无套餐、不可发送、立即登录/稍后再说、返回原 AI 入口 |
| 问候/推荐/缺城市/补充/至少一次多轮 | 本轮未发业务请求 | Spring/new Agent 事件与实际输出，推荐单目标 is_composite=false |
| 确认/取消展示 | 仅静态核对旧售后与外卖兼容 | 隔离真实准备接口摘要，不提交购买/退款/投诉 |
| 真实失败/断线/原地重试 | 本轮未断服务、未操作设备 | 新 Agent不可达及实际连接中断→错误→恢复→一次重试；用户气泡和历史结果无复制 |
| 定向测试与适用回归 | 执行次数 0，非 SKIP/PASS | 实际命令/输出/原失败及修复，Mock 测试与真实 E2E 分开 |
| 最新 APK/安装/SHA | 本轮未构建、未安装，无最新 APK 声明 | 本轮构建 SHA-256、卸载/安装时间和 emulator-5554 运行截图 |
| 独立验收 | 未运行，由控制端协调 | 独立 Agent 亲自测试与逐屏对照后明确结论 |

## 7. 本轮实际运行来源与验证限制

只读系统复核得到：8080 监听 PID53264，java.exe/IntelliJ JBR，进程标识包含 Spring Boot；8091 监听 PID28744，Python pinnT，命令标识包含 service.app:create_app、--factory、8091，不包含旧 fandazi_agent/agent-service。没有打印完整命令行或环境密钥。

通过无代理 GET http://127.0.0.1:8091/internal/v1/health 得到：

```json
{"status":"ok","schemaVersion":"1.1","service":"group-buying-agent","sourceRoot":"D:\\project\\-Demo-master","entryPoint":"service.app:create_app","buildVersion":"8265f08-working-tree"}
```

这是本轮实际健康响应，只证明来源/入口与健康路由。buildVersion 为已有环境标签，与 Git HEAD 88c386e 不同，不能据此宣称当前进程已加载最新提交代码；后续获准重启时须记录准确运行来源与版本。Spring 未发现单独健康路由，本轮只复核监听/进程身份，**未证明其数据库、业务接口和 Spring→Agent 链路健康**。

未调用任何业务请求、写接口或断开连接。没有占用设备、Gradle、Maestro，未启动/停止/重启共享服务，无需释放本轮设备锁或进程；已有服务保持原状。

## 8. 命令、异常及写集

首次报告落盘前累计：exec_command **67 次**，Figma 工具 **9 次**（get_metadata 2、只读 use_figma 7）；Android/Gradle/Maven/Maestro/业务请求测试执行 **0 次**。收尾只读摘要/报告核查另外记录到 preflight-checks.md，不能以审计计数代替测试覆盖。

保留实际异常及修正：

- Figma 默认查询只返回空 0:1 Design Tokens，两次检索无聊天节点；改用已有记录中 6:8 并 setCurrentPageAsync，成功定位十页。默认未加载页面不能判定文件为空。
- 首次全量 AI 图层响应截断，JSON.parse 产生 Unterminated string；改为有界关键图层输出，取得可解析证据，无画布写入。
- 一次 10 张截图调用仅回传 5 张图片；定向补取 F–J，本轮可见证据共十页，未重复 A–E。
- 初始假设的 AgentController.java、AgentEventStore.java 不存在；先 rg --files 定位实际 AgentGatewayController.java、AgentEventService.java 后重读。该 PowerShell 非终止错误不能作为测试通过证据。
- 两次 Health 源码检索无匹配（rg exit 1），不把无路由写为 Spring 服务故障。
- 首次逐文件 SHA 清单输出截断，弃用截断 JSON，改为文件数+排序路径/内容 SHA 的总摘要并收尾重算。
- git status 对既存 .pytest_cache 有访问告警，未改变权限或删除缓存；dirty 快照仅覆盖 Git 可列出项。

完整写集见 [task5-write-set.txt](C:/fdz-build/docs/verification/task5-evidence/task5-write-set.txt)。本轮 Android、Spring、新 Agent 源码写集为空；证据保存前后摘要用于佐证，没有修改 Task8 实验记录/真实夹具。待人类结果传递后从该检查点继续 Task5，未开始任何 Task6 工作。
