# Task5 开发报告

## Fix Round1（开发修复自测结束，冻结待独立复验）

**DONE_FIXROUND1，2026-10-02 07:04已释放环境**。64/64受影响定向（专项9），最新APK主聊天及原P1三段Maestro退出0，旧数量1取消点击后数量2run仍waiting_confirmation/quantity2/18000，当前数量2合法取消后同run cancelled。真实确认未执行；待Jason独立复验及额外全离线/游客禁发点击，不声称独立通过。

完整覆盖/未覆盖/精确行号/实际命令/失败边界：[fixround1/coverage-and-commands.md](C:/fdz-build/docs/verification/task5-evidence/fixround1/coverage-and-commands.md)。冻结写集[write-set.txt](C:/fdz-build/docs/verification/task5-evidence/fixround1/write-set.txt)、[freeze-manifest.json](C:/fdz-build/docs/verification/task5-evidence/fixround1/freeze-manifest.json)、[fixround1.patch](C:/fdz-build/docs/verification/task5-evidence/fixround1/fixround1.patch)。累计51文件源码/运行器SHA：079B60CBF024247094C70D26867303862797BBE20837A78A10CB0FC407A23303；设备回拉与最新构建APK均840D115471CE8BE02ADE51B2CB3E5CF6D3D913EE4D1CA74375DF9250A130A724。

新准备run原11条HTTP/SSE身份全部一致，4条网关兼容另列；原9条及false/P1均保留。四产品新增修复hunk外，一个专项测试+六运行器/流程为本轮新增；共享导航无本轮hunk，原Task8夹具7文件和D70源/测试/契约SHA不变。无产品待修项；待独立验收。Spring44776/D8091 54004健康留接管，无测试/Gradle占用。下方R0及只读检查点均为历史过程。

独立报告 `2026-10-02-task5-independent-acceptance.md` 精确指向 Fragment110/117/158、ViewModel258/271、Renderer169–184。已确认根因：历史共享confirmation卡只发送泛化语句，新暂停任务接管后旧卡仍可操作它。数量1→取消→数量2准备→旧数量1取消已被Jason真实复现；确认风险未真实执行交易，不改写独立报告或原失败证据。

本轮仅修此P1及修复引起的兼容问题。实施前四个产品文件字节副本 `.runtime/task5-fixround1-before`，上一冻结manifest另存fixround1/previous-freeze-manifest.json；原Task8/source/夹具保留。先新增状态回归与可执行动作入口，随后验证旧卡行为红，再以原runId/traceId/confirmation_required.eventId+完整公开摘要绑定按钮；当前等待阶段、终态/新输入/替换/账号切换均在ViewModel入口复核。UI复用已确认卡，不重画Figma，不改17工具/交易流程。

### Fix Round1 构建前进度

- API编译红与6项行为红分别保存在fixround1/api-red.log和behavior-red.log；绑定修正后6/6通过。追加同摘要仅换eventId、SSE替换/重放/终态和SavedState恢复，共9项专项通过；受影响聊天/身份/仓库/售后兼容共64/64通过，targeted-final.log实际退出0。扩展测试一次参数位置编译失败也保留targeted.log，随后改为命名参数，不隐去失败。
- 最新APK构建安装成功，SHA256为840D115471CE8BE02ADE51B2CB3E5CF6D3D913EE4D1CA74375DF9250A130A724。四个产品文件外只增加专项测试；不修改XML、Figma、后端交易或17工具。正执行主聊天Maestro与数量1→取消→数量2→旧取消→当前取消设备/服务端快照验证。
- 健康探针首次误用actuator/health与/health得到401/404，不代表服务停机；已改用既定/api/stores/cities与/internal/v1/health，均响应正常。PID8080=44776、8091=54004，未重启服务。

## 当前实施检查点（2026-10-02）

- 状态：**DONE_WITH_CONCERNS（开发自测结束，冻结待独立验收）**。控制端已传递人类确认并更新 brief，历史 NEEDS_CONTEXT 已解除；以下只读审计记录保留为当时事实，不代表当前门槛。
- 实施 BASE：手机 c614bd57218dd315e4ce03a60537023526cd6d49；新 Agent ee04c2cd0fa0232e857da915c4ca2773842afb28。仅 Task5，不执行 Task6。
- Figma 已修复：56:356（H 卡片恢复 x=16/y=294）；56:218（E 卡片高度184）；171:2/171:3（原价/折扣缺值明确显示未取得）；56:176（D 移除人均字段）。H/E 截图已检查，无越界。G 保持原图，实施按真实 isComposite，不按工具数推测。
- 已用 figma-design-to-code 技能取得 A–J 的 get_design_context 与截图：56:2、56:46、56:95、56:134、56:180、56:225、56:269、56:318、56:363、56:404。页面 6:8，文件 FxsWKGs10z7BL1MhFAOqFN。
- 05:54 实施记录：真实问候/缺城市/补充恢复/多轮/门店查询均跑通，12 套餐与29门店来自 Spring；首轮服务冷启动失败保留。两版聊天 Maestro 通过，最新版修复圆形头像与白色/浅珊瑚气泡；D/F 卡片及显式点击详情返回通过。真实待支付订单是本任务随机用户的隔离展示夹具，来源见 order-fixture-provenance.json，不是购买/付款闭环证据。
- Android 全量153项通过（最终公共确认/delta补正再定向复验）；Spring 同源隔离全量215项中1项因旧 Python 探针运行器依赖缺失报错，补探针依赖及解释器后相关20项通过。没有启动旧 Agent 服务，没有 SKIP 冒称 PASS。源码修改前副本位于忽略目录 .runtime/task5-source-before。
- 上述05:54检查点当时尚在补delta/确认、断线恢复和收尾；当前均由下方最终记录更新，仅独立验收未运行。未 stage/commit/创建 Agent，未修改主 plan/spec/UI 确认文档。

### 05:58 恢复实施追加记录

- 最终 APK 已安装并完成 `maestro-final` 聊天全流程：H 游客拦截、稍后返回首页、登录返回 AI、A 空态、C 真问候、缺城市与补充恢复、E 真套餐、显式详情点击及 J 多轮；不是沿用旧 APK。
- 新 Agent 不可达：`unreachable-recovery.json` 中 errorExit/retryExit 均为0，真实服务停机后错误卡出现，恢复服务原地重试成功；用户问题数量前后均1。正在执行连接已经建立后的真实中断测试，不以此替代断线测试。
- 原 `sse-identity-check.json` 的 false 文件保留，不能笼统算全量身份通过。原因不只是旧会话重放：SSE 还包含 Spring 网关生成的 parsed/tool_result/completed 兼容记录，它们没有原 Agent eventId/sequence，不能伪造。
- 新 run 本人订单查询的 `sse-identity-check-r2.json`：HTTP9条原始事件与 SSE 中9条对应事件的 eventId/sequence/runId/traceId/status 逐条一致；数据库游标2022–2031独立于sequence0–8。另有3条网关兼容记录，只有真实runId/traceId，不声称原始身份完整。追加实际状态模型 HTTP/SSE 同身份合并定向测试。

## 最终覆盖与冻结交接（2026-10-02 06:16，已释放环境）

- 最终APK SHA-256：`8F5FF97EB6FD42B88022B168C2E8547665D18CBE6EBC33C4A8C800BA09B4F7F9`，06:13:57卸载旧com.fandazi.app并确认com.dirror.music不存在后安装，详见apk-install-release.json。
- 最后必要兼容状态补正后49项定向及构建退出0；当前最新APK主聊天Maestro06:15:48退出0，真实推荐双工具→卡片链路及登录返回、多轮正常。按控制端要求取消后续尚未启动的重复套件，批次shell退出1仅为主动交接取消，不算产品测试失败或未跑流程通过。
- 环境已释放：本应用force-stop关闭SSE；Gradle1个本轮daemon停止，无活动Maestro test或Gradle daemon；已有5个Maestro MCP工具服务未泛杀。Spring8080 PID44776、新Agent8091 PID44320健康保留供独立接管；新Agent入口/来源确认是D service.app:create_app，不是旧服务。release-environment.json保存原始字段。
- 新Agent service/tests/config/contracts70文件总摘要与旧审计完全相同（C977E9…），new-agent-source-freeze.json有逐文件SHA；原真实夹具7文件不变。源码冻结、运行器临时交接阻挡标记已清理，无新增产品操作或后台测试任务。
- 可复核正式运行：Gradle15次（9退出0、6红/回归失败留存），Maven4次（2退出0、2原非0留存），Maestro16次均退出0；公开API成功场景请求11次，另保留首轮冷启动6次FAILED。未执行条件分支/主动取消批次另列，不混入通过数。冻结写集最终逐文件数量以freeze-validation.json为准，源码/运行器44项SHA一致；设备安装APK与本地最终APK SHA完全相同，见installed-apk-identity.json。

### 产品与设计

仅Task5正式文字聊天，复用既有路由/Repository/ViewModel/ApiClient与真实Spring。九页基线批准来自更新brief的人类y确认，不再将下方旧NEEDS_CONTEXT视作当前状态。Figma先修复并逐页取上下文再转XML，保留A–J全部可编辑图层：H56:356坐标修复；E56:218高度184和171:2/171:3字段；D56:176移除人均；G不按工具数量伪造复合。

产品增量：游客正式聊天页拦截/登录原入口返回；空态与三个快捷问题；文字气泡和每轮历史卡片；公开progress按runId+stepId合并、暂停终态停止动画；delta即时文本/卡片；HTTP与SSE原身份去重；失败原地重试不复制问题；真实门店/套餐/订单与点击上下文；公共准备摘要/确认/取消展示但测试仅取消。Activity级历史按认证账号隔离，旧账号请求取消/会话换新，不改服务端归属。

最后一个必要补正来自实际SSE中的网关 `tool_result/status=succeeded`：这是工具成功，不是任务成功。Android只对“tool_result且没有原eventId”排除其状态对忙碌终态的影响，保留原始状态/审计/游标。新增测试先10项中1FAIL，再修正后最终相关49/49（0FAIL/ERROR/SKIP）及assembleDebug退出0。未修改Spring事件状态造假来规避问题。

### A–J 实际开发对照

设计图全在 `task5-evidence/figma/56-*.png`。设备截图1080×2400包含系统栏，采用393dp逻辑宽度完整页面；不是与852px设计做整图像素相等判断。开发者已实际查看A/H/C/E/D/F/G/I/J截图以及设计图，检查区块、文字换行、点击入口和四Tab；容许系统栏、轻微尺寸与真实多卡滚动差异，不把固定示例店名/示例步骤移植成产品数据。

| Frame | 设计节点 | 实际证据及检查 |
| --- | --- | --- |
| A空态 |56:2|maestro-release/task5-A-empty.png；圆形AI头像、欢迎/描述、三快捷问题，登录后直达AI |
| B处理中 |56:46|task5-B-running-1/2.png；真实NDJSON连接后采样，正在理解请求/进行中，352像素差异仅在[87,551,128,586]旋转区域；不是固定耗时或静态假进度 |
| C文字 |56:95|maestro-release/task5-C-text.png；真问候，白助手/浅珊瑚用户气泡，文字输入，无语音入口 |
| D门店 |56:134|maestro-read-cards-freeze/task5-D-store.png；真29门店、评分/评价/团购摘要，缺图沿用占位；点击才进真实详情再返回 |
| E套餐 |56:180|maestro-release/task5-E-deals.png；真12套餐、现价/真实原价与计算折扣；详情仅点击跳转 |
| F订单 |56:225|maestro-read-cards-freeze/task5-F-order.png；本任务本人待支付隔离订单，合计90.00/真实有效期；点击打开既存详情，不做支付 |
| G复合 |56:269|maestro-composite-prepare-final/task5-G-composite-paused.png及result；服务isComposite=true才显示任务进度，缺城市暂停，补城市返回真订单/门店/套餐 |
| H游客 |56:318|maestro-release/task5-H-guest.png；完整登录卡、禁用真实输入发送、无结果；稍后返回首页，立即登录后回AI空态 |
| I错误 |56:363|maestro-unreachable-freeze-error、maestro-interrupted-freeze-error/task5-I-error.png；真实不可达/连接已建后中断，重试恢复截图单独保留 |
| J多轮 |56:404|maestro-release/task5-J-multi-turn.png；旧卡保留，摘要只取真实显式点击的卡，不虚构已购买或默认选中 |

上述D/F/G/I及运行中像素证据对应APK6F8EB161…；最后的兼容工具终态补正不改布局，最新APK8F5FF97E…只再验证受影响的真实推荐链及已经运行的主聊天流程，遵从控制端不重跑已完整通过全套。`maestro-release` 退出0，不把取消的后续计划算通过。共享准备摘要实际出现完整门店/套餐/数量/金额/规则/截止时间，点“取消本次操作”后已取消，无业务写确认。

### 原始身份与兼容事件边界

原 `sse-identity-check.json` allHaveIdentity=false/sampleOriginalIdentity={} **保留不覆盖**。不能只解释成旧会话重放：Spring还生成parsed/tool_result/completed兼容审计，天然没有原Agent eventId/sequence。最小补正仅给兼容记录真实runId/traceId，不伪造原身份。

新本轮订单run `98e602ed-dba8-4c9b-8e25-6a02105e2500` / trace `e5054e2e-947c-4f14-8e23-020bb316f06f` 的9条HTTP原事件，与同会话SSE对应9条eventId、sequence0–8、runId、traceId及status全部一致（`sse-identity-check-r2.json` / `new-run-identity-sse.txt`）。SSE数据库游标2022–2031独立传递，兼容3条记录另列，不声称12/12原身份完整。原Agent的工具完成仍taskstatus=running，最终run_completed=succeeded。

实际状态模型测试覆盖：同原eventId的HTTP/SSE双交付只保留一次、数据库游标2031不由sequence8替代、暂停后相同run新trace允许sequence归零、网关兼容回放不重复回复/卡片/导航、兼容工具succeeded不结束任务。实时文本用协议delta字段，HTTP最终回答替换临时流气泡；历史卡与对应回复绑定，SavedState恢复不丢原卡。测试使用隔离替身验证合并规则，与上面真实9条配对分别标识，未把替身算真实网络故障。

### 真实数据与故障

公开业务均Android→Spring8080→D8091 `service.app:create_app`；随机注册隔离账号凭据仅.runtime，城市从GET目录取得，目前真实目录只有demo，不硬编码业务到Android。公开API11次成功场景有完整响应/单请求墙钟：6次问候/缺城市/补充/多轮/门店及5次订单/复合/准备/取消/复合恢复。单目标推荐多工具isComposite=false；真实DAG恢复34卡45事件，实际list_user_orders/search_stores/list_deal_packages，不调用禁止写工具。

订单展示夹具是本任务随机用户独立待支付订单ecf6fcbb-e079-4d7d-b73b-d516d42e3a09，套餐/金额从真实Spring取，见order-fixture-provenance.json。只插隔离待支付展示订单及未支付券，不扣库存、不付款、无核销/售后，不能当作Task6购买闭环。原夹具文件7项SHA未变，包括Task8券/评价相关清单；不清库、不覆盖八券/七场景。

故障首次及6F版本复验均真实：8091进程来源核验后停机制造不可达；中断则观察POST200/NDJSON连接已建，350ms后只停该新Agent；错误卡出现，恢复同入口后在原页点重试，两类error/retry均退出0，问题数before/after均1。原 `unreachable/interrupted-recovery.json` 与 `*-freeze-recovery.json` 各自保留；不把冷启动失败或状态替身算本测试。首轮6次实际冷启动失败保留readonly-chat-api-startup-failure.json，健康等待后复跑成功。

### 命令与回归

详见command-results.json，正式运行计数以可复核日志/结果为准；旧审计67+6次仅是历史只读命令，不等于实施工具总调用数。未能从终端精确重建全部探索/读取调用，不编造总数。

- Android完整适用回归：153/153，0FAIL/ERROR/SKIP，android-final-regression.log/summary。之后新delta/确认/身份/账号/兼容状态补正逐步定向复验，最终49/49、构建0；不是把两次测试数相加声称202项独立覆盖。
- Spring同源隔离完整215项：214PASS/1ERROR，失败仅SpringBootToolHttpContractTest缺旧Python客户端探针解释器，原失败JSON保留。补快照agent-service/src库及PYTHON_EXECUTABLE后相关4类20/20，backend-final-directed.json；没有启动旧Agent服务、没有回原真实目录跑会写H2清单的全量测试，也没有重写失败断言。
- 红绿与修复保留：初始聊天3FAIL；即时流卡1FAIL；43项兼容回归2FAIL（新HTTP事件历史期望及流故障后HTTP成功恢复）；17项真实delta/准备卡2FAIL；账号绑定测试首次编译红；兼容工具终态10项1FAIL。各red日志独立留存，最终定向0失败。账号绑定红是缺方法的编译失败，不冒称运行时失败。
- 非产品命令问题保留：Figma大base64首次命令行超长，改忽略目录分段导出；curl参数错误后改--noproxy；一次SSE WebRequest无限等待被终止，改curl最大窗口，exit28为主动截取窗口不是网络业务PASS；一次PowerShell rg通配路径失败，改-g过滤。具体原文件保留。
- Maestro条件operation补槽分支未触发显示SKIPPED，明确不是运行过该分支；主流程/已必跑断言按各result.json退出码判定，不写全分支全绿。

### 冻结归属、依赖与未覆盖

完整逐文件写集 `task5-write-set.txt`，前像SHA/当前SHA `task5-freeze-manifest.json`，本人增量 `task5-own-source.patch`，明确边界 `scope-boundary.md`。源码31项=修改22项（含7个原未跟踪布局）+真正新增9项；运行器/流程13项另列。原先dirty的Task8分支保留前像，只按本轮差异交接，不整文件归Task5。

MainActivity只有游客AI路由与本地账号隔离两个Task5 hunk；nav_graph **本任务零改动**，仅F显式点击需要已有groupOrderDetailFragment+orderId三行注册；其余购买/支付/券/退款/评价/帮助/投诉路由属后续。SessionRouteCoordinator没有相对BASE内容差异，不列本轮产品修改。FandaziApplication只有已有voucherRepository初始化是F读取依赖，purchase/afterSale变更不归本任务。其余原未跟踪订单页/模型/仓库/布局用确切SHA指认，无已提交最低版本就不编造提交号；详见scope-boundary，不承诺脱离原脏依赖可直接从干净BASE编译整应用。

未覆盖/非阻塞：独立验收未运行；完整买/支付Task6、本人订单/券全套Task7、售后/FAQ/优惠券/评价Task8未本轮验收；ASR/核销延期；大审计payload8000容量缺陷仍可能丢审计，留Task12关注，9条小订单事件配对不证明所有大DAG审计完整；未做意图12类准确率/消融/并发/P95，EXP-016只是本轮11次墙钟与聊天状态。现有Task7详情“实付”与券状态等遗留页面不以F聊天卡正确为其背书。SavedState仅本地会话恢复，不宣称无限历史持久化。最新版本未泛化重跑全部已过场景，独立验收须按冻结源码/最新APK亲跑适用项。

最终不stage/commit/创建Agent、不更改主plan/spec/UI确认状态、不进入Task6。已停止Maestro/Gradle占用并关闭本应用SSE，健康Spring/新Agent保留供接管，释放记录已落盘。控制端已有独立brief，可直接安排独立验收；本报告只给开发自测证据，不重复等待下一次验收指令。

## 历史只读审计检查点（下方第1–8节均为04:31–04:42当时事实）

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
