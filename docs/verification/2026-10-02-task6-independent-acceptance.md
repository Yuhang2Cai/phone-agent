# Task6 独立验收报告

最新结论：**Task6 独立R1整体PASS**（2026-10-02 09:29，Asia/Shanghai），环境已释放。本报告由独立验收Agent亲跑并审查，不是实现者开发自测结论；不执行Task7、不修改产品、不stage/commit、不启动子Agent。R0 FAIL及首次失败证据保留，下方原文及`task6-independent-evidence/r1/independent-report-r0-preserved.md`不会因R1通过而消失。

## R1 最终结论

| 维度 | 独立判定 | 主要依据 |
| --- | --- | --- |
| 需求审查 | **PASS** | 完整brief、规格6–8章、已确认UI-07–09逐项映射；真实API/在线模型、最新APK与当前Figma覆盖全部本Task必需边界，无剩余未运行项 |
| 质量审查 | **PASS** | R0/R1冻结审查包分别完整读一次；B1随机自建事务H2门店/套餐替代共享门店依赖，金额断言不变；中文注释、安全绑定、整数分/DECIMAL、历史守卫与意图基线核对；源只一项批准测试变化 |
| 运行验收 | **PASS** | 独立同源全量H2 217全绿；复用本独立已过Android171与Agent378/21跳过证据；R1最新APK完整购买、自然401/过期、真实断网、隔离变价和历史按钮亲跑，真实DB金额/券一致 |
| 整体 | **PASS** | B1已修复且受影响全量亲跑关闭，所有本Task必需边界实测完成，无未关闭产品阻塞；首次环境/验证器失败原样保留且受影响段实际复验 |

### 需求覆盖

| brief边界 | 实际独立覆盖与证据 |
| --- | --- |
| 新Agent真实报价、完整公开确认、暂停/恢复沿用run、确认前无单、取消无单 | R0真实Spring→8091→模型`agent-*.json`；R1自然过期拒绝/新摘要/取消`agent-natural-expiry-*`、`agent-expiry-new-*`；真实公开摘要包含动作、套餐/门店、数量、金额、版本与截止时间，不公开凭证，不靠模型推定授权 |
| 短时单次、用户/会话/动作/资源/金额/版本绑定；必填报价、旧确认不能消费新摘要 | 同源完整授权/购买测试；R0真实缺quoteId、错会话、跨报价、已消费换新幂等键、变价旧凭证等拒绝均无单，合法原凭证仍有效；R1真实第二用户拒绝且原合法凭证未消费；根目录与R1`api-checks.json` |
| 两套餐真实UUID、至少两数量/不同价格、UI07→08→09、真实模拟支付/一份一券 | 原`group-buying-03-purchase.yaml`在本轮卸载后最新同源APK运行两次；6713分/数量1和16654分/数量2，实际订单`446bca19-...`/`f4dc3121-...`各PAID且1/2券，截图与DB逐一关联；`maestro-quantity1/2`、`device-database-audit.json` |
| 金额精确、后端数量上下限、缺金额/报价禁止确认 | 商品总价减优惠等于实付，UI07/08/09与真实API/DB一致；设备真实断网数量变化清掉旧金额，只有重新报价，恢复数量3=20139分、最大3/最小1不能越界；HTTP数量0/4拒绝；`maestro-quote-network-*` |
| 重复创建/支付与受控取消 | R0各8次并发创建/支付同单；R1两个原完整购买、网络恢复、变价新确认双击只有一个订单；UI07关闭/reopen及明确取消无写单，支付取消不改变待支付；数据库与每段前后订单集合核对 |
| 购买/支付/成功页自然401登录返回原目标/数量 | 普通JWT真实60秒到期，访问日志实际401；购买登录重取原数量2后取消无单；支付登录回同待支付单再明确支付；成功登录回原PAID金额/券，不重复支付；`auth-http-access/`、`auth-real-order-audit.json`与三类Maestro |
| 真实断网错误/重试且不假成功 | wifi/data实际关闭、默认网络none；报价/创建/支付/成功回读均实际失败，无错误阶段订单增量；恢复只新增明确创建的一单，支付/回读同单同金额2券；`network-*.json`、`maestro-network-*` |
| 五分钟自然过期、实际拒绝及新报价需新操作 | HTTP/在线Agent旧授权自然过期已亲跑；设备另取新真实报价等待310.016秒，端上过期只重新报价，服务器同设备原凭证HTTP400，均无单；新UUID13426分/数量2先展示再取消不自动写单；`wait-expiry-reject.json`、`frontend-expiry*.json`、`maestro-expiry-*` |
| 隔离价格变化旧摘要拒绝/重报价/新明确确认 | 本验收新套餐甲6713/v1→6714/v2，设备旧13426分明确拒绝；重报价13428分仍无单，新明确创建支付才新增一PAID/2券；亲读恢复6713/v1，原资源未写；R0在线Agent同样旧确认失败、新摘要另授权；`price-up/restore.json`、`maestro-price-*` |
| Task5历史卡与聊天导航 | 原历史三用例最新APK实际跑；旧数量1取消按钮不改变完整当前数量2确认，当前取消结束同session/run为cancelled、无订单；实际截图仍在聊天，回复不自动导航；`history-guard-audit.json` |
| 当前Figma逐屏、核心结构/无溢出假数据 | 当前MCP完整Frame及截图，亲看两套餐三屏；核心结构、警告、金额、券及操作完整，无阻塞遮挡/溢出；真空图占位、不冒充示例套餐，不加秒杀/券包/购物车字段；`figma-completeness.json`、`figma-visual-comparison.md` |
| 原事实/共享价格偏差、源与环境 | 原目录/门店/订单/券/退款/评价完整原行重读0差异、原Task2/5/8私有清单哈希不变；历史共享套餐当前9000/v1亲核，历史事后重算不能证明过去完整行或所有并发影响的限制保留；未消费原清单。`original-facts-after.json`、`shared-price-independent-audit.json`、`r1/closeout-final.json` |

### 计数与交接

- 独立后端最终53 suites/217 tests，0 failures/errors/skipped、exit0；首次R0产品B1、R1解释器遗漏全量错误均保留，绝不宣称所有首跑全绿。R1金额源SHA`2679AEAD67A1F6A6E7DA509939743E22928751763D65267DB2D7876F4100839A`。
- Android171全绿、Agent378 passed/21 skipped为本独立R0实际结果，源不受R1测试修复影响而不重复。在线边界另亲跑，21跳过不冒称在线测量。
- R1共30段Maestro结果：27段exit0，3次环境/验证器首败保留并分别以新标签受影响复验exit0；175条R1接口/数据库断言全部通过，重复核查计入断言数，不冒充175个独立业务场景。详情`r1/closeout-final.json`、`r1/verifier-failures.md`。
- 最新同源APK SHA`5F03AF43BB50A8DF3720B2E1F07A07331BE018B6DD083158723D58015279BF5E`，08:46:05卸载规约后安装、本地与设备一致；不是把冻结开发27BB...误写为同一字节包。
- 最终本人11个新订单，9 PAID金额/实付/券DECIMAL分摊精确一致、总17券；2个待支付保留，不删失败事实。R1仅5次明确创建新增5单，其他阶段零订单增量，原用户/Task8资源未消费。
- 两本验收新套餐最终6713/8327分、版本1、active0，真实剩余库存47/56保留；未恢复已发生订单/库存扣减，未修改原目录。`r1/isolated-resources-release.json`。
- 独立采集曾误回显本人新账号口令的失误已披露；仅新账号口令已真实轮换，旧口令HTTP400、新口令200，凭据继续仅.runtime；最终356个公开文本文件/报告敏感值精确匹配及JWT模式扫描0命中。`r1/private-account-rotation.json`、`r1/public-secret-scan.json`。
- 09:28:27网络恢复原wifi1/data1/airplane0，普通JWT亲读3600秒、App force-stop；无仍运行的本轮测试会话。批准Spring8080 PID43260/JDK ms17与新Agent8091 PID42144/pinnT保持健康供接手，未起旧Agent。`r1/environment-release.json`。
- 09:28:50最后696源文件核查仅一项允许测试变化；277保护文件0变化，R0receipt/source-final/原报告备份SHA仍原字节；两仓HEAD仍手机158c21b/Agent f9fcff3。本验收产品修改/暂存/提交/子Agent/Task7均为0。

### 状态历史

| 时间 | 本独立结论 |
| --- | --- |
| 08:33 R0 | FAIL，217全量共享门店B1，按派发返回修复、保留失败并释放 |
| 08:40 R1接管 | 进行中，先读R1冻结包/receipt，只有批准测试源变化，不接受开发全绿代替亲跑 |
| 08:46 R1全量 | B1独立同源217全绿关闭；设备/自然到期等仍待跑，未提前整体PASS |
| 09:27 | 所有Task6必需运行边界完成，最终事实/源/资源/账号与环境收尾中 |
| 09:29 | **需求PASS、质量PASS、运行PASS、整体PASS，环境已释放** |

残余限制：开发历史共享价格事件没有当时完整行前哈希，无法追溯证明全部历史并发影响，本报告没有抹除该偏差或把finally当证据；当前原事实与本次新资源影响已经独立亲核。ASR/核销延期，Task7完整查询及Task8售后不属于Task6整体验收，也未执行。本轮无剩余Task6必需未覆盖项。

## R1 过程历史

当前更新时间：**2026-10-02 09:27**。所有Task6必需设备、真实HTTP/在线Agent与同源回归边界已亲跑，当前进行最终数据/源指纹核查、仅本验收新资源停用、私有新账号轮换及环境释放，完成收尾前不提前整体PASS。产品未改，不重复已过全套。

09:27续记：新设备报价`29471f32-f3c7-49f0-94e1-70f4d0947c26`截止09:25:31；09:20:51至09:26:01自然等待310.016秒后，实际旧提交显示“报价已过期，请重新报价”、清除金额，只有重新报价，不进入支付、无订单。当前后端用同一设备原真实凭证收到HTTP400过期且无订单；重新报价得到`3c17227f-e02e-424c-a437-839be1332f14`，数量2/13426分，先展示再明确取消，未自动创建。`maestro-expiry-prepare/reject/recover`全exit=0、`wait-expiry-reject.json`及两frontend快照留档。数据库所有11个本人新订单实际一份一券；9个PAID实付/券分摊与订单DECIMAL金额精确一致，2个待支付原样保留。原目录等完整行再读差异0，开始只停用两新资源、轮换本验收新账号口令、恢复原网络并释放。

09:20续记：原Task5历史Maestro实际完成，`history-prepare-r2/old-tap-r2/current-cancel`均exit=0、订单集合不变。数据库完整私有pending_confirmation前后相同，旧按钮未改变新数量2/13426分确认，当前取消在同session/run结束为cancelled，见`history-guard-audit.json`；回复停留聊天，没有自动导航。独立错误目录首跑及ADB超时首跑均保留，非产品缺陷。新设备报价前置正在运行；后续真实等待310秒，不改时钟或授权截止值。

09:04续记：真实断网（WiFi=0、data=0、Active default network: none）创建失败、再次失败均无订单；恢复后双击只新增待支付`4ddef947-077b-435c-996b-18e79409c950`。支付再次断网显示真实连接失败，未假成功；首次恢复Maestro被Android蓝牙系统崩溃弹窗遮挡而exit=1，截图/结果/日志保留，明确是环境故障不是产品缺陷。只关闭该已观察到的系统弹窗，原支付恢复断言重跑exit=0，同单实付13426分/2券，未多单；后续不再主动切飞机模式，只关闭真实WiFi/data。当前补成功页断网回读，随后缺报价边界、设备自然过期、隔离变价和历史守卫。详见`r1/verifier-failures.md`。

09:08续记：成功页真实断网显示“核对支付结果/点击重试”，恢复回读同一已支付事实，前后10订单不变。数量切换断网实际清除旧金额，仅“重新报价”，连续尝试不写单；恢复取数量3后端报价20139分，步进最大3/最小1实际不能越界，再取消，仍无订单增量。对应`maestro-network-success-*`、`maestro-quote-network-*`均最后exit=0。当前开始设备变价前置，且只使用本验收套餐甲；其余剩余设备五分钟自然过期、历史按钮实际守卫与最终收尾。本轮原始Maestro命令首部一次误回显独立新账号口令，已在验证器失败历史披露，收尾将轮换该新账号口令，密钥/口令不进入公开证据文件；不掩盖独立采集错误。

09:12续记：设备独立变价串行完成：本验收新套餐甲6713分/版本1→6714分/版本2；旧设备13426分授权亲点实际“变化”拒绝，无订单。重新报价13428分单独展示，仍10订单；新明确提交/支付后新增一PAID、2券。价格恢复脚本随后亲读6713分/版本1，原目录未进入写集，原失败待支付订单未删除。`maestro-price-prepare/reject/requote/confirm-pay`最后均exit=0，`price-up.json`/`price-restore.json`记录实际SQL读数。剩余设备自然五分钟过期与Task5历史守卫正在续验，之后最终数据/源指纹/口令轮换及释放。

2026-10-02 08:50阶段：B1已经独立同源全量复验通过，217项、0失败/错误/跳过。首次R1全量因独立脚本漏设批准Python解释器出现1个HTTP契约环境错误，首次XML/摘要保留R1根目录；正确解释器及禁代理后，在另一全新无链接/runtime/target快照原样全量217全绿，证据`r1/backend-attempt2/`。验证器路径/解释器错误另存`r1/verifier-failures.md`，不删首次失败、不当作产品缺陷。

已亲跑先前自然到期的HTTP报价及在线Agent确认：旧授权实际拒绝、无写单、原run沿用；恢复新报价/新摘要不自动写单，明确取消无写单。只激活本验收新建的两套餐，不重置其库存或订单。R1按卸载两旧包→确认不存在→安装规约复用同源APK，SHA5F03AF43...，安装时间08:46:05，本地与设备一致。

数量1原完整购买Maestro已exit=0；数量2不同套餐流程正在运行，期间无变价注入。Figma当前三屏截图、完整Frame图层已分别亲取，响应均未截断，逐屏视觉和后续设备负面边界尚在进行。整体R1未判通过。

08:55阶段：数量1/2两个不同真实套餐完整Maestro均exit=0，后台实际新增各一个PAID订单，金额6713/16654分、券数1/2、DECIMAL分摊精确一致。当前Figma三屏与两流程实际截图逐屏亲看，确认核心结构齐全，数量、优惠、总价/实付、模拟支付警告、券集合与操作均可见；缺图使用真实空图占位，不冒充Figma示例图片。购买页自然401已发生（08:53:13 POST /api/group-orders 401），登录后回到原套餐数量2，重新报价13426分并取消；前后8订单不变。普通JWT亲读有效期60秒。支付页/成功页401、断网恢复与设备过期/变价仍在执行。

08:59阶段：支付页、成功页自然401两流程均实际完成。支付页旧令牌实际POST mock-pay 401，登录后同一订单`1f355633-fe04-42d9-94af-ce91e6344011`仍待支付，新的明确支付才变PAID、13426分/2券，前后9订单不增。成功页GET回读401后登录返回该已支付订单，券数/金额不变，未再次写单支付。访问日志和截图独立留档`r1/auth-http-access/`、`maestro-payment401-*`、`maestro-success401-return/`；正在恢复普通JWT60分钟。真实第二新用户消费第一用户报价被拒，双方无新增订单，原合法凭证未消费，见`r1/api-checks.json`。后续设备断网、五分钟过期、变价与Task5历史守卫尚在续验。

### R1 审查与运行映射

本验收对R0审查包完整读一次，分别核对本轮精确patch、相对固定BASE累计差异及完整需求；R1包完整读一次，仅金额测试的自建随机H2门店/套餐改动，不改变金额断言、其他测试或产品。新增事务资源随测试回滚，`merchant=null`为既有可空兼容关联，不再读共享门店。新增/修改的模块、公开函数、关键拒绝/恢复和金额转换均有与实际逻辑一致的中文说明；没有为测试绕过产品授权。B1的质量问题已由同源全量复验关闭。

| 本轮核查 | 独立结果与证据 |
| --- | --- |
| R1同源全量H2 | `Invoke-R1Backend.ps1`：53 suites、217 tests、0 failures/errors/skipped、exit=0；全新输入快照无runtime/target/链接，307复制文件逐字节一致，`backend-attempt2/`保留完整XML。PurchaseConfirmationTest 3项、GroupBuyOrderIdempotencyTest 1项及HTTP契约包含于原样全量，不用开发定向3项替代 |
| 不受影响回归复用 | 本独立R0 Android171全绿、Agent378 passed/21 skipped；696冻结源核对只有金额测试变化，因此按派发不重复全套。跳过不算在线成功；本轮真实Spring→新8091→模型场景另有证据 |
| 意图与协议边界 | `Verify-QualityBoundary.py`实际YAML解析12类、17工具、confidence 0.80/margin 0.10、协议1.1；intentRouter/decisionNode/routerNode与审核/模板配置相对Agent f9fcff3均无变化，不重写state或历史基线 |
| 当前源/原材料 | `Verify-R1Closeout.ps1 -Label pre-expiry`：696项仅允许测试SHA2679AEAD67A1F6A6E7DA509939743E22928751763D65267DB2D7876F4100839A；277项保护文件0变化，原R0报告SHA9E677626...及R0receipt/source-final仍原字节 |
| 最新APK/完整购买 | 按卸载两包→确认不存在→安装执行，08:46:05安装SHA5F03AF43BB50A8DF3720B2E1F07A07331BE018B6DD083158723D58015279BF5E，设备pull与本地相同。原完整Maestro两不同UUID、数量1/2，双击创建/支付只有一单，实际PAID金额6713/16654分、一份一券 |
| 401及网络 | 普通JWT自然到期，不伪造令牌；三类页面真实401→登录原目标恢复；断开真实设备wifi/data且无默认网络，报价/创建/支付/成功核对失败与恢复均亲跑，缺报价不允许确认 |
| 当前Figma | MCP亲取07/08/09三个当前Frame完整树与截图，18/23/20节点、393×852，无截断；亲看两数量的实际三屏，核心结构/金额/警告/按钮/券齐全、可点，无阻塞溢出。真实imageUrl=null使用空图占位，不冒充示例图。详细`figma-visual-comparison.md` |
| 验证器故障 | 第一次全量Python路径遗漏、设备蓝牙系统弹窗、ADB启动超时、旧按钮命令目录错误均留首次失败并只修独立验证器/受影响流程；没有删失败美化全绿。原R0并发变价金额采样失败同样保留 |

09:23收尾预检：原目录/用户订单/券/退款/评价完整原行对照无变化；新Agent实际健康来源D仓、`service.app:create_app`、协议1.1，Spring当前PID43260/新AgentPID42144。公开报告和340个文本文件已做已知私有凭据精确匹配及JWT模式扫描，0命中。设备新报价自然等待仍未结束；目前剩余该边界和最终停用新资源、私有新账号轮换、网络/环境释放，不能据上述已过项提前整体PASS。

## R0 历史报告（原文保留）

状态：**FAIL，交实现者修复 B1**。本报告由本轮全新独立验收 Agent 编写，不是开发报告。当前阶段：已完成冻结核验、需求与变更审查、三端离线回归以及真实 HTTP/在线 Agent 主要购买场景；发现必须修复的全量测试隔离错误，按控制端最新要求停止扩跑；已保存首次失败、核查恢复并释放环境。最后更新：2026-10-02 08:33。

## 结论与实际卡点

| 维度 | 判定 | 依据 |
| --- | --- | --- |
| 需求审查 | 待完成独立运行覆盖 | 已读完整验收 brief、实施 brief、开发报告、规格 6–8 章及 UI-07–09；未运行的设备负面边界不能以开发自述补证 |
| 质量审查 | FAIL | 本轮新增金额测试仍依赖共享门店种子，在同源全量 H2 测试中读取空列表 |
| 运行验收 | FAIL | 后端全量 217 项中 1 error；Android/Agent 离线回归及已运行真实购买边界通过，不抵消阻塞 |
| 整体 | **FAIL** | B1 尚未修复复验，不宣称 Task6 独立通过，不执行 Task7 |

**B1：`PurchaseConfirmationTest.java:38` 金额测试隔离不完整。**

- 触发：在新建同源、无旧 `.runtime`、无 `target`、无链接的隔离快照原样运行 `mvn -q test`。
- 实际：exit=1，217 tests、0 failures、1 error、0 skipped。
- 首次错误：`displayedDiscountReconcilesWithPayableAmount` 的 `stores.findAll().get(0)` 抛出 `IndexOutOfBoundsException: Index 0 out of bounds for length 0`。
- 测试源 SHA256：`CD5D9B40A0516313DC9C1B0AF50E02F97128A1F4737CF2F5C9AEF8560E117760`。快照复制逐文件与冻结源核验一致，未修改产品测试、断言、种子或运行顺序。
- 首次失败原 XML：[PurchaseConfirmationTest XML](task6-independent-evidence/Backend-full-xml/TEST-com.fandazi.feature.purchase.PurchaseConfirmationTest.xml)。[全量汇总](task6-independent-evidence/Backend-full-summary.json)、[阻塞记录](task6-independent-evidence/blockers-r0.md)。完整日志仅忽略目录 `.runtime/task6-independent/backend-full.log`。
- 修复归属：Kuhn。修复后由本验收 Agent 使用新同源快照亲跑受影响全量 H2 测试；不能以定向金额 3 项通过替代此次失败。

## 基准与冻结

- 手机：`C:/fdz-build`，HEAD/BASE `158c21b385a11406d72ea12bd139092f0b574ff7`。
- Agent：`D:/project/-Demo-master`，HEAD/BASE `f9fcff3e8f2b9f41410762c5b6383f883b9cd742`。
- 先核查 `freeze-receipt.json` 与 `source-final.json`，696 个源文件逐一 SHA256 对照，差异 0。源清单 SHA256 `F86DF1F2499716B93DBAA4F205E4833B79AD68AFC39A4AFBE9912932A7906566`。
- 审查包：SDD `task6-review-package-r0.txt`，区分本轮精确 patch 与相对 HEAD 累计差异；原 Task7/8 dirty 只作为既有依赖，不归为本 Task6 新增。Task5 历史按钮守卫未在本轮修改。
- [独立冻结和快照采集](task6-independent-evidence/fingerprint-before.json)。本轮未修改产品、不 stage/commit、不起子 Agent。
- 运行服务亲核：Spring 8080 PID33452，JDK `ms-17.0.17`；新 Agent 8091 PID42144，Python `pinnT`，实际入口 `service.app:create_app`、来源 `D:/project/-Demo-master`、协议 1.1。未启动旧 Agent。

## 本轮命令与结果

| 命令/场景 | 独立实测结果 | 证据 |
| --- | --- | --- |
| `Invoke-IndependentChecks.ps1 -Mode Prepare` | 696 源文件核验通过；新快照逐字节一致、无链接，原私有清单/密钥保护指纹留档 | `fingerprint-before.json` |
| `Invoke-IndependentChecks.ps1 -Mode Backend`，内部 `mvn -q test` | 217 项，216 通过、1 error；首次失败原样保留 | `Backend-full-summary.json`、`Backend-full-xml/` |
| `Invoke-IndependentChecks.ps1 -Mode Agent`，内部 `pytest tests -q -p no:cacheprovider --basetemp <本轮新忽略目录> --junitxml=...` | exit=0，378 passed、21 skipped，无旧 WinError5；跳过不冒称在线验证 | `Agent-full-summary.json`、`agent-full.xml` |
| `Invoke-IndependentChecks.ps1 -Mode Android`，内部 `gradlew.bat :app:assembleDebug :app:testDebugUnitTest --rerun-tasks` | exit=0，171 项、0 failures/errors/skipped；本轮完整 XML 已保存，未依赖开发被覆盖的 XML | `Android-full-summary.json`、`Android-full-xml/` |
| `independent-api.py setup` | 首次独立验证器遇到 Windows 默认 GBK 读取 UTF-8 JSON 的 `UnicodeDecodeError`；属于验证器故障，两个新资源已提交 | 本报告保留首次错误；随后 `recover-setup` 只读恢复资源证据，没有重建或覆盖原事实 |
| `independent-api.py recover-setup` / `api` | 新账号、新套餐；两个价格 6713/8327 分、数量 1/2；各 8 次并发创建/支付同单；金额守恒、一份一券及 DECIMAL 分摊核对 | `api-checks.json`、`real-purchase-1.json`、`real-purchase-2.json` |
| 真实 HTTP 报价/授权边界 | 缺 quoteId、错会话、跨报价凭证拒绝且无写单；原凭证仍可正常使用；quoteStatus=CONSUMED 实际入库；消费凭证换新键拒绝；数量 0/4 拒绝 | `api-checks.json` |
| 真实隔离套餐价格变化 | 只改本验收新建套餐；旧报价实际拒绝，新摘要不能用旧凭证；新报价 6714 分/版本2，明确新授权后支付；恢复后亲读为6713分/版本1 | `isolated-price-change.json`、`api-checks.json` |
| `independent-api.py agent` | 通过 Spring→8091→真实模型；报价公开摘要完整、确认前无写单、取消原run无写单；原run恢复，创建/支付分次授权；支付前取消不改变订单 | `agent-*.json`、`agent-paid-database.json` |
| 在线 Agent 隔离变价 | 旧确认 FAILED 且无订单；新摘要新资源/新金额等待明确确认，新授权才能创建；取消待支付，亲读恢复本验收资源 | `agent-price-*.json`、`api-checks.json` |
| 最新 APK 安装 | 安装前亲 pull 旧设备包 SHA=冻结 27BB3A7D...；卸载旧包并确认两旧包均不在，随后安装同源独立新构建 APK；设备/本地 SHA 一致 | `apk-install.json` |

全部公开证据在 `docs/verification/task6-independent-evidence/`，敏感原始响应、账号口令和 Maestro 命令只保存在忽略 `.runtime/task6-independent/`。

## APK 与 Figma

- 冻结开发 APK：`27BB3A7D3D48CDD21339BE90AA64CBFBB0CD7D6647A540BABC344DDFEBC00A97`，安装前从设备亲自 pull 核验。
- 独立同源重新构建最新 APK：`5F03AF43BB50A8DF3720B2E1F07A07331BE018B6DD083158723D58015279BF5E`；安装时间 `2026-10-02T08:24:51.5402902+08:00`。设备 `base.apk` 同 SHA。重新构建字节与开发 APK 不同，未将两者混写为同一 APK。
- 已卸载 `com.fandazi.app`；`com.dirror.music` 卸载返回不存在类错误，随后 `pm list packages` 两个旧包均为空，才安装新 APK。
- 通过 Figma MCP 亲取当前文件 `FxsWKGs10z7BL1MhFAOqFN`、页面 `6:5`：UI-07 `29:41`、UI-08 `29:59`、UI-09 `29:82` 的设计上下文和截图。截图独立保存 `figma-UI-07.png`、`figma-UI-08.png`、`figma-UI-09.png`。
- 完整图层首次合并工具响应到20KB处截断，UI-09已分开补取完整图层；不将截断响应当作完整证据。
- 设备购买原 Maestro 在最新独立 APK 亲跑，exit=1，停在金额期望 `.*67\.14`；UI-07 实际为67.13。真实预报价与本验收在线 Agent 隔离变价测试交叠，08:28:30 已恢复，属独立验证器调度失误，不作产品失败结论。前后新增订单来自同期 Agent 确认，设备尚未提交。首次失败不删除、不覆盖：[验证器失败说明](task6-independent-evidence/verifier-failures.md)、[设备结果](task6-independent-evidence/maestro-quantity1/result.json)。后续设备和价格注入必须串行或不同隔离资源。
- UI-07 已将本轮真实截图与上述当前 Figma 亲自查看对照：底部白色确认单、遮罩、套餐图位/标题/规则、金额、数量、总价/优惠/实付和提交位置结构一致；截图97.13-30=67.13，无明显文字互相遮挡。数量步进器及缺图占位为运行数据/交互状态；这里只记录单屏观察，不代表整套视觉或购买流程通过。UI-08/09本轮尚无设备成功截图，不标 PASS。

## 隔离与历史偏差

- 本轮真实注册新随机验收账号，仅新账号购买；所有购买与价格注入使用本轮新建两套餐，未消费原 Task2/Task8 订单、券或评价资格，未清库、未假核销。
- 原目录行、门店、订单、券、退款等完整行在开始时只读采集并哈希保护，结束时重读；完整敏感事实只在忽略目录，公开只给指纹/差异。
- 对开发曾改价的原共享套餐 `7b5a48ce-8d4b-4a4e-9858-c9752011685d`，独立只读确认当前 9000 分/版本1，现存持久报价指纹与当前原价格版本一致。报告保留限制：历史前哈希是事后依据报价重算，无法据此证明当时完整行/所有并发影响无变化；未以开发 finally 声明替代独立恢复事实。[独立审计](task6-independent-evidence/shared-price-independent-audit.json)。
- 本轮隔离变价恢复后已亲自数据库读取验证。失败场景生成的本账号待支付订单保留，不删单美化结果。

## 未覆盖与后续复验

因 B1 真正阻塞且控制端要求尽早返回 FAIL，以下未运行项明确待验，不使用开发自述或替身补证：HTTP/在线 Agent 自然五分钟过期后实际拒绝及恢复、设备第二数量完整 Maestro、设备401原目标/数量恢复、支付页/成功页401、设备真实断网与重试、设备自然报价过期、设备隔离价格变化拒绝与重新确认、Task5 历史按钮设备回归及逐屏视觉完整对照。

自然过期报价与在线确认已真实准备，只是尚未完成到期后提交，因此不标 PASS。ASR、核销、Task7完整查询、Task8售后不在本 Task6 整体范围，不用尚未完成的后续功能充当本轮阻塞。

修复后保留已过独立证据，仅复验受影响项并继续上述尚未覆盖的本Task边界；只有 B1 修复及必要边界完成独立运行后，才能重新判定整体。

## 收尾与环境交接

- `release-isolation.py` 实际exit=0：原目录、门店、订单、券、退款、评价等开始时已有完整行逐行重读，差异0；仅本轮两个新增隔离套餐停用，价格均与新增时一致/版本1，所有真实订单保留。[原事实复核](task6-independent-evidence/original-facts-after.json)、[新隔离资源收尾](task6-independent-evidence/isolated-resources-release.json)。未以开发 finally 声明代替核验。
- `Release-Environment.ps1` 实际exit=0，08:32:16结束：696源文件再次核对差异0，清单SHA与开始一致；原私有夹具/服务密钥清单文件指纹均未变。没有产品修改、stage、commit、子Agent或Task7执行。[收尾指纹](task6-independent-evidence/environment-release.json)。
- 本轮后端/Agent/Android/设备所有已启动命令会话均已结束，设备应用已force-stop。不再占用设备、Maestro、Gradle或服务测试权；原Spring8080 PID33452和Agent8091 PID42144仍运行，未停止原服务。
- B1已写控制端可读阻塞文件与本报告。尝试桌面线程消息因原生父子线程限制被拒，未声称投递成功；通过本轮最终答复交回控制端/Kuhn修复。
