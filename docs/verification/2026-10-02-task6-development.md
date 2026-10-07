# Task6 购买、确认与模拟支付开发报告

当前轮次：DONE_FIXROUND1，B1 开发修复及 H2 自测完成，待 Tesla 独立复验；独立 R0 整体 FAIL 保留。下方原开发记录为 R0 历史；当前修复范围、结果和冻结见文末 R1，不将开发自测当作独立验收通过。

状态：DONE_WITH_CONCERNS（开发自测完成，待新的独立 Agent 验收）。只实施 Task6；无 stage/commit、子 Agent、主 plan/spec/UI 确认改动；不执行 Task7/8。源码、报告与证据冻结见 task6-evidence/freeze-receipt.json；环境已释放，双服务保留健康运行。

## 基线与写集

- 手机 BASE：158c21b385a11406d72ea12bd139092f0b574ff7，feature/fandazi-ai，现有 C:/fdz-build junction。
- Agent BASE：f9fcff3e8f2b9f41410762c5b6383f883b9cd742，codex/group-buying-protocol-service，D:/project/-Demo-master。
- 初始 Spring8080 PID44776 / 新 Agent8091 PID54004 已亲自核对；最终 PID、来源与释放时间见 environment-release.json。8091 始终为 D 新服务 service.app:create_app，未启动旧 Agent。
- 开始字节基线 680 文件；source-before.json、source-final.json、phone-source.json、agent-source.json、compile-inputs.json 保存双仓实际 dirty 编译输入，而非声称干净 HEAD 可编译。
- task6-write-set.txt 分列本次新增/改动和原 dirty 必要依赖；task6-baseline-hunks.patch 是相对本轮开始字节的精确 hunk，不将原 dirty 全部认领。
- 原 Task7 GroupOrderDetailFragment 只读依赖、Task8 其他源保持原字节。Task5 原 run/trace/确认事件/摘要的 replyToCard 守卫未修改；不泛化历史按钮。

## 实施范围

1. 报价商品原价小计、优惠、实付金额守恒；后端提供套餐身份、规则及数量上下限，Android 严格解析整数分，不猜金额或资格。
2. 页面及新 Agent 共用报价绑定创建，凭证绑定用户/会话/动作/报价资源/金额/版本；用户锁与报价/套餐行锁、指纹重放、库存扣减和一次消费均在写事务内。公开 HTTP 缺 quoteId 拒绝，不退回历史套餐/数量分支；历史服务方法仅保留兼容依赖，不供当前公开购买入口使用。
3. HTTP 脱管报价在写事务重新加载锁定，消费状态实际入库。支付锁住本人订单，过期不可付，重复支付返回同一事实、一份一券，数据库 DECIMAL 精确分摊。
4. UI-07 真正 BottomSheetDialogFragment；数量变化重新报价、取消不写单、401 账号密码登录返回原套餐/数量并重新取报价。UI-08/09 按 orderId 回读本人真实订单，包含实付、模拟支付说明和真实券集合。
5. ViewModel 同步占用入口防重复；支付换页等待当前双击窗口结束，避免第二次点击落到成功页返回订单按钮。网络未知结果保留同一幂等键，明确 HTTP 拒绝清除旧报价。
6. RunEngine 复用 service/engine.py，仅保留购买授权实际期限并与报价期限取较早值；暂停/恢复/取消继续使用原 runId，不复制 workflows/purchase.py。
7. 必要原 dirty 上 hunk：FandaziApplication 仅购买 wiring 注释；MainActivity 仅目标 Bundle/401 返回；DealDetailFragment 仅真实套餐购买及 resumePurchase；nav_graph 仅 checkout dialog 和真实 store 参数。中文注释随行为同步。

## 实际 Figma 与 APK

- Figma 文件 FxsWKGs10z7BL1MhFAOqFN，实际加载页面 6:5；UI-07 29:41、UI-08 29:59、UI-09 29:82 已先取得完整图层与截图再改 XML。图标节点 29:70、29:73、29:83；参考图 figma-ui07/08/09.png。
- 最终 APK：C:/fdz-build/android-app/app/build/outputs/apk/debug/app-debug.apk。
- SHA256：27BB3A7D3D48CDD21339BE90AA64CBFBB0CD7D6647A540BABC344DDFEBC00A97；卸载旧 com.fandazi.app 后于 2026-10-02T08:00:26.6097102+08:00 安装；com.dirror.music 已确认不存在。apk-final-installed.json / apk-device-match.json 记录设备 base.apk 与构建 APK 字节一致。
- 该 APK 所有最终设备流程共用同一 SHA；之后只改后端、测试、证据脚本和文档，Android 产品源未再改变。前一 APK 4617D645... 的首次失败/quantity2-r1 仅是历史证据，不冒称最新。
- 最新截图路径：maestro-quantity1-final/2026-10-02_080036、maestro-quantity2-final/2026-10-02_080207、maestro-401-return/2026-10-02_080642、maestro-latest-server/2026-10-02_081217，各目录下 takeScreenshot。UI-07/08/09 已逐屏查看并对照 Figma：结构齐全、无遮挡/操作失败；后端 imageUrl=null 明确占位，不替换成假套餐图。多券区域可滚动。
- 最新服务的单券完整流程再次 exit=0；多券/401 是同一最终 APK、后端最后“报价必填/消费状态持久化”两处改动之前的运行，最终后端另外有14项定向和真实HTTP创建/支付证据，不混称全套都在最后源重跑。

## 命令与实际计数

工具链：JDK C:/Users/caid0/.jdks/ms-17.0.17；Maven D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd；Python C:/Users/caid0/anaconda3/envs/pinnT/python.exe；Maestro C:/Users/caid0/.local/maestro/bin/maestro.bat。HTTP 禁代理；环境导入 scripts/Initialize-FandaziEnvironment.ps1。

| 检查 | 实际命令与结果 |
| --- | --- |
| 后端初始红测 | mvn -q -Dtest=PurchaseConfirmationTest,GroupBuyOrderIdempotencyTest test：4项，1失败1错误；金额 expected17600 / actual13600，凭证重放失败 |
| 后端首次定向绿 | mvn -q -Dtest=PurchaseConfirmationTest,GroupBuyOrderIdempotencyTest,InternalV1ToolIntegrationTest test：13项通过 |
| Agent 红/绿 | pytest tests/test_purchase_workflow.py：首次4通过1失败（授权期限覆盖）；修复后连同 test_confirmation_time_precision.py、test_agent_service_reads.py，-q -p no:cacheprovider：15通过 |
| Android 构建/定向 | gradlew.bat :app:assembleDebug；购买状态及 Task5ConfirmationActionTest 定向通过。最终 :app:assembleDebug :app:testDebugUnitTest --tests '*PurchaseStateTest'：构建成功、4项0失败/错误，android-final-purchase.xml |
| Android 一次适用回归 | gradlew.bat :app:testDebugUnitTest：171项、31套、0失败/错误；日志 .runtime/task6-android-regression.log。原全量 XML 后被定向 Gradle 覆盖，计数为运行当时实际汇总；此证据缺口明确保留，见 android-regression-summary.json |
| Agent 一次适用回归 | pytest tests -q -p no:cacheprovider --junitxml=.../agent-regression.xml：399项，360通过21跳过18环境错误。仅原18错误节点指定 --basetemp C:/fdz-build/.runtime/task6-pytest-repair 复验18通过；合计378通过21跳过，不声称首次全绿 |
| 后端一次适用回归 | scripts/Test-Task6Regression.ps1：同源、无 runtime/target/链接的隔离快照，全量216项/52套，215通过1错误。原 Task2DevelopmentFixtureTest 仅写快照 H2/.runtime，不写真实库；原清单/密钥哈希0变化 |
| 隔离金额用例修复 | 同一快照仅复制并校验最终 PurchaseConfirmationTest.java，再 mvn -q -Dtest=PurchaseConfirmationTest test：3项0失败/错误 |
| 最后两个边界红测 | PurchaseRequestValidationTest：1项失败（缺 quoteId 无校验）；GroupBuyOrderIdempotencyTest 脱管报价：1项失败（expected CONSUMED / actual ACTIVE），红 XML 保留 backend-first |
| 最终后端定向 | 同一快照 mvn -q -Dtest=PurchaseConfirmationTest,PurchaseRequestValidationTest,GroupBuyOrderIdempotencyTest,InternalV1ToolIntegrationTest test：14项0失败/错误。最终235个 backend/src 文件与快照逐字节一致，backend-final-source-binding.json |
| 后端合并 XML | 原回归已过项加失败类复验/最后定向/新增边界，backend-final/counts.json 为217项/53套0失败/错误；是合并证据，不是最后源又全量一次通过 |

金额隔离测试最终源与实际 XML：backend/src/test/java/com/fandazi/feature/purchase/PurchaseConfirmationTest.java，SHA256 CD5D9B40A0516313DC9C1B0AF50E02F97128A1F4737CF2F5C9AEF8560E117760，工作区/隔离快照一致；最终 XML tests=3、failures=0、errors=0、skipped=0。用例创建独立 H2 套餐（8800/10800分、库存5、数量2），事务回滚；不再依赖被其他测试耗尽的共享种子。backend-final/isolated-money-test.json 留档。

完整日志仅忽略 .runtime；公开 XML、结果和指纹均在 task6-evidence。Agent 原18 WinError5/PermissionError 的 agent-regression.xml、后端首次 NoSuchElementException 的 backend-first/TEST-...PurchaseConfirmationTest.xml 均保留，未改写首次失败。

## 真实在线覆盖

- scripts/task6-live-purchase.py api：本任务随机隔离账号、真实接口，两套餐/数量1和2；各8并发创建/8并发支付，均1个订单，券数=数量、total_amount=paid_total_amount=SUM(allocated_amount)，见 real-api-purchase.json。
- agent / agent-price / agent-prepare-expiry / agent-expiry：Spring8080→新Agent8091→真实在线模型。准备不写单，取消无写工具；创建和支付分次确认，恢复原 runId；价格变化 FAILED 无新增订单；自然350秒后 FAILED 过期且订单集合不变。agent-live.json、agent-price-change.json、agent-natural-expiry.json。
- expiry：自然306秒后原 HTTP 报价返回400过期，订单集合不变；未改报价/凭证期限或时钟，natural-expiry.json。
- Run-Task6Purchase.ps1 -DealIndex 1 -Quantity 1 -Label quantity1-final / -DealIndex 0 -Quantity 2 -Label quantity2-final：两流程 exit=0，真实不同UUID、90/180元、取消重开、数量调整、双击创建/支付、单券/多券查看。
- Start-Task6Services.ps1 -Service Spring -JwtExpirationMinutes 1；401-prepare / 401-return 两 YAML exit=0。普通JWT实际60秒，准备后自然等待，访问日志 08:06:53 POST /api/group-orders 401；08:07:16 登录200及新报价200，返回原7b5a48ce...套餐、数量2、180元并取消。前后9订单/13券完全不变；401-access.log、401-facts.json、最新APK截图。
- 之后恢复 -JwtExpirationMinutes 60，实际签发3600秒；contract 阶段缺quoteId HTTP400无写单、有效原报价正常创建、同键创建/支付重放同单且数据库quoteStatus=CONSUMED；final-http-contract.json。
- 最后受消费持久化修复影响的设备单券 Run-Task6Purchase.ps1 -DealIndex 1 -Quantity 1 -Label latest-server，08:12:15–08:13:30 exit=0，双击支付/真实券查看通过。
- 最终只读 Audit-Task6Facts.py final：本任务12个PAID订单、16张券，逐单金额精确一致，facts-final.json。早期错误读取器 payment_amount 导致一次真实支付后读证据失败；修成实际 allocated_amount，保留该额外本账号订单。首次单券跨页双击失败、两次不同后端版本 contract 验证也有真实订单，未删单或篡改历史以美化计数。
- 离线 tests/test_purchase_workflow.py、PurchaseStateTest 的模型/仓库替身仅算离线回归，不算在线证据。

## 价格变更与恢复

变更资源7b5a48ce-8d4b-4a4e-9858-c9752011685d是既有共享目录套餐，**不是本任务隔离套餐**。API与在线Agent各在独占窗口用带原价/版本条件的 SQL 临时加1分/版本+1；各 finally 精确恢复9000分/版本1，后续停止价格写入。API旧报价拒绝后新报价9001分重新确认付款；Agent旧确认拒绝无订单。订单账号隔离，不等于套餐隔离。

只读恢复复核 facts-final.json：变价前持久报价 fingerprint 的原价格/版本事实与当前数据库读数相等；规范JSON（仅dealId/priceCents/version）恢复前/后SHA均9f451c3e25d459a89c5836d9ec8918ebff19a062ccee407fc0f85c01666ca43d。**恢复前哈希是事后依据已持久化报价重算，不是当时现场采集的完整行哈希**；正常购买扣减库存未回滚、不纳入价格恢复哈希。该非隔离资源及当时哈希未采集是明确验收关注点，不再为补哈希重演变价。

## 首次失败与修复保留

- 样式隐式父级 style/Task6 缺失，首次 assembleDebug 失败；purchase_styles.xml 显式 parent="" 后构建成功。
- Agent覆盖短期授权期限，红测后改用真实较早期限；不改Task5卡片守卫。
- 后端金额重复优惠与授权重放、共享种子依赖、报价必填及脱管消费红测均留存。
- 旧 pytest-of-caid0 目录18环境错误保留，仅指定新basetemp复验。
- Maestro数组包装令门店参数错误；¥在Windows bat变成?造成断言失败，改ASCII数字正则。实际页面金额未改。
- 单券支付已成功却双击第二下误触成功页返回订单，旧失败截图保留；支付导航手势保护后最新APK两数量及最终服务单券均过。

## 未覆盖与交接

- 尚未独立验收，不自行勾选主计划或宣称验收通过；由新的独立 Agent 亲自复验最新源/APK。
- 未在设备单独重演报价自然过期（已有真实HTTP/在线Agent自然等待）；未对支付页/成功页另做401或真实断网重试；这些状态有代码及离线边界测试，不冒称全部在线覆盖。
- 原Android全量XML被后续定向执行覆盖；已保留实测汇总、完整退出成功日志指纹和最终4项XML，不伪造恢复。
- Task7订单详情旧dirty只读依赖、Task8验收及真实支付渠道/核销均不在本任务范围。
- 历史本任务测试报价/订单保留，包括修复前页面报价ACTIVE但一次性凭证已消费；不追改旧测试数据。最终新订单报价消费已真实验证。
- 环境释放记录 environment-release.json；JWT恢复60分钟、TASK2_FIXTURES_ENABLED/TASK8_FAILURES_ENABLED/SEED_ENABLED=false，新双服务健康，设备保留最终APK并已停止本app，所有本轮产品测试结束。凭据与Maestro原始命令/日志移入忽略.runtime，公开证据已敏感值扫描；不消费原Task8清单。
- 冻结后停止源改动与产品测试；双仓源、报告、hunk、写集、APK、Figma节点的实际SHA以freeze-receipt.json为准。

## R1：B1 门店隔离修复

独立首次全量实际217项、0失败、1错误：displayedDiscountReconcilesWithPayableAmount 的 stores.findAll().get(0) 读取空列表，IndexOutOfBoundsException。原源SHA CD5D9B40A0516313DC9C1B0AF50E02F97128A1F4737CF2F5C9AEF8560E117760；原XML/汇总及完整独立FAIL报告原样保留，并复制到 task6-evidence/fixround1/first-independent-failure/。

只改 PurchaseConfirmationTest.java：用例自建随机UUID H2门店（merchant=null，不依赖历史商家）及套餐，同一测试事务回滚；删除共享门店列表读取，保留 subtotalCents-discountCents=totalCents 核心断言、另外两项测试、产品源、种子、排序和测试集合。修复源SHA 2679AEAD67A1F6A6E7DA509939743E22928751763D65267DB2D7876F4100839A。

实际验证器：docs/verification/task6-evidence/fixround1/Invoke-Fixround1Maven.ps1，依次 -Mode Prepare / Directed / Full / Freeze。新快照 .runtime/task6-fixround1-77281d4bce8645d4b50dfc1f985af515 在复制时无runtime/target/链接，307输入逐字节核对；只将修复测试同步到快照，再次核对输入。定向和全量是两个独立Maven进程，同一快照使用定向新生成的target，不从原工作区复制构建或历史运行目录。

| R1 实测 | 结果与证据 |
| --- | --- |
| mvn -q -Dtest=PurchaseConfirmationTest test | 2026-10-02 08:36:57–08:37:16，exit=0，3项，0 failures/errors/skipped；Directed-summary.json / Directed-xml/ |
| mvn -q test（本轮唯一一次全量） | exit=0，217项/53套，0 failures/errors/skipped；Full-summary.json / Full-xml/，包括金额类3项0失败/错误。无排序修改、测试跳过或替换产品实现 |
| 源一致 | 工作区与快照测试均SHA2679AEAD67A1F6A6E7DA509939743E22928751763D65267DB2D7876F4100839A；全部307复制输入在执行前后逐字节核对。原696源中仅该测试改变 |
| 首次失败保护 | Tesla原217项1错误的XML/汇总以及R0源CD5D9B40...保留，first-independent-failure/与before/另存复本；R0receipt/evidence与独立FAIL报告不更新、不覆盖 |

完整本轮Maven日志在上述忽略快照内 Directed-maven.log / Full-maven.log，日志SHA和实际命令、工作目录、时间、XML计数留在本轮summary。使用原test/resources H2配置，不导入真实库环境；移除可能继承的数据源/内部令牌覆盖。历史Task2夹具测试仅在新快照内写 .runtime/task2-test-fixtures.json，原清单/密钥字节保护核对。

本轮写集、精确测试hunk、最终测试源、报告与证据指纹见fixround1/fixround1-write-set.txt、fixround1-test.patch、manifest.json、receipt.json。只修改测试和必要验证器/报告；R0冻结不冒充新版本。H2构建会话已结束并释放，不再占用构建或改源；未操作服务/设备/真实库，未重跑Android/Agent，未进入Task7，未stage/commit，未起Agent。

未覆盖边界以2026-10-02-task6-independent-acceptance.md的Tesla FAIL报告为准，本轮不扩跑；等待Tesla亲跑受影响全量并续验尚未覆盖实际边界。DONE_FIXROUND1仅是实现修复收尾，不是Task6独立验收PASS。
