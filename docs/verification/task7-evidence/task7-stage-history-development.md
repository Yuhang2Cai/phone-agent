# Task7 开发报告

状态：进行中；待独立验收。仅本人订单和团购券只读查询，不执行 Task8。

## 当前阶段与基线

10:47位置续记：JWT3600秒已实测恢复，双服务/设备网络正常。success-voucher-dependency使用Task7新账号/套餐乙一份，只验证成功页→单券必要依赖，仍在跑。结束后最终API/DB本人事实、原完整行/438清单保护，停用新套餐但不回滚库存或删除订单，然后源码冻结交独立验收。

10:45位置续记：第三入口券自然401返回退出0，实际401及登录后原券号一致；列表/订单/券三入口全部亲跑通过。正在恢复Spring普通JWT3600秒；之后一笔Task7隔离真实HTTP模拟购买用于成功页→单券接线，不重复Task6无关回归。原事实/私有清单保护与源冻结仍待最后执行。

10:44位置续记：第三入口原券自然过期已到，后台恢复/登录返回正跑；待结果后恢复3600秒JWT，只做一笔本任务新单成功页→单券适用验证。最后保护/冻结/交控制端独立验收，不自行派发Agent或进入Task8。

10:43位置续记：券自然180秒JWT等待到10:43:52，尚待登录返回结论。冻结/原事实与清单保护/公开秘密扫描脚本已准备并语法核对；未冻结，不冒称独立通过。随后恢复普通3600秒，Task7新套餐只做单券成功页依赖购买，最终订单/券将按DB精确核对。

10:41位置续记：原券自然401准备退出0，现后台保留同一公开券号并按真实登录时间等待自然过期；前两入口已通过。剩余券登录返回、恢复3600秒JWT、仅一笔Task7隔离套餐成功页→单券依赖适用验证、原事实/438清单保护与源码冻结，未进入Task8。

10:40位置续记：原订单自然401返回退出0，真实401路径与登录后UUID一致；第三入口原券401准备正在运行。API/DB/身份断言目前37/37，未新增售后写入。后续券返回、恢复3600秒、成功页单券适用接线、保护与冻结，仍待独立验收。

10:39位置续记：订单自然过期等待完成，后台恢复/登录返回同一订单参数流程运行中；返回结果未出不计通过。之后券入口独立自然过期，再恢复环境、验证成功页只读接线并冻结，未执行Task8。

10:38位置续记：最终Android71项亲跑通过、0失败/错误/跳过，10套JUnit已脱敏留档；构建14秒，APK SHA仍与10:11安装版本相同。两仓HEAD/空索引复核不变。原订单自然过期等待到10:38:39，之后恢复/真实401/原编号返回，不提前计通过。

10:36位置续记：原订单401准备退出0，按10:35:35真实登录等待到10:38:39自然过期。主查询最终JSON实际75 COMPLETED、0失败/条件跳过；actual-counts.json逐段统计全部首次失败和条件跳过，不把同一阶段重跑算新的业务场景。下一原订单返回，继而券入口及成功页接线，尚未冻结。

10:35位置续记：列表自然401返回退出0，真实访问GET /api/group-orders 401后重新登录回列表；当前原订单详情自然过期准备运行中，券入口随后。公开XML实际复核后端24/7套、Android71/10套全零失败/错误/跳过，不把Maestro非当前入口的条件分支SKIPPED计作另一个通过场景。

10:33位置续记：列表180秒JWT自然过期已到，后台恢复/真实401/重新登录返回流程运行中，结果未出不写PASS。之后订单详情/券详情同样独立亲跑，再恢复3600秒，验证成功页券接线并冻结。

10:31位置续记：列表自然401准备退出0，后台保留列表并按实际登录访问时间自然等待180秒加4秒余量（expiry-orders.json），尚无401返回PASS。源范围核对正在进行，原dirty outline及其单色token是UI12必要只读显示依赖，Task8其余调色/接线不因此纳入。之后详情/券入口独立自然过期，再恢复普通JWT3600秒与成功页适用验证。

10:29位置续记：游客流程退出0，登录后恢复订单空态原入口；双服务来源健康及普通JWT180秒已实际核对。Agent29884新启动禁代理，Spring同源新进程；fault/fixtures仍false。当前401-orders-prepare正定位列表，随后依真实登录时间自然等待，不改令牌。剩余详情/券401、成功页适用依赖、保护事实/冻结。

10:28位置续记：原券网络恢复和新空账号流程均退出0；游客登录返回正跑。Spring精确重启至普通JWT3分钟，用于三入口自然过期，结束恢复3600秒；fault/fixtures始终false。冻结脚本只准备未执行；明确Task7必要原dirty源清单和Task8遗留构建引用，不把混合工作区成功冒称干净BASE投影已验证。

10:26位置续记：voucher-resume-prepare和voucher-network-error-resume均退出0；真实原图加载结束截图已留档，券断网失败隐藏旧资格、重试按钮可用。网络恢复，voucher-network-recover正跑原券重试。剩余空态/游客、三入口自然401、Task6成功页接线、事实保护与冻结；不执行售后写入。

10:24位置续记：券断网首测因Maestro默认launchApp强停原页面而失败，失败截图为首页网络错误；已只修验证器为stopApp:false。当前恢复网络重新定位原券，随后复验券断网/重试。HTTP/SSE原事件23/23一致，29帧含兼容事件，2轮run不同。401/空态/成功页接线仍未跑，不提前计通过。

10:23位置续记：network-recover退出0；同会话两轮HTTP/SSE原身份字段完全一致、原eventId不重复（identity-match.json），追加2实际断言通过。当前券详情断网Maestro运行中；剩余空态/游客、三入口自然401、成功页接线、事实保护和写集冻结。普通JWT3600秒未改，短期自然过期实验结束必须恢复。

10:21位置续记：真实wifi/data关闭，设备Active default network:none已留证；network-error退出0，列表及原订单详情错误/重试符合，未以旧内容冒充成功。网络恢复，network-recover正在运行；剩余券断网、空态/游客、三入口自然401、成功页适用接线及冻结。已过回归不重复无关全套。

10:19位置续记：agent-read设备退出0，订单/券只读卡未自动跳转，用户点击才进入本人资源，返回保留聊天。访问日志实见新会话SSE在POST创建前GET500（DomainException会话不存在）及取消时ASYNC AccessDenied；HTTP结果与UI成功，作为既有网关关注记录，不宣称全部请求200或已修复该非本Task查询路径。当前network-prepare运行中；下一真实断网后列表/订单/券重试，继而空态/自然401。

10:18位置续记：query-final完整Maestro退出0；三订单、两笔实付/待支付、不同券、状态分段、仅退款资格导航及正确返回已亲跑。query-first和query-selector-fixed两次验证器失败保留。适用Agent只读10+协议/模型/DAG/迁移85=95通过0跳过，非全套重复；当前设备agent-read运行中。下一空态/真实断网/自然401与最终源闭合。UI10–12结构和可读性已逐屏观察，UI12图片加载结束证据仍需补。

10:15位置续记：query-first在裸UUID选择器失败，截图已证明真实订单/实付正确；query-selector-fixed复跑已通过首笔订单、两张不同券和仅退款资格导航，后续不同订单/待付券仍在运行。UI10/11逐屏对照结构/可读性通过，UI12待对照；尚未执行空账号/Agent实际点击/断网/自然401。每个失败标签保留，未执行边界不计通过。

2026-10-02 10:07：进入真实查询验证，未冻结、待独立验收。已读完整Task7实施/验收brief和Task6最新独立R1 PASS报告；适用单元/集成回归已通过，双服务已按当前修改源精确重启。

手机 BASE：c386a0555d9091fa2b9c5ec9830e643b6e0c767d（feature/fandazi-ai）；Agent BASE：c339237fb38f9418f29e70b3ce36c7022215b6b3（codex/group-buying-protocol-service）。手机 C:/fdz-build 为既有 junction；Agent 根目录 D:/project/-Demo-master。

## 范围与执行约束

复用已有接口、RunEngine、UI 和 Task5 F 只读依赖，闭合 voucher 接线与订单/券路由。核对待支付金额标签、真实状态分段、支付/券快照及后端资格；本人/他人/游客权限、无订单、401 原入口恢复、真实断网重试、不同订单/券导航和真实 Agent 卡片必须实际运行。

afterSale 接线留 Task8；不纳入或启用延期 QR/核销路由，不执行 ASR。新随机本任务账号和隔离真实订单；不恢复旧库存、不消费原 Task8 资格、不使用 Task6 已轮换凭据。原 dirty 与七场景/Task8 清单保护。

产品修改前先取得当前 Figma 完整 Frame/截图，UI 不使用 TDD。新增修改代码及测试、配置说明使用中文注释。共享源、设备和服务由本执行者独占；不 stage/commit，不改主计划/spec/UI 确认，不起子 Agent。

## 运行证据

| 子步骤 | 实际结果与当前边界 |
| --- | --- |
| 开始字节 | source-start.json 共869项，两仓 BASE 如上；.runtime/task7/source-start 原字节不可覆盖 |
| 当前 Figma | 6:6 页面 UI10 31:2、UI11 31:43、UI12 31:70，均393×852完整 Frame，get_design_context 包含截图，未 sparse；先取再改 XML，设备对照待跑 |
| Agent 测试 | 新券查询首测失败（槽位缺失），随后 tests/test_agent_service_reads.py 10 passed / 0 skipped，agent-reads.xml；离线不代表在线通过 |
| 后端首测 | GroupOrderSummaryContractTest 4项，1 failure/0 error/0 skipped，缺页面 paidTotalCents；backend-red.xml保留 |
| 后端修复首复测 | 定向5类10项，1 failure；新测试误把两份整单25600分写为单券12800分，已修正测试，尚待复验；原日志.runtime/task7/backend-green.log |
| Android 原回归 | gradlew :app:assembleDebug :app:testDebugUnitTest exit0，原171项通过，构建38秒；任务新增解析/导航验证与设备流程仍待跑 |
| 后端定向复验 | GroupOrderSummaryContractTest/VoucherLifecycleTest/PurchaseConfirmationTest/GroupBuyOrderIdempotencyTest/AgentCardEnrichmentTest/InternalV1ToolIntegrationTest/AgentToolFieldBoundaryTest，共24项，0失败/错误/跳过；backend-attempt3.log |
| Android任务回归 | VoucherRepositoryTest及feature.agent/feature.purchase，71项，0失败/错误/跳过；android-task7.log，最新APK已构建；新测试先出现编译错误、后3项1failure，修复后通过，失败保留 |
| 真实setup首测 | 验证器误用不存在的order_reviews，首次在原事实采集阶段失败，未产生购买；已改store_reviews待重跑，verifier-failures.md |
| 真实环境 | emulator-5554在线；8080目录与8091健康实读成功，Agent sourceRoot=D:/project/-Demo-master/entryPoint=service.app:create_app；尚未按当前修改源重启 |

开发自测不能代替独立验收；首次失败保留，不将尚未运行边界计通过。

## 已补缺口

订单页面响应独立提供 paidTotalCents/paidAt/expiresAt/dealDescription，券页面补所属订单数量/状态/时间，17工具严格投影保持原样。Android 列表与详情已按当前 Frame 补完整结构，实付只取付款快照，待支付明确标签；分段按后端枚举，订单与每券独立ID，401登录回原入口、失败原地重试。新Agent券查询目标由严格模型槽位提供，调用已有只读工具；Android券卡显式点击导航，不自动跳转或签发令牌。

原 Task5 F 必要 dirty 依赖与原 Task8 售后/QR依赖将在 source闭合清单逐项区分；当前工作区含后者不代表将其纳入Task7干净HEAD交付。主计划/spec/UI确认无本执行者修改。

## 当前卡点与交接

10:12实测更新：API/DB/在线Agent共35实际断言通过；新任务3订单4券，整单实付14246/9647分，待支付7123分无付款快照，券身份彼此不同；他人400、游客401，读前后完整业务摘要不变。私有原任务清单438项摘要已采集。最新APK于10:11:18安装，本地/设备SHA一致28380cf723c41659aca0dc7c609f2388f0083fd3344f8dac4269d9ce67c2fb3d；旧包与干扰包已先卸载。当前query-first Maestro运行中，设备结论待结果；当前Figma三张PNG/完整层已存档。下一动作：查询流程、空态/401/断网、Agent显式点击与Task6成功页voucher依赖适用验证，然后冻结。

下一动作：保护原私有清单摘要，随机账号/新套餐实际购买，HTTP/DB/在线Agent及最新APK完整Maestro；最终保护事实、Figma对照与源码冻结。尚无设备/API本任务通过结论，不重做Task6或无关全套。完成自测后给精确写集/开始delta/冻结SHA与源闭合清单，释放交控制端独立验收；当前不得勾选完成。
