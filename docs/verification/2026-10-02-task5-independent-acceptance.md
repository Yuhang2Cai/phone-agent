# Task5 独立验收报告

日期：2026-10-02。角色：全新独立验收者，非实现者。验收对象为冻结实际工作区及指定 APK，不是干净 BASE，也不是开发报告中的自测结果。阅读了 acceptance brief、完整需求 brief、交付报告、349190 字节审查包、开发验证报告及 scope-boundary.md。按真实前像增量审查，不把累计 dirty 应用全部归 Task5。

## 结论

| 维度 | 结论 | 依据 |
| --- | --- | --- |
| 需求符合性 | **FAIL** | 多轮历史保留后，已取消旧摘要的按钮仍可作用于新待确认任务，破坏共享确认/取消与任务归属。 |
| 代码质量 | **FAIL** | 历史卡片重绘未区分是否仍有效，按钮只发送泛化文本而不校验所属任务；现有通过测试没有覆盖这一交互。 |
| 实际运行 | **FAIL** | 最新 APK + Maestro + 真实双服务复现：数量 1 旧卡取消了数量 2 新任务，数据库前后只读快照直接证明。 |
| 整体 | **FAIL** | 已发现并确认 1 个 P1 阻塞；按 brief 停止继续扩展验收，不修产品，不宣称完整验收通过。 |

Android/Maven/适用 Agent 回归均有独立通过证据，但不能覆盖或抵消上述实际交互失败。F 的未提交依赖本身不被直接判为当前功能阻塞：冻结版实际点击成功，提交抽取方案尚未构建验证。

## 阻塞证据

**P1：已取消的历史确认卡仍能取消当前新任务。**

1. 同一设备会话准备真实套餐，数量 1，摘要 ¥90.00；点击其“取消本次操作”，得到“已取消本次操作”。
2. 保留历史，准备同套餐数量 2，摘要 ¥180.00；服务器当前任务 `7e1c225b-01eb-4490-9918-c05e03a361ba` 为 `waiting_confirmation`。
3. 向上滚动至数量 1 的旧摘要。ADB 层级证明屏幕仅有该数量 1 摘要，其取消控件 `enabled=true, clickable=true`，边界 `[680,1027][996,1132]`。
4. Maestro 点击这个旧控件。相同当前任务变为 `cancelled`，服务器摘要仍为数量 2、18000 分；不是用户点击了数量 2 卡，也不是等待确认超时。两次操作在服务器摘要截止时间前完成。

设备操作命令：`Invoke-IndependentDevice.ps1 -Mode Flow -Flow <evidence>/stale-prepare.yaml -Label stale-prepare`；旧按钮操作：同一命令换 `stale-cancel.yaml` / `stale-cancel`。操作前后经环境初始化运行 `independent-session-snapshot.py stale-before` / `stale-after`，查询仅限定本验收随机账号，无数据库写入。

证据：[旧卡可用截图](C:/fdz-build/docs/verification/task5-independent-evidence/maestro-stale-prepare/task5-stale-A-old-actions.png)、[新摘要截图](C:/fdz-build/docs/verification/task5-independent-evidence/maestro-stale-prepare/task5-stale-B-prepared.png)、[误取消后截图](C:/fdz-build/docs/verification/task5-independent-evidence/maestro-stale-cancel/task5-stale-old-cancel-result.png)、[结构化失败断言](C:/fdz-build/docs/verification/task5-independent-evidence/stale-card-finding.json)、[前快照](C:/fdz-build/docs/verification/task5-independent-evidence/stale-before-sessions.json)、[后快照](C:/fdz-build/docs/verification/task5-independent-evidence/stale-after-sessions.json)。原始设备层级与完整会话摘要仅在 `.runtime/task5-independent`。

根因与精确归属：

- [AgentChatFragment.kt:110](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentChatFragment.kt:110) 重绘历史；117 行对所有历史 `message.cards` 调用同一 renderer，158 行直接把 `AgentReply` 转成 `sendText`。
- [AgentViewModel.kt:258](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentViewModel.kt:258) 将准备卡保留在消息；271 行终态继续保留历史，清空当前 confirmation 不会撤销历史按钮。
- [AgentCardRenderer.kt:169](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentCardRenderer.kt:169) 仅判断摘要字段齐全；178 行确认可用，180 行发“确认”，184 行取消发“取消”，没有所属 run 或有效性约束。共享 `confirmation` 分支及 `renderConfirmation` 是本轮真实前像增量（own-source.patch 中新增），不是把旧 Task6/8 全部分支算成本轮。
- 既存服务按当前暂停会话恢复：[AgentSession.java:62](C:/fdz-build/backend/src/main/java/com/fandazi/feature/agent/AgentSession.java:62)。[AgentGatewayService.java:22](C:/fdz-build/backend/src/main/java/com/fandazi/feature/agent/AgentGatewayService.java:22) 使用当前 run，30 行传当前请求确认上下文。这解释旧按钮为何落到新任务，不表示这些既存恢复逻辑全属 Task5。

确认按钮采用同样无绑定的文本通道，存在错误确认当前摘要的风险；**没有点击确认，没有执行或声称复现购买/付款/退款/投诉写入**。实测成立的是误取消，不把风险推断写成已发生交易。修复后需独立复验旧卡终态、新摘要替换、跨 run 按钮无效性、合法当前确认/取消及原事件兼容回归。

## 独立运行与计数

可复现运行器均在 `docs/verification/task5-independent-evidence`，带中文说明；敏感日志和 XML 均在 `.runtime/task5-independent`。以下数字来自本轮亲跑解析，不引用开发 49/153 作为验收。

| 独立命令/路径 | 实际结果 | 公开证据 |
| --- | --- | --- |
| `Invoke-IndependentChecks.ps1 -Mode Android`，指定四个聊天/HTTP/state suite，`--rerun-tasks` | **49/49**，失败/错误/跳过均 0 | `android-directed-summary.json` |
| 同运行器完整 `:app:testDebugUnitTest --rerun-tasks` | **158/158**，失败/错误/跳过均 0，不是开发报告的 153 | `android-full-summary.json` |
| `Invoke-IndependentChecks.ps1 -Mode Maven`，全新同源快照 `mvn.cmd -q test` | **215/215**，失败/错误/跳过均 0 | `maven-full-summary.json`、`maven-snapshot-integrity.json` |
| `Invoke-IndependentChecks.ps1 -Mode Agent`，指定 9 个适用新 Agent 测试文件 | 初跑 111 项：110 通过、1 跳过，0 失败/错误 | `agent-summary.json` |
| 设置 `MOBILE_PROJECT_ROOT=C:/fdz-build` 后重跑 `tests/test_cross_repo_contracts.py` | **34/34**，0 跳过，包含初跑被跳过的 authority 对照；不将重叠样例加成 145 个独立测试 | `agent-cross-repo-recheck.json` |
| `independent-api.py`，Spring8080→新 Agent8091 | 11 请求预期状态正确，41 张门店/套餐逐 ID 重查事实，原事件 9/9 配对；未观察到禁止写工具 | `api-summary.json`、`api-results.json`、`card-source-checks.json` |
| 指定最新 APK 安装 + 原始规定 Maestro + 真实故障恢复 | 7 个正向流程退出 0；2 个额外旧卡复现流程退出 0，但数据库业务断言 **FAIL**，不能写“9/9 验收通过” | 各 `maestro-*/result.json`、`stale-card-finding.json` |

新 Agent 适用测试文件：`test_agent_service_api.py`、`test_agent_service_reads.py`、`test_agent_service_dag.py`、`test_compound_intent_requests.py`、`test_cross_repo_contracts.py`、`test_confirmation_time_precision.py`、`test_group_buying_read_tools.py`、`test_spring_tool_client.py`、`test_service_mysql_settings.py`。确切调用见运行器；不存在旧服务替代运行。

初次失败/纠正必须保留解释：Agent 唯一 skip 因缺 `MOBILE_PROJECT_ROOT`，已设置后亲跑补齐；独立 API 运行器曾有语法括号、误以为响应顶层有 runId、误用详情 URL 404、辅助变量覆盖函数等脚本问题，只改验收运行器并使用已保存真实响应完成断言，非产品问题。B 首次层级采样因等待 UI 空闲未捕获运行态，退出 1，不算 B 证据；后在真实 NDJSON 200 已建立时即时 ADB 取两帧成功。首次一次性 Figma 合并输出被截断不可解析，未作完整图层证据，随后逐 Frame 重新取得完整树。

## 原事件 9/9 与兼容工具成功

真实本人订单查询，runId `c3713104-95b2-46f1-b334-4ecb58ec3659`、traceId `67fa4dd5-e6b7-40f8-bf61-0e850d96c75d`。逐行同时核对 HTTP 与 SSE 的 eventId、sequence、runId、traceId、status，全等。完整记录：[event-identity.json](C:/fdz-build/docs/verification/task5-independent-evidence/event-identity.json)。

| sequence | 原 eventId | SSE 数据库游标 | HTTP/SSE status | 配对 |
| --- | --- | --- | --- | --- |
| 0 | `6cf9412c-58ef-472b-aebe-dc68b6a9d469` | 2625 | running | 相同 |
| 1 | `83b6f8f3-ae63-4289-8c32-35ad0bd007eb` | 2626 | running | 相同 |
| 2 | `1d0a7648-5a4d-41b4-8734-a45fee7fdc56` | 2627 | running | 相同 |
| 3 | `c2ff532d-be67-4ed9-85e3-7c107cff34b5` | 2628 | running | 相同 |
| 4 | `db1e227c-1bc4-4454-a041-0b78134f8687` | 2632 | running | 相同 |
| 5 | `ad4c9162-0c67-4e51-babb-454b21272388` | 2634 | running | 相同 |
| 6 | `f27175c8-ec31-448e-8a95-280f2de81f86` | 2635 | running | 相同 |
| 7 | `35944437-af5d-49c1-a0c5-afd33ac8c7bb` | 2636 | running | 相同 |
| 8 | `6d29fa41-0f25-42b3-9356-56a45cae2ed0` | 2637 | succeeded | 相同 |

另有兼容 parsed 游标 2624、tool_result 游标 2633、completed 游标 2638；无原 eventId/sequence，**不冒算成 12/12 原事件**。游标 2633 的 `list_user_orders` 工具 status=succeeded 后仍有原序列 5–8，随后任务终态；工具成功不等于任务完成。端侧专门测试 [Task5ChatStateTest.kt:118](C:/fdz-build/android-app/app/src/test/java/com/fandazi/app/feature/agent/Task5ChatStateTest.kt:118) `successfulGatewayToolAuditDoesNotFinishRunningTask` 本轮亲跑通过。Last-Event-ID 仍取数据库游标，未换成原 sequence。

API 实测：缺城市及补充使用相同 runId/新 trace；问候及之后的新输入保留历史。普通套餐推荐虽调用多个工具，`isComposite=false`；同时查门店与套餐的真实复合请求为 true，缺参→恢复实际返回 34 卡及目标/进度。没有按工具数量伪造复合状态。去重、异 run 同文案进度、迟到事件等由定向 tests 亲跑覆盖，UI 不存在所有竞争条件的人工穷举证据。

## Figma 当前十 Frame 与设备状态

亲自通过 MCP 获取文件 `FxsWKGs10z7BL1MhFAOqFN`、07 AI 助手页面 A–J 当前截图和完整可编辑图层树，每帧 393×852；逐屏查看设计与本轮设备 PNG。公开 `figma/A.png` 至 `J.png`、`A-full-layers.json` 至 `J-full-layers.json` 为有效证据；`figma-current-layers.json` 是首次截断失败尝试，不能替代它们。原 metadata 也保留。

下表截图路径均相对 [独立证据目录](C:/fdz-build/docs/verification/task5-independent-evidence)。命令 F1=`-Mode Flow -Flow task5-ai-chat-page.yaml -Label main`；F2=`task5-ai-read-cards.yaml / read-cards`；F3=`task5-ai-composite-prepare.yaml / composite-prepare`，由设备运行器调用原 Maestro，未改产品或原流程断言。故障使用 `-Mode Failure -Kind unreachable` 及 `interrupted`。

| Frame/node | 入口与真实数据 | 命令 | 实际截图与结论 |
| --- | --- | --- | --- |
| A / 56:2 | 游客 AI→立即登录→账号密码登录后返回唯一 AI，空消息 | F1 | `maestro-main/task5-A-empty.png`；标题、空态、快捷项、输入及底栏完整，非第二聊天页。 |
| B / 56:46 | 真实模型请求已经建立 NDJSON200，处理中 | interrupted | `task5-B-running-1.png`、`2.png`；真实进度与原生忙碌图标，两个阶段，非事后把暂停图叫运行态。 |
| C / 56:95 | hello→实际“你好，我是饭搭子助手。” | F1 | `maestro-main/task5-C-text.png`；用户/助手气泡与真实回复，无硬编码示例替代。 |
| D / 56:134 | recommend nearby restaurants→目录城市 demo→真实门店 | F2 | `maestro-read-cards/task5-D-store.png`；图片缺省、名称与实际字段，显示后仍在聊天，点击才进该门店并可返回。 |
| E / 56:180 | 缺城市套餐推荐→demo→真实套餐 | F1 | `maestro-main/task5-E-deals.png`、`task5-deal-click-only.png`；真实价格/原价折扣/销量等，缺图不伪造；点击才跳对应详情。 |
| F / 56:225 | 本轮独立用户查询唯一隔离待支付订单 | F2 | `maestro-read-cards/task5-F-order.png`、`task5-order-click-only.png`；订单 ID、9000 分、待支付及 0 券与事实一致；显式进入订单详情并返回。 |
| G / 56:269 | 同时推荐门店/套餐，缺参后补 demo；另 prepare 真实套餐 | F3 | `maestro-composite-prepare/task5-G-composite-paused.png`、`task5-G-composite-result.png`；实际目标和进度，未用工具数假冒复合。 |
| H / 56:318 | 卸载重装后的游客→AI 登录门槛→稍后再说/立即登录 | F1 | `maestro-main/task5-H-guest.png`；保留人类确认的 H 门槛区块，无套餐结果，两个入口实点可用，登录回原入口。游客禁发源码成立，未另跑单独点击禁用发送的负面流程。 |
| I / 56:363 | 真新 Agent 不可达；已经建立的 NDJSON 被停进程中断→恢复后原地重试 | 两种 Failure | 两组 `maestro-*-error/task5-I-error.png`、`maestro-*-recovered/task5-I-recovered.png`；友好错误与重试，真实恢复输出；用户气泡前后均 1，输入可用，未见凭据或内部堆栈。 |
| J / 56:404 | 同 run 补城市、点套餐返回、再次 hello，保留多轮历史 | F1 | `maestro-main/task5-J-needs-input.png`、`task5-history-return.png`、`task5-J-multi-turn.png`；旧卡/文本保留及真实上下文，无自动跳页。 |

视觉判断：核心区块、底栏、输入、主要卡片/按钮未发现严重比例、溢出或互相遮挡；长结果可滚动，局部屏幕边缘裁切是滚动视窗，不当作假数据/缺失。轻微尺寸/颜色不构成阻塞。当前确认摘要门店、套餐、数量、金额、规则版本、到期均取实际 prepare，正常取消可停止忙碌；但历史按钮有效性 **FAIL**，不是设计容差。两组故障恢复公开结果中输入 enabled=true，真实中断恢复后 ProgressBar 数 0；不可达早期计数器曾按不存在的资源 ID 检查，不将该计数单独作为动画证明。

## F 最小可提交依赖边界

这是只读调用链的**代码级边界核对**，不是已经制作/提交/构建过的干净 BASE 补丁。当前实际 F 调用：`AgentCardRenderer.Order → groupOrderDetailFragment(orderId) → GroupOrderDetailFragment.load → VoucherRepository.getOrder → GET group-orders/{orderId}`。原依赖工作区没有可引用的独立提交号，只按冻结 SHA 和下面 hunk 确认。资产 `group_buy_card_background.xml` 和相关基础颜色已在手机 BASE，不必借机重交整份资源/应用。

| 必要只读依赖 | 最小需要带入或由先行提交满足 | 应留原 Task6/7/8 或延期项 |
| --- | --- | --- |
| [GroupOrderDetailFragment.kt:19](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/voucher/GroupOrderDetailFragment.kt:19) | 类/所需 imports；20 行统一仓库；23–29 生命周期；32–47 参数→GET→标题/金额/错误；53–57、59 券列表只读投影（相应注释须如实反映只读职责） | 49–52 评价资格 UI+reviewFragment 跳转属 Task8；58 行 voucherDetailFragment 点击属 Task7。保留完整原文件会连带依赖这些资源 ID，不能隐去。 |
| [VoucherRepository.kt:12](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/voucher/VoucherRepository.kt:12) | getOrder 协议与24行 GET；38 URL编码；40–55 toVoucher/toGroupOrder；57可空字段；61–67 mapJson；必要 ApiClient/ApiResult/JsonParser imports和构造 | listOrders 11/20–22、getVoucher13/26为 Task7非 F 必需；display-token14/28–32、redeem15/34–36及仅为核销使用的 Gson 依赖为延期项，不搭售。接口/实现须成对取同一只读边界，不能只删实现留接口。 |
| [VoucherModels.kt:5](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/voucher/VoucherModels.kt:5) | VoucherSummary 5–22、GroupOrderSummary25–43及只读事实字段，供嵌套详情解析；canReview/canRefund是后端资格事实，不等于开放写流程 | 46行 VoucherDisplayToken为二维码/核销延期；评价/退款动作与页面不因保留事实字段归 Task5。 |
| [fragment_group_order_detail.xml:3](C:/fdz-build/android-app/app/src/main/res/layout/fragment_group_order_detail.xml:3) 与 [item_voucher_row.xml:3](C:/fdz-build/android-app/app/src/main/res/layout/item_voucher_row.xml:3) | 标题、金额、状态、券列表滚动容器和只读券行；使用 BASE已有背景/颜色 | detail XML7–10评价入口及 after_sale_primary 关联属 Task8；不能为此把售后资源整包归 Task5。 |
| [FandaziApplication.kt:20](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/FandaziApplication.kt:20) |20–21 Voucher imports、45–46属性、65初始化，共用现有认证 ApiClient |18–19、43–44、64购买仓库属Task6；22–23、47–48、66–67售后仓库属Task8。本轮未改此文件。 |
| [nav_graph.xml:47](C:/fdz-build/android-app/app/src/main/res/navigation/nav_graph.xml:47) |47–49恰好三行 order详情注册和 string orderId |40–46 checkout/payment/success属Task6；50–52券详情属Task7；53–60 QR/redemption延期；62–75退款、评价、帮助、投诉属Task8。本轮未改导航。 |

最小“可提交”需控制端在保留准确历史所有权的前提下，选择先提交上述只读依赖版本，或协调所有者提取只读 hunks；本验收没有修改、stage、commit，也没有编造其可编译验证。若选择原完整 GroupOrderDetailFragment，必须显式声明 review/voucher 导航及资源的传递编译依赖，不能称“只有三行 nav”即可独立提交；若选择真正只读边界，应一起排除相应点击和评价 UI，保留正确接口/实现/模型闭环。后台 GET 的既存只读端点同样必须先满足，不把后台购买/退款写 API 整体算 Task5。

其余共享 Agent 模型/renderer/viewmodel 中已有购买授权、退款投诉前像保留，不冒领 Task6/8；本轮只审查 own-source.patch 的聊天与共享展示增量。scope-boundary 明确不承诺干净 BASE 可编译，这一点与本轮结论一致。没有因“依赖未提交”提前判提交交付 FAIL；当前确证阻塞是上面的新共享摘要历史按钮错误。

## 非阻塞与未覆盖

- F聊天卡自身展示“合计/待支付”正确；原 Task7订单详情47行把待支付金额叫“实付”，实际截图可见，是既存页面债务，不能用 F点击通过替代 Task7验收，也不将其记为本轮新增 Task5阻塞。券状态57行粗投影同属原页面。
- 尚未单独关闭模拟器 wifi/data 做端侧离线恢复。已经真实断开服务器已建立连接并恢复，**不等同宣称完成设备全离线测试**。发现 P1后按brief停测，该额外分支及游客禁用发送实点击仍待修复后补齐。
- 原流程条件 query 澄清分支、本轮未触发的购买条件分支为 SKIPPED，不计业务成功。不执行 Task6/8写闭环；购买确认仅检查呈现/协议和风险分析，不声称真正点击交易确认已验收。
- ASR、二维码/核销延期；Task6购买支付、Task7券完整流程、Task8退款/评价/投诉闭环不在本任务范围。不清库，不用原Task8券写入。

## 环境、隔离及释放

手机 HEAD `c614bd57218dd315e4ce03a60537023526cd6d49`，新 Agent HEAD `ee04c2cd0fa0232e857da915c4ca2773842afb28`；APK SHA256 `8F5FF97EB6FD42B88022B168C2E8547665D18CBE6EBC33C4A8C800BA09B4F7F9`。卸载旧 `com.fandazi.app` 和干扰包 `com.dirror.music`，确认移除后于 06:28:03+08 安装；从设备拉取已装 base.apk，哈希一致，见 `apk-install.json`。

两服务启动前显式 `TASK2_FIXTURES_ENABLED=false` / `TASK8_FAILURES_ENABLED=false`；环境从指定 Initialize脚本取得，HTTP禁代理。新 Agent健康自报 `sourceRoot=D:\project\-Demo-master`、`entryPoint=service.app:create_app`，未启动旧服务或替身。故障操作只停经进程命令确认的新 Agent，使用既有隐藏窗口脚本恢复。

独立账号只产生本轮隔离待支付展示夹具：订单 `163e4efb-6479-462e-bd87-b5a36bc290e8`、真实目录套餐/门店、9000分、数量1、0券；没付款/扣库存/生成券。prepare/取消只用于共享展示及 P1复现。密码、JWT、内部凭据、完整原始资料仅在忽略 `.runtime`，公开 API经过敏感键递归过滤。

Maven在 `.runtime/task5-independent/spring-snapshot-<独立GUID>` 原样复制 backend/src、pom、contracts、data/seed，并包含仅供既存客户端兼容测试导入的 agent-service/src；**复制该客户端源码不代表启动旧 Agent**。306份文件复制前后源/副本均同 SHA，快照0链接，不复制 target/runtime，不改测试和断言；H2一次性文件仅留快照，不触碰原 Task2/8 清单。

最终 `fingerprint-after.json`：44项冻结条目（31产品/测试与13运行器）、12项必要依赖、7项保护文件、70项新 Agent文件均0不一致，前像指纹有效，HEAD和APK均不变；源规则/阈值/state/12类/17工具未改。两个仓库 cached diff均空，没有stage/commit、回滚或子Agent。

06:41:40+08 已 force-stop 本应用以关SSE，Gradle daemon已停止，没有遗留本轮Maestro/Gradle/Maven测试；服务健康保留供控制端，Spring8080 PID44776、新Agent8091 PID54004，见 `environment-release.json`。环境所有权已释放，产品仍冻结未修复。下一步由控制端安排修复上述 P1，再做独立受影响范围与协议复验；本报告不得据测试通过计数勾选Task5完成。

## Fix Round1 独立复验（2026-10-02，最新结论）

本节为修复后追加，**不删除或改写上文 R0 的 FAIL、原 P1、9/9 配对及失败证据**。R0结论描述当时 APK；最新 Task5 结论以本节为准。按控制端指定范围复验，没有重新运行已经通过的全量 Maven、全量 Android、新 Agent完整回归或重新获取十 Frame全量。

### 最新判定

| 维度 | 最新结论 | 独立依据 |
| --- | --- | --- |
| 原 P1 | **ADDRESSED** | 真实旧取消与旧确认实点击后，数量2原run及trace/摘要/等待状态完全不变；合法当前取消后同run cancelled。 |
| 需求符合性 | **PASS** | 历史展示与可执行资格分离，终态/新输入/版本替换/跨run守卫亲跑；前次游客禁发实点击和端侧全离线恢复缺口已补齐。 |
| 代码质量 | **PASS** | 独立审查R0→R1四产品文件精确diff，没有发现新的明确阻塞；确认/取消共用原身份+完整摘要校验并在VM入口二次守卫，中文新增注释与逻辑相符。 |
| 实际运行 | **PASS** | 最新APK严格卸装和回拉一致；64/64受影响回归、11个Maestro流程、真实DB断言、4轮新HTTP及原11/11事件配对均通过。 |
| 整体 Task5 | **PASS** | 综合R0已通过且未受影响的证据与本轮复验，原唯一P1已独立关闭；不包含延期项或Task6/7/8交易闭环。 |

公开R1证据：[fixround1目录](C:/fdz-build/docs/verification/task5-independent-evidence/fixround1)、[结构化独立结论](C:/fdz-build/docs/verification/task5-independent-evidence/fixround1/independent-r1-result.json)。原始日志、XML、完整摘要、账号/JWT、网络原始资料只留 `.runtime/task5-independent-r1`。

### 冻结对象与边界

亲读SDD `task5-review-package-r1.txt` 全部修复diff/覆盖与命令、开发报告最新Fix Round1、`task5-evidence/fixround1/freeze-manifest.json`。并逐项核对修复前像与**本独立R0实际fingerprint-after**，不是只相信开发者before值。

最新APK SHA256：`840D115471CE8BE02ADE51B2CB3E5CF6D3D913EE4D1CA74375DF9250A130A724`。07:09:44+08卸载旧应用及干扰包、确认不存在后安装，设备base.apk回拉哈希完全一致，见 [apk-install.json](C:/fdz-build/docs/verification/task5-independent-evidence/fixround1/apk-install.json)。

51源/运行器累计SHA：`079B60CBF024247094C70D26867303862797BBE20837A78A10CB0FC407A23303`。本轮修复delta11项，其中4产品、1专项测试、6证据运行器/流程；本验收不改它们。前后均51项/12依赖/7保护文件/70新Agent文件无不一致，4个产品前像与独立R0一致；两端HEAD、暂存区均未变，见 `fingerprint-Before.json` / `fingerprint-After.json`。

只审R0→R1修复增量，原F只读提交边界章节继续有效：没有将nav/FandaziApplication或原购买/退款/评价 dirty hunks冒领为R1，仍不承诺干净BASE直接编译。Spring、新Agent、XML、Figma与17工具无本轮产品变化。

### 原 P1 的真实复验

设备会话 `c36750da-a82c-4ce0-b39b-af4bb0ad42f1`，数量2当前run `1dd1277f-ab22-4736-a13a-7ce9728611e6`，原trace `caa15092-b7ca-46a0-9eb8-4f6af515bf34`，quantity=2、amountCents=18000。

| 实测阶段 | UI与实际DB结果 | 证据（本节目录下） |
| --- | --- | --- |
| 数量1准备→合法取消→数量2准备→回看旧数量1 | 原数量1事实¥90保留，旧确认和取消都enabled=false；当前数量2服务器waiting_confirmation | `maestro-stale-prepare/`、`before-old-tap-ui.json`、`before-old-tap-sessions.json` |
| 实际点击旧数量1取消 | **整条白名单会话记录完全不变**，包括当前run、trace、resourceId、金额、规则和截止；不是只比较状态 | `maestro-old-cancel-tap/`、`after-old-cancel-sessions.json` |
| 实际点击旧数量1确认位置 | 先核实enabled=false才点，旧按钮未触发交易；数量2整条记录仍完全不变 | `maestro-old-confirm-tap/`、`after-old-confirm-sessions.json` |
| 回到真正的当前数量2卡 | 确认/取消均enabled=true，输入可用，停止动画；没有把所有按钮一律关闭来规避P1 | `maestro-current-inspect/`、`current-before-cancel-ui.json` |
| 合法点击当前数量2取消 | 同run变cancelled，trace变为`e6d8d3c7-bfa0-4150-9e54-5ae40f8193a0`，quantity2/18000摘要保留；两按钮随后false、ProgressBar0 | `maestro-current-cancel/`、`after-current-cancel-sessions.json`、`after-current-cancel-ui.json` |

旧按钮检查与当前取消均发生在服务器该摘要07:19:07+08截止前，当前终态按钮失效于07:16:29已观察；**不是依靠过期使测试通过**。快照只读本轮新随机账号，查询原始材料不公开，公开摘要白名单及完整比较见 `summarize-r1.py` 的独立断言。

合法当前“确认”不点击真实交易。亲跑 `Task5ConfirmationActionTest.forgedSummaryWrongConfirmAndUnboundReplyAreRejected`：同一个 `replyToCard` 入口拒绝金额篡改、错误确认支付、无绑定取消，接受当前合法确认并在**隔离仓库**记录“确认”。其它专项同时对旧确认/旧取消、同run替换、仅eventId变化、终态、忙碌/待输入、账号切换/过期、SSE重放与SavedState执行守卫。真实UI只检查当前确认enabled=true并实点已失效的旧确认；**不把隔离确认说成真实订单创建/付款通过**。

对应当前代码：[AgentModels.kt:33](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentModels.kt:33)携带sourceCard，73/77行保存原run/trace/confirmation_required.eventId/sequence；[AgentViewModel.kt:123](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentViewModel.kt:123)确认和取消共用动作入口，136行检查当前完整卡和有效阶段，176行新输入撤销，295/320行错误/终态撤销，331–369行SSE替换/重放与终态处理；[AgentCardRenderer.kt:189](C:/fdz-build/android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentCardRenderer.kt:189)和196行双按钮调用同守卫，动作同时传原卡；Fragment138/159行交VM二次验证。没有把身份字段作为真实授权凭据或新增交易参数。

### 亲跑命令与计数

JDK、Python、Maestro、emulator使用brief指定原工具链；双服务始终Spring8080→D项目新Agent8091，`TASK2_FIXTURES_ENABLED=false` / `TASK8_FAILURES_ENABLED=false`，HTTP禁代理。独立运行器均在本节证据目录，不修改原开发运行器。

```powershell
# C:/fdz-build/docs/verification/task5-independent-evidence/fixround1
./Invoke-R1Checks.ps1 -Mode Before
./Invoke-R1Checks.ps1 -Mode Tests
# Tests实际执行八suite、--rerun-tasks，不用缓存退出码冒充重跑。
# :app:testDebugUnitTest --tests '*Task5ConfirmationActionTest'
# --tests '*Task5ChatStateTest' --tests '*AgentViewModelTest'
# --tests '*AgentChatStateTest' --tests '*HttpAgentRepositoryTest'
# --tests '*AgentCardRendererTest' --tests '*AgentAfterSaleCardTest'
# --tests '*AgentLoginGateTest' --rerun-tasks --console=plain
./Invoke-R1Device.ps1 -Mode Install
./Invoke-R1Device.ps1 -Mode Flow -Flow <下表流程> -Label <对应label>
python independent-identity.py
./Invoke-R1Device.ps1 -Mode Failure -Kind offline -Query hello -Expected '你好，我是饭搭子助手。'
python summarize-r1.py
./Invoke-R1Checks.ps1 -Mode After
./Invoke-R1Device.ps1 -Mode Release
```

64/64、0失败/错误/跳过，实际08个suite计数：专项9、聊天状态10、ViewModel26、HTTP11、原聊天2、renderer1、售后2、登录门槛3。原执行XML留私有目录，公开 `targeted64-summary.json` 和 `guard-cases.json`（九项逐case结果），不是开发64/64的转载。

11个Maestro执行单元全部退出0，其中包括取证滚动辅助流程，不冒称11个独立业务闭环：

| label / Flow | 最新APK实际覆盖 |
| --- | --- |
| guest-disabled / `guest-disabled.yaml` | 游客实点输入、发送；仍只有登录门槛，无消息/套餐/错误，输入与发送enabled=false。 |
| main / 原`task5-ai-chat-page.yaml` | H两个登录入口、账号密码返回AI、A空态、C问候、缺城市/补充、E真卡、显式详情返回、J多轮/上下文。 |
| stale-prepare / 原`task5-fixround1-stale-prepare.yaml` | 数量1合法取消、数量2新准备、保留旧卡并滚回。 |
| old-cancel-tap / 原`task5-fixround1-old-tap.yaml` | 原P1旧取消实点，DB确认数量2仍等待。 |
| old-confirm-tap / `old-confirm-tap.yaml` | 已核实失效旧确认实点，不产生交易/新trace。 |
| current-inspect / `current-card-inspect.yaml` | 当前数量2确认和取消仍可用。 |
| current-cancel / 原`task5-fixround1-current-cancel.yaml` | 合法当前取消与终态禁用，DB直接核对同run cancelled。 |
| offline-error / `offline-error.yaml` | 已有准备/取消历史中直接发送，**没有launchApp**，实际模拟器全离线错误。 |
| offline-recovered / 原`task5-ai-retry.yaml` | 不重建页面，网络恢复后原地重试，真实问候回复。 |
| history-after-offline / `history-after-offline.yaml` | 恢复后滚到数量1与2，两个摘要事实与历史仍在。 |
| read-order / `read-order.yaml` | 本人唯一真实待支付订单卡，显式进既存详情并返回，未付款/核销/售后。 |

4轮新HTTP为缺城市NEEDS_INPUT→同run补充COMPLETED→新run准备AWAITING_CONFIRMATION→同run取消COMPLETED；prepare新run `807e9d89-2fa8-4162-9e22-64f0ad5fbf10`、trace `6193badc-3af1-41c7-bc8d-d5b846e3e371`。原11条HTTP/SSE的eventId/sequence/runId/traceId/status逐条相同；4条parsed/两个tool_result/confirmation_required兼容记录另列，无原eventId/sequence，不伪造15/15。原waiting_confirmation事件ID `403af5c7-32ad-47b7-af37-2397555e7529`、sequence10，详见 `fresh-http-sse-identity.json`。原R0本人订单9/9保持原文件不改，两个场景不混算。

两个兼容工具成功后仍继续原事件并等待确认，专项SSE兼容测试及原 `successfulGatewayToolAuditDoesNotFinishRunningTask` 亲跑通过，不把工具成功当作任务终态。未观察到create/pay/refund/complaint/redeem禁止写工具，原数据库游标依然独立于sequence。

### 全离线、历史与受影响设计

关闭模拟器Wi-Fi和移动数据，用Android `dumpsys connectivity` 实测 `Active default network: none`，两个设置均0；**不是停服务器、删除代理或只看开关**。在原P1同一已有历史页面发送hello，友好错误卡“网络连接不稳定，请检查后重试”，没有内部异常/凭据；恢复后默认网络106实际出现，原地点击重试返回“你好，我是饭搭子助手。”。用户hello气泡前后均1，输入true、ProgressBar0；服务器进程未重启。证据 `offline-recovery.json`、`maestro-offline-error/task5-r1-I-full-offline.png`、`maestro-offline-recovered/task5-I-recovered.png`。

首次全离线准备校验曾退出1：svc设置已为0，但立即读取仍显示默认网络103，因此**未启动发送流程且未算覆盖**；finally恢复网络。首次结果和网络原件保留为 `offline-first-attempt.json` 及私有同名前缀。只修独立运行器，增加等待实际默认网络消失后严格重跑成功；不改产品、不掩盖首次失败。其它本轮64回归、APK安装、HTTP身份、11个Maestro均无失败/跳过。

受影响设计只重新通过MCP取得F56:225、H56:318、J56:404当前metadata和截图，没有重取十Frame全量。三图与R0对应PNG哈希均相同，原十Frame完整图层/截图保留。亲看最新设备游客禁发H、多轮J、共享准备旧/新/终态、离线I及普通订单F：核心区块、文字、按钮及底栏没有新严重溢出/遮挡；原事实历史未清除，失效按钮保持文案并淡化、当前按钮正常。

普通F最新截图 `maestro-read-order/task5-r1-F-read-order.png` 展示本轮专属订单 `bfc4c9c5-8d97-46d4-8bf5-33ebfddf0e6e`、待支付、合计¥90；无券有效期明确“未取得”，不编造。详情只读显式点击/返回成功，既存“实付”命名债务仍归Task7，不将它转嫁成R1新增问题。两版摘要恢复后截图在 `maestro-history-after-offline`，游客禁发状态在 `guest-after-tap-ui.json`。

### 未覆盖边界与最终释放

真实当前确认后的订单创建、支付和后续退款/评价/投诉**刻意不执行**，合法确认仅验证共用VM守卫/隔离请求与实际按钮可用，属于本轮明确授权边界，不声称Task6完成。ASR及二维码/核销仍延期；不替代Task7/8独立验收。没有新的本轮必测未完成项；同run精细替换/SavedState等由亲跑真实VM隔离仓库覆盖，不冒称全部由真实服务器UI制造。

原R0的97份公开文件哈希全部不变（`r0-preservation.json`），原独立报告主体另保留私有副本且本次仅追加本节。源冻结前后均0不一致，原Task2/8保护文件和70项新Agent文件不变，没有修改产品、stage、commit、回滚或子Agent。

07:19:45+08已释放：app force-stop关闭SSE、1个Gradle daemon停止，所有本轮Maestro/Gradle/HTTP验证会话完成，无遗留测试；双服务健康保留，Spring8080 PID44776、新Agent8091 PID54004，入口`service.app:create_app`与D sourceRoot一致，网络已恢复。见本节 `environment-release.json`。**本轮独立复验PASS，原P1已ADDRESSED；不以此宣布后续交易任务通过。**
