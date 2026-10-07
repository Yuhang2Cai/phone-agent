# Task4 开发记录（待独立验收）

## 范围与边界

- 唯一实现 Agent；仅执行 Task4 真实浏览、分类、排序筛选、门店三 Tab、不同套餐详情与新 Agent 只读推荐。
- 依据：完整读取 task-4-brief.md、主计划 Task4 与全局约束、design spec、两仓 AGENTS.md；不修改主计划/spec/公共台账，不创建子 Agent，不提交或暂存，不进入 Task5。
- 两个现有工作区：C:/fdz-build（junction）和 D:/project/-Demo-master。保留 Task8 与其他既有 dirty/未跟踪文件；不初始化 fixture、不消费券、不改订单退款评价。
- 本报告只记录开发自测；独立验收由控制端安排，当前状态：DONE_WITH_CONCERNS（开发自测通过，待独立验收）。关注项是既有网关审计容量与真实目录素材质量，不宣称独立验收通过。

## 子步骤 1：基线与 Figma（2026-10-02）

实际读取/检查命令：

```powershell
Get-Content -Raw C:/fdz-build/.superpowers/sdd/2026-09-29-group-buying-agent-refactor-implementation/task-4-brief.md
Get-Content -Raw C:/fdz-build/docs/superpowers/plans/2026-09-29-group-buying-agent-refactor-implementation.md
Get-Content -Raw C:/fdz-build/docs/superpowers/specs/2026-09-29-group-buying-agent-refactor-design.md
Get-Content -Raw C:/fdz-build/AGENTS.md
Get-Content -Raw D:/project/-Demo-master/AGENTS.md
git -C C:/fdz-build status --short
git -C D:/project/-Demo-master status --short
```

上述命令退出码 0。初始手机 HEAD 由 brief 给出 d6c6441，新 Agent HEAD 8265f08；将在环境核查再次实读。新 Agent 原 dirty：service/engine.py、service/dag_runtime.py、tests/test_agent_service_reads.py、实验文档等；手机大量 Task8 和历史 dirty，后续逐文件记录是否原 dirty。

Figma MCP get_design_context（包含实际截图，未关闭 screenshot）已取得当前文件 FxsWKGs10z7BL1MhFAOqFN 的六页：UI-01 63:2、UI-02 63:88、UI-03 64:2、UI-04 64:88、UI-05 64:144、UI-06 64:225。技能参数 figma-design-to-code，目标 Kotlin/XML。取得完整可编辑结构并查看六张截图，非 sparse；业务样例图片/字段不作为 Android 数据资产。静态图标将核对既有资源或下载精确资产后绑定，资产路径稍后登记。

实际缺陷：

- HomeFragment 构造 store-001 等演示名单，首页排序/筛选传入 initialQuery，位置 XML 写死北京市朝阳区。
- SearchFragment 使用演示名单、本地排序与宽泛 OR 过滤，没有真实 API 查询。
- StoreFragment 仅套餐列表接后端；头部 XML、菜品、评价、评分和图片仍为静态样例，无有效重试。
- DealDetailFragment 已读取真实列表，但横向切换后购买取原导航 ID；缺失败重试、实际选项图片，XML 初始内容和备注仍包含静态业务承诺。
- Store API 有分类/人均/有效定位距离排序，缺搜索词与展示摘要；后端公共数据必须补齐以便 UI 不制造事实。

首次探索失败（不涉及产品执行）：rg 指向不存在 docs/ui-acceptance；读取 GroupBuyingRepository.kt 的猜测路径不存在。已改为 rg --files 定位实际模块，未创建替代目录或覆盖源文件。

## 后续记录

将在每个子步骤追加真实命令、退出码、失败与修复、写集、接口/在线推荐证据、APK 哈希及安装时间、Maestro 与截图对照。不把未执行检查写成通过。

## 子步骤 2：Spring 真实目录

- 业务测试先行（不对 UI 使用 TDD）：Maven `-f backend/pom.xml -Dtest=StoreDealApiTest test` 首轮退出 1，6 项中 3 项失败：关键词返回 29 家而非空、缺 reviewCount、人均非法范围返回 200。
- 添加 StoreBrowseService/StoreBrowseResponse，保留原工具投影，公开页面补城市、真实菜单、全量评价评分/分布、本店真实套餐摘要与可空距离/图片；StoreService 增加服务端分类分组与范围校验。
- 二轮同命令退出 1，5/6 通过；唯一剩余是测试误数饮品种子为 4 家，实际 stores.json 为 3 家（006/018/026）。修正预期为 3，产品不增数据迎合错误预期；下一轮复验。
- 原始写集：上述两新 Java 文件、原 clean StoreService.java/StoreController.java/StoreDealApiTest.java。无迁移、无种子改动、无数据重置。
- 初始 HEAD 已实读手机 d6c64418b33a54805ae800b71ca7f23284e6ce97；8080 PID44128、8091 PID53472。正式重启将导入共享环境后明确 TASK2_FIXTURES_ENABLED=false/TASK8_FAILURES_ENABLED=false。

## 子步骤 3：真实浏览绑定与推荐工作流

- Spring 三轮定向测试：`mvn -q -f backend/pom.xml -Dtest=StoreDealApiTest test` 退出 0，6/6（首次/二次失败见上）。随后按控制端审查改成评价按候选门店一次批量查询、按门店分组；单门店详情按 storeId 查询，套餐也改按 storeId 查询，后续再复验。
- 新 Agent 新增 `service/recommendation.py`，在既有 dirty engine.py 的 `_read` 内分派套餐推荐；城市明确时先 search_stores 再 list_deal_packages，最多 3 家门店；保持既有三层分类、背景 state 与 0.80，不进 DAG、不改 17 工具 Schema、不写购买。
- 测试先行 `python -m pytest tests/test_group_buying_read_tools.py -q` 首次退出 1（有城市套餐推荐仍追问门店，无工具调用）；实现后 `python -m pytest tests/test_group_buying_read_tools.py tests/test_agent_service_reads.py -q` 退出 0，10/10。这里只是离线回归，不宣称在线推荐通过。
- Figma 截图已实际下载到 `docs/verification/task4-evidence/figma/UI-01.png` 至 `UI-06.png`，各 393×852；下载退出 0。既有四导航/返回/搜索/漏斗资源复用，未将业务示例照片作为 Android 兜底。
- Android 新增 CatalogRepository/DTO、CatalogViewModel、CatalogControls、CatalogRenderer，首页/搜索按真实响应绑定；城市来自 API；距离点击才申请粗定位，拒绝/不可用保持普通浏览；关键词、分类和排序独立。
- 门店三个 Tab 分别读取真实套餐/菜单/评价；头部用真实评分/数量/图片；评分分布采用全量摘要，列表为最新 20 条。套餐详情先查详情验证所属门店，再查同店可售选项；切换同步真实 ID、内容、价格及图片；加载失败禁用购买并提供重试。
- 首次 `JAVA_HOME=IntelliJ.../jbr; gradlew.bat assembleDebug` 退出 1；Kotlin 编译通过，JdkImageTransform 因该 JBR 没有 jlink.exe 失败。已定位既有 JDK `C:/Users/caid0/.jdks/ms-17.0.17`，将仅切换构建 JDK，不安装或重启全套环境。
- 一次 apply_patch 试图对同文件 Delete/Add 被工具原子拒绝，无文件变化；改为两次受控替换原 clean 的 Home/Search 文件后继续，保留所有写集外 dirty。

## 子步骤 4：构建、导航回归与必要重启

- `JAVA_HOME=C:/Users/caid0/.jdks/ms-17.0.17; JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8; .\gradlew.bat assembleDebug --console=plain` 退出 0，37 tasks、24 秒。只切换已有完整 JDK 修复缺 jlink，未安装组件。
- Maven `-q -f backend/pom.xml -Dtest=StoreDealApiTest,AgentContractSchemaTest,AgentGatewayV1ContractTest test` 退出 0；XML 计数将在收尾汇总。评价批量按候选 storeId 查询已复验。
- 原 dirty `MerchantNavigationPagesTest.kt`（Task8 已修复退役路由生命周期测试）仅将 Task4 首页/搜索的演示名单前置替换为合法 UUID 的 CatalogRepository 测试边界；旧 merchant 生命周期、导航身份、失败无假套餐、返回不重复导航断言全部保留。没有在生产添加测试 setter。
- `gradlew.bat testDebugUnitTest --tests '*HomeSortTest' --tests '*MerchantNavigationPagesTest' --console=plain` 首轮退出 1，6 项 1 失败，重试文案实为“优惠 加载失败，点击重试”，测试遗漏空格。按真实文案修正断言，再复验。
- 服务已确认 8080/8091 无 Established 请求；导入 `scripts/Initialize-FandaziEnvironment.ps1` 后显式设 `TASK2_FIXTURES_ENABLED=false`、`TASK8_FAILURES_ENABLED=false`，仅检查并停止入口匹配的 PID44128/PID53472。调用根目录 `start-fandazi-services.ps1`、`start-fandazi-agent.ps1`，退出 0，启动请求 PID44868/PID42280，隐藏窗口。健康与新 PID 后续实测。
- 曾误读 scripts/start 脚本及 service/extractor.py（不存在）退出 1；已用 rg --files 找到根 start 脚本和 service/providers.py，不新建替代入口。

## 子步骤 5：真实接口与推荐恢复（已实测）

- `RUN_TASK4_LIVE=1; python -m pytest tests/test_task4_browse_live.py -q` 首轮退出 1，2/2 FAIL：门店评价 500（LazyInitializationException，StoreReviewResponse.from 读取已脱离会话的 store.publicId）；火锅被模型抽为 keyword，套餐发现忽略 keyword，返回其他品类。公开网关卡片实际为扁平兼容投影，测试初稿误以为 data 嵌套，亦已修正。
- 新增评价独立事务边界回归，Maven 首轮 7 项中 1 ERROR，准确复现上述懒加载。将评价查询与 DTO 转换移至 StoreBrowseService 只读事务内，保留单门店查询，复跑与契约测试退出 0。
- 仅查看隔离任务最新 snapshot 的 intent/slots，确认实际为 `{keyword:火锅,cityCode:demo}`。只修 `service/providers.py` 槽位提示的专名/品类边界与显式目录城市代码保留，不改分类规则、阈值、17工具。新增提示回归初次误用未安装 pytest-asyncio 失败，改标准库 asyncio.run 后准确红；无安装插件。
- 控制端补充范围审查后：无门店/城市先等待 cityCode，合法城市恢复 search_stores→list_deal_packages；空白/控制字符/超长补充继续 waiting_input 且不污染原槽位；完整 UUID 可明确选店。原 Task8 dirty `tests/test_agent_service_reads.py` 仅调整套餐范围的过期预期，保留其他售后/FAQ回归。
- `python -m pytest tests/test_group_buying_read_tools.py tests/test_agent_service_reads.py -q` 退出 0，15/15（7.34 秒）。`gradlew.bat testDebugUnitTest --console=plain` 退出 0，XML 计数待汇总。
- 第二次必要重启仍导入共享环境后关闭两个开关，仅停止匹配入口 PID49804/PID42280，启动请求 PID48612/PID28744。随后在线套件新增缺城市恢复场景：`RUN_TASK4_LIVE=1; python -m pytest tests/test_task4_browse_live.py -q` 退出 0，3/3，15.72 秒。
- 在线证据：`task4-evidence/browse-api.json`、`recommendation-online.json`、`recommendation-city-resume.json`。目录 29 家营业店，城市实际只有 demo；完整 UUID、服务端人均/定位排序、AND 筛选、非法范围400、真实菜单、摘要评分分布、两个不同套餐详情均实读。推荐在线确认入口 service.app:create_app，单目标 isComposite=false，真实 search_stores/list_deal_packages 返回4张火锅套餐；缺城市首轮 NEEDS_INPUT/cityCode，补 demo 后 COMPLETED，保留品类、无写工具。
- 提醒控制端：首轮未过滤全城市 search_stores 产生 `agent_events.payload` 超长审计写入日志，但公开读取未阻塞；归属网关审计容量，不在 Task4 静默改列/迁移。修品类后本轮小范围未触发，仍作为独立验收关注。

## 最终公开浏览契约（请控制端同步，未修改主 spec）

- `GET /api/stores` 返回扁平数组；`GET /api/stores/{publicId}` 返回同一完整对象。既有非空字段：`publicId`（随机 UUID string）、`brandName/name/cityCode/address/phone/category/openingHours`（string）、`latitude/longitude`（门店实际 number）、`averageSpendCents`（int 分）、`open`（boolean）。
- 新字段名最终为 `rating`（number，真实全量评价均值四舍五入1位，无评价 null）、`reviewCount`（int，全量数量）、`reviewDistribution`（int[5]，一星→五星，评级按 HALF_UP 归桶，总和=reviewCount）、`distanceMeters`（long/null，有有效请求位置时按Haversine米四舍五入，否则null）、`imageUrl/imageAlt`（string/null，仅本店 featuredDeal 的原始图/替代文本，绝不取其他店图）、`featuredDeal`（对象/null，无当前可售套餐为null）。
- `featuredDeal` 与 `GET /api/stores/{publicId}/deals`、`GET /api/deals/{publicId}` 相同：`publicId/storePublicId/title/description` string，`priceCents/originalPriceCents/inventory/purchaseLimit` int，`validFrom/validTo` YYYY-MM-DD业务日期，`active` boolean，`imageUrl/imageAlt` string/null。摘要选择本店可售 PRICE_ASC 第一项；同价按 UUID 稳定打破平局。
- 查询彼此独立 AND：`keyword`（门店/品牌/品类/当前套餐标题描述/已上架关联菜单名称，trim后不分英文大小写）、`category`（精确原始品类或六 UI 分组；推荐/空不筛）、`sort`（RECOMMENDED/PRICE_ASC/PRICE_DESC/DISTANCE；默认推荐，大小写折叠，未知400）、`cityCode`（实际精确代码；缺省不限制）、`latitude/longitude`（经纬度number完整对）、`minPrice/maxPrice`（int 人均分，负数/倒置400）。DISTANCE 缺/不合法位置400；其他排序位置缺/不合法只令距离null，不默认用户位置。推荐顺序是UUID稳定序，不臆称模型评分。
- `GET /api/stores/cities` 新路由返回 `[{cityCode:string,name:string}]`，从真实营业门店代码去重排序；目录无城市名列，当前 name=cityCode（demo），不是虚构行政区/定位事实。不存在任何写死城市名单。
- `GET /api/stores/{publicId}/dishes` 新路由返回 `[{name:string,description:string,priceCents:int,imageUrl:string|null}]`，仅本店 merchant 关联真实已上架菜单，金额精确转分；无关联/数据为[]，无外卖ID/加购路由。
- `GET /api/stores/{publicId}/reviews` 既有字段不改：`storePublicId/reviewerName/content` string、`rating` number、`createdAt` UTC ISO8601 string；按createdAt降序最多20项，无头像字段。图片缺失、空菜单/评价/套餐是业务状态，网络/协议失败是重试状态，不互相冒充。
- 原17工具投影/Schema不改；公开网关卡片依旧扁平兼容字段，不把新增页面聚合 DTO 强塞工具。

## 子步骤 6：最新 APK 与 Maestro（早期失败及修复）

- ADB emulator-5554，1080×2400、420dpi，Maestro2.8.0，Java17.0.17。卸载 com.fandazi.app 成功；卸载 com.dirror.music 返回 DELETE_FAILED_INTERNAL_ERROR，但 pm list packages 确认两旧包均已不存在。最新APK安装成功，时间2026-10-02T03:02:38.1717044+08:00，SHA256 `B126CB6AF1D77E01B2224DC01950245A26798DA9DB5AFE8E29C673AC0B6B1498`。
- 新增 `scripts/run-task4-browse.ps1` 从实际 API 读取城市/门店/套餐 UUID、金额，传入已更新 `maestro/group-buying-01-browse.yaml`；先首页分类→人均升降→拒绝定位→城市/分类筛选→搜索空/清空→门店3Tab→套餐A/B切换及分别打开→返回原状态。
- 首次运行器错误把 Invoke-RestMethod 返回数组包为单个管道对象，判数据不足；修正直接使用 JSON 数组。第二次 Maestro 启动和真实首屏成功，但 takeScreenshot 绝对路径被2.8沙盒拒绝；查看真实 --help 后改相对截图名并指定 --test-output-dir，未绕过路径保护。重跑中，保留首轮 debug 证据 `maestro-development/.maestro/tests/2026-10-02_030424`。
- r2 在定位拒绝步骤失败。实际失败截图显示距离9922.8km而非系统权限对话框，ADB hierarchy没有permission_deny_button；根因 Maestro launchApp 默认允许全部权限，并非生产硬编码坐标。查官方 launchApp/Permissions 文档后明确 `location: unset`，r3 已亲自点击拒绝并核对人均降序/未定位保持。来源：https://docs.maestro.dev/reference/commands-available/launchapp。
- r2首屏缩略图缺图文字在48dp区域重叠；新增 Task4 `CatalogImages.kt` 小图符号状态适配，实际图片仍Glide原URL，状态无障碍可读。大图复用既有 AfterSaleImageLoader，未改Task8模块。r3截图显示小图无文字重叠。
- r3完整跑到三Tab/两个套餐切换与各自重新打开，全部真实API对应；最终返回搜索的可见卡片断言失败，查询/结果保留但列表回顶部。根因原ScrollView无ID/偏移保存；给Home/Search稳定ScrollView ID，CatalogViewModel保存实际scrollY、条件改变清零、详情返回原偏移，未放宽Maestro断言。r3失败截图/命令保留。
- r3发现真实长菜品描述导致原固定91dp行挤出，改为最小104dp+随受约束文字增高、标题/描述最多2行；头部24dp安全留白、去静态五星初始值、实际原价删除线。待最终APK重新安装全流程复验。r3截图原URL确实为已有seed-food图片（卡通文字资产），不是Task4生成/替换的餐图；保留原数据，不冒称真实餐厅照片。

## 子步骤 7：适用全量回归

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
& 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' -q -f backend/pom.xml test
$files=Get-ChildItem tests/test_agent_service*.py | ForEach-Object FullName
python -m pytest @files tests/test_group_buying_read_tools.py tests/test_after_sale_workflow.py -q --junitxml=C:/fdz-build/docs/verification/task4-evidence/agent-regression.xml
$env:JAVA_HOME='C:/Users/caid0/.jdks/ms-17.0.17'
.\gradlew.bat assembleDebug testDebugUnitTest --console=plain
```

- Maven全量退出0，52 XML套件213 tests、0 failures/errors/skipped。所有业务测试使用各自隔离H2，不连接/改动真实订单夹具。
- 新Agent适用服务回归退出0，37 passed、5 skipped（真实在线套件受开关默认跳过；Task4在线已独立执行3/3）。没有重跑售后写交易或Task8夹具。
- Android回归加入 `CatalogRepositoryTest.kt` 四项HTTP/Gson边界：独立参数编码、无假定位、空成功与失败区别、合法UUID及未知事实/缺字段拒绝。滚动修复后最终重跑退出0，28套件147 tests、0 failures/errors/skipped；最终APK下述重新安装后运行全部浏览自测。

## 写集归属与必要依赖

- `D:/project/-Demo-master/service/engine.py` 只修改 `_read` 的 Task4 分派：增加 recommend_deals调用、从旧必选storeId集合移除deal_recommendation、移除被替代旧套餐分支。其余所有 diff（FAQ/coupon省提取、inFlightWrite处理、_fill_user_slot、退款/投诉恢复）均原Task8 dirty，不属于Task4提交；控制端后续仅选对应_read hunks，本Agent不暂存。
- `service/providers.py` 仅本次模型槽位提示边界与显式目录城市代码说明；`service/recommendation.py` 新模块复用现有_call/_pause/_finish/卡片/持久化，是Task4新增写集。`tests/test_group_buying_read_tools.py`、`tests/test_task4_browse_live.py` 新增。原dirty `tests/test_agent_service_reads.py` 只更新套餐缺范围等待cityCode过期预期，不捆绑Task8其他新增城市/FAQ测试。
- Android必要原dirty依赖：FandaziApplication只增加目录仓库初始化；Manifest只增加粗定位权限；原dirty MerchantNavigationPagesTest只修真实目录前置/文案断言。AfterSaleImageLoader/ApiClient/Session/导航/售后卡片等均复用原运行版本，未改、未回滚。
- 具体所有Task4路径及证据在收尾逐条列出；Git全仓dirty或git diff --stat不是Task4写集，不代表归属。本任务未修改主plan/spec/台账，控制端的文档更新仍共存。

## 子步骤 8：最终 APK、完整真实路径与错误重试

最终构建与安装（替代上文所有早期 APK，不使用旧轮截图冒充）：

```powershell
$env:JAVA_HOME='C:/Users/caid0/.jdks/ms-17.0.17'
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
.\gradlew.bat assembleDebug testDebugUnitTest --console=plain
& C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe -s emulator-5554 install C:/fdz-build/android-app/app/build/outputs/apk/debug/app-debug.apk
& C:/fdz-build/scripts/run-task4-browse.ps1 -EvidenceSuffix final
& C:/fdz-build/scripts/run-task4-browse.ps1 -EvidenceSuffix keyword -Flow keyword
& C:/fdz-build/scripts/run-task4-browse-retry.ps1
```

- Gradle工作目录为 `C:/fdz-build/android-app`；最终构建退出0。再次卸载旧应用并确认干扰包不存在，然后安装最新APK；主浏览/关键词/错误重试均复用这一个APK，中间无产品源码修改。
- APK：`C:/fdz-build/android-app/app/build/outputs/apk/debug/app-debug.apk`。SHA256：`9711942492026950C1D55F1906BB0364E91EEADDE1E075B7A069226AC38F0B54`；主机记录实际安装时间 `2026-10-02T03:13:34.1621183+08:00`。ADB dumpsys显示 `versionName=1.0/versionCode=1`、设备 `lastUpdateTime=2026-10-01 19:13:12`，设备时钟格式不当作主机安装时间。
- 主浏览Maestro退出0：真实首页→饮品分类→人均升序/降序→点击距离请求权限并亲自拒绝→保持人均降序/未定位→API城市demo/火锅筛选→搜索无命中→清空→火锅筛选→快餐店列表滚动→实际门店优惠/菜品/评价→套餐A详情→横向B切换→返回后单独打开B→返回搜索同一已浏览门店可见→返回首页原火锅/降序/demo保留。保留原返回断言，没有放宽为仅检查页面存在。
- 关键词补充Maestro退出0：实际套餐文本含 `1` 的关键词与火锅分类AND→唯一真实火锅店→返回后查询 `1`、分类和卡片保留。中文门店专名正向命中由最后一轮真实API测试额外验证；ASCII `1` 仅避免 ADB 键盘中文输入限制，不是业务兜底或名称fixture。
- 错误/恢复两条Maestro均退出0：本人先force-stop应用关闭闲置连接，核对PID23960为Spring入口后只停该进程，错误页断言“门店加载失败，点击重试”且无store_name；finally导入共享环境后再次显式关闭两个安全开关，恢复Spring；不重新launchApp、不清UI状态，在同一错误页点击重试，真实列表出现且错误消失。
- 重试脚本启动请求PID18336，实际Spring监听PID53264；Agent保持PID28744没有重启。没有停MySQL、Docker、模拟器、旧Agent或修改业务记录。

最终Maestro证据（路径均相对 `C:/fdz-build/docs/verification/task4-evidence/`）：

- `maestro-final/2026-10-02_031338/group-buying-01-browse/`：执行结果/层级/10张截图；截图在 `takeScreenshot/`，含UI01–06、A/B、拒绝定位、搜索空与返回首页。
- `maestro-keyword/2026-10-02_031657/task4-search-keyword/`：`search-keyword-hit.png`、`search-keyword-returned.png`。
- `maestro-retry/2026-10-02_032240/task4-browse-retry-error/`：`browse-network-error.png`。
- `maestro-retry/2026-10-02_032325/task4-browse-retry-recovered/`：`browse-network-recovered.png`。
- 早期失败保留在 `maestro-development/`、`maestro-development-r2/`、`maestro-development-r3/`；独立验收应使用final轮，不把修复前证据当最终页面。

## 子步骤 9：最后一轮公开接口/新 Agent 与截图对照

重试恢复后实跑最新在线脚本（含新增中文专名+分类正向AND断言）：

```powershell
# 工作目录 D:/project/-Demo-master；不打印共享密钥。
$env:PYTHONUTF8='1'
. C:/fdz-build/scripts/Initialize-FandaziEnvironment.ps1
$env:TASK2_FIXTURES_ENABLED='false'
$env:TASK8_FAILURES_ENABLED='false'
$env:RUN_TASK4_LIVE='1'
& C:/Users/caid0/anaconda3/envs/pinnT/python.exe -m pytest tests/test_task4_browse_live.py -q --junitxml=C:/fdz-build/docs/verification/task4-evidence/task4-live-final.xml
```

退出0，3 PASS、0 FAIL（19.33秒，套件总时长不是模型单请求延迟）。`browse-api.json`、`recommendation-online.json`、`recommendation-city-resume.json` 已刷新为本轮真实响应，JUnit位于 `task4-live-final.xml`。所有推荐均公开Spring网关→D新Agent→Spring工具，不伪造内部run/trace/授权。

- 29家营业门店、真实城市demo；公开UUID、整数分、服务端人均/实际经纬度距离顺序与400边界、关键词+分类AND、关联菜品、最多20评价/全量摘要、两套餐GET详情均实际核对。
- 明确城市/火锅：`isComposite=false`，实际工具序列 `search_stores,list_deal_packages`，4张deal卡与各真实GET金额/storePublicId逐一相同。
- 缺城市：首轮 `NEEDS_INPUT`、`candidateCart.inputKey=cityCode`、无tool_result；补实际 `demo` 后 `COMPLETED`、同样两工具及4张真实火锅套餐卡，无购买/退款/评价/核销工具。
- 离线跨引擎恢复：重建RunEngine后空白、控制字符、内部空格和21字符补充均保持waiting_input及原品类；完全空请求入口422不改变等待快照；随后合法城市完成两只读工具，恢复轮不重分类或重提槽位。另有既有明确门店路径回归，未强迫用户城市优先。

截图人工核对：亲自打开Figma六张原图与final实际六页，亦打开关键词/返回/错误/恢复图，检查结构、真实字段和文字边界。不是仅凭Maestro退出0判视觉通过。Figma参考在 `figma/UI-01.png` 至 `UI-06.png`；实际截图来自1080×2400、420dpi模拟器。

| 页面 | Figma节点 | 最终截图名 | 本人开发对照结论与事实差异 |
| --- | --- | --- | --- |
| UI-01 | 63:2 | UI-01-home.png | 绿头/位置选择/搜索/六分类/人均距离筛选/卡片/四导航保留；无首页AI横幅/浮球。实际未选择城市显示选择城市，选后demo；距离未知显示未定位，不沿用北京或1.2km。 |
| UI-02 | 63:88 | UI-02-search.png | 返回/输入/提交/清空、独立分类及筛选、实际结果数保留；真实火锅只有一家，不能为接近设计56条补假门店。正向关键词见补充截图。 |
| UI-03 | 64:2 | UI-03-store-deals.png | 左侧真实店名评分数/右侧原URL/三Tab及优惠行、实际现价原价折扣保留。安全头部比原147dp多24dp留白；长真实名称不与图重叠。4个套餐均来自该店，不改成设计经典模板。 |
| UI-04 | 64:88 | UI-04-store-dishes.png | 三Tab/本店真实菜单/说明/金额保留，无加购按钮；长菜名描述限制2行，行高最小104dp可增长，价格不挤出。 |
| UI-05 | 64:144 | UI-05-store-reviews.png | 全量4.3/16条和五星至一星数量11/4/1/0/0与API相同；列表实际日期/正文，长用户名省略。协议无头像，使用通用人物符号；显示真实数值评分，不伪造设计五颗满星。 |
| UI-06 | 64:225 | UI-06-deal-A.png / UI-06-deal-B.png | 横向真实套餐选择、标题/有效期/库存/限购/description/金额与选中态同步；底部门店及购买入口保留但本任务不购买。协议无可选菜明细/桌数/随时退承诺，未照抄设计样例。 |

与API一致的截图关键身份：门店 `447d78f2-1c74-4ecf-8e41-7e73b8ddd90b`（山海食集春风店）；A `281afc47-8ff6-4cee-abeb-a61c33815c58`（双人招牌盖饭套餐3，8800分，库存80）；B `74a011ae-518a-4cfc-bcfe-d2ee6d537b7d`（双人招牌盖饭套餐1，8800分，库存73）。当前同店套餐价格/说明相同是实际目录数据，标题/UUID/库存不同并分别读详情，未编造价格区别。火锅店 `9c0e47d3-a26e-4ab3-8ef1-ccec04003e3f`（云上火锅静安店），真实4个套餐。

## 精确最终写集

以下相对路径分别归属指定仓根；新增/修改指本任务内容，混合文件不能按整文件纳入Task4。完整当前文件SHA256生成在 `task4-evidence/source-fingerprints.json`（41个源码/测试/运行器文件，含混合文件的原Task8内容，指纹不表示归属）。所有本次新增/修改代码与核心分支采用中文说明；无迁移、fixture、购买/核销或ASR修改。

交接用逐行路径：`C:/fdz-build/docs/verification/task4-evidence/task4-write-set.txt`，43个绝对文件路径（41个源码/测试/运行器+本报告+EXP-015所在文档），一行一路径、无目录通配。实查43个均存在、无重复，41个源指纹全部一致。混合文件只按上述Task4 hunks纳入，不因为在manifest就整文件提交。生成型证据另列 `task4-evidence/task4-evidence-files.txt`，它与源指纹/写集manifest均为交接元数据，不是额外产品修改。

手机仓 `C:/fdz-build`，新增产品/测试：

```text
backend/src/main/java/com/fandazi/feature/catalog/StoreBrowseResponse.java
backend/src/main/java/com/fandazi/feature/catalog/StoreBrowseService.java
android-app/app/src/main/java/com/fandazi/app/feature/home/CatalogControls.kt
android-app/app/src/main/java/com/fandazi/app/feature/home/CatalogViewModel.kt
android-app/app/src/main/java/com/fandazi/app/feature/store/CatalogRepository.kt
android-app/app/src/main/java/com/fandazi/app/feature/store/CatalogRenderer.kt
android-app/app/src/main/java/com/fandazi/app/feature/store/CatalogImages.kt
android-app/app/src/test/java/com/fandazi/app/feature/store/CatalogRepositoryTest.kt
```

手机仓，修改Task4目录/页面/测试：

```text
backend/src/main/java/com/fandazi/feature/catalog/StoreService.java
backend/src/main/java/com/fandazi/feature/catalog/StoreController.java
backend/src/main/java/com/fandazi/feature/catalog/StoreReviewRepository.java
backend/src/main/java/com/fandazi/feature/catalog/DealPackageRepository.java
backend/src/main/java/com/fandazi/feature/catalog/DealPackageService.java
backend/src/test/java/com/fandazi/feature/catalog/StoreDealApiTest.java
android-app/app/src/main/java/com/fandazi/app/feature/home/HomeFragment.kt
android-app/app/src/main/java/com/fandazi/app/feature/search/SearchFragment.kt
android-app/app/src/main/java/com/fandazi/app/feature/store/StoreFragment.kt
android-app/app/src/main/java/com/fandazi/app/feature/deal/DealDetailFragment.kt
android-app/app/src/main/java/com/fandazi/app/feature/deal/GroupBuyingModels.kt
android-app/app/src/main/java/com/fandazi/app/feature/deal/GroupBuyingRepository.kt
android-app/app/src/main/res/layout/fragment_home.xml
android-app/app/src/main/res/layout/fragment_search.xml
android-app/app/src/main/res/layout/fragment_store.xml
android-app/app/src/main/res/layout/fragment_deal_detail.xml
android-app/app/src/main/res/layout/item_store_dish.xml
android-app/app/src/main/res/layout/item_store_review.xml
```

手机仓，明确混合原dirty文件中的必要小改：

```text
android-app/app/src/main/AndroidManifest.xml：仅ACCESS_COARSE_LOCATION及说明，原RECORD_AUDIO不动。
android-app/app/src/main/java/com/fandazi/app/FandaziApplication.kt：仅CatalogRepository属性及初始化。
android-app/app/src/test/java/com/fandazi/app/feature/search/MerchantNavigationPagesTest.kt：仅真实目录测试前置与读取失败文案；原退役导航生命周期测试保留。
```

DealDetail/GroupBuyingModels/Repository等已有运行上下文也包含历史未提交工作；本次仅套餐图片解析、真实选项/内容/ID同步、错误重试与详情页事实展示。原购买流、支付、确认单与售后仍原样复用，不宣称本任务新实现或已验收。

手机仓，Maestro/运行器与开发证据：

```text
maestro/group-buying-01-browse.yaml（修改）
maestro/task4-search-keyword.yaml（新增）
maestro/task4-browse-retry-error.yaml（新增）
maestro/task4-browse-retry-recovered.yaml（新增）
scripts/run-task4-browse.ps1（新增）
scripts/run-task4-browse-retry.ps1（新增）
docs/verification/2026-10-02-task4-development.md（新增，本报告）
docs/verification/task4-evidence/（新增，公开响应/XML/截图/源指纹，无凭据）
```

新Agent仓 `D:/project/-Demo-master`：

```text
service/recommendation.py（新增）
tests/test_group_buying_read_tools.py（新增）
tests/test_task4_browse_live.py（新增）
service/providers.py（仅槽位提示：品类/专名边界及显式城市代码）
service/engine.py（仅_read的Task4 hunks，其他原dirty均Task8）
tests/test_agent_service_reads.py（仅套餐缺范围等待cityCode预期，其他原dirty均Task8）
docs/experiments/intent-routing-experiments.md（仅追加EXP-015；原Task8全文保留）
```

必要依赖但本次未写：Android现有ApiClient、导航、会话、Glide/AfterSaleImageLoader及其他Task8运行代码；新Agent既有RunStore、_call/_pause/_finish、17工具客户端与原分类器。`service/dag_runtime.py`、FAQ/售后恢复、原旧agent-service均未由Task4改动；共享启动脚本/安全开关配置/种子/迁移未修改。主plan/spec/公共台账的新diff归属控制端，不是本任务写集。

## 交接状态与未覆盖项

- 状态：DONE_WITH_CONCERNS，仅开发自测通过，待控制端独立验收。没有暂存、提交、勾选验收或创建子Agent，没有进入Task5，ASR/核销继续延期。
- 最终测试证据：Spring全量213/213；Android全量147/147；新Agent适用离线37PASS/5SKIP（含Task4定向15/15）；最后一轮真实接口/公开网关3/3；主浏览/关键词/断网错误/原地恢复4条Maestro均退出0。5SKIP不是通过，原售后在线写操作未重跑。
- 审计关注需控制端分配：全城市宽范围推荐可能使既有 `agent_events.payload` 超长，失败被现有审计非阻塞策略记录，但读取可完成。本任务没有改审计容量/截断协议/数据库列；不以此次火锅小结果通过表示容量问题已修。已经向控制端明确通报，不静默扩任务。
- 数据限制：真实目录目前仅demo；不少原图片为null；有图门店沿用既有seed-food卡通文字位图，不是餐厅实拍。无服务端头像/套餐逐菜明细/评分字段的地方不补事实。没有为了设计样例重建或增加夹具。
- 本轮覆盖实际坐标距离排序API和Android权限拒绝；没有实测Android允许定位后的GPS单次更新、运行中撤销权限、系统杀进程后恢复。网络失败实测首页重试，其他页面失败边界通过Repository/导航回归，不冒称每页都跑过断网Maestro。
- 最后核对两仓Task4涉及路径 `git diff --check` 均退出0，仅既有CRLF提示；再次解析JUnit为Spring52套件213、Android28套件147，均0失败/错误/跳过。无运行中的Gradle/pytest/Maestro开发测试会话。
- 服务留给独立验收：8080 PID53264入口com.fandazi.FandaziApplication；8091 PID28744入口service.app:create_app，健康status=ok/schemaVersion=1.1/sourceRoot=D:/project/-Demo-master。最近Spring恢复先导入共享环境再设TASK2_FIXTURES_ENABLED=false/TASK8_FAILURES_ENABLED=false；不输出密钥。设备emulator-5554保留本次最新APK。

冻结与释放：2026-10-02T03:28:40+08:00最终健康核对Spring城市GET=200、Agent健康ok；两仓HEAD仍为开始值，`git diff --cached --name-only`均为空。已运行 `C:/fdz-build/android-app/gradlew.bat --stop`（退出0，停止1个空闲daemon）及 `adb -s emulator-5554 shell am force-stop com.fandazi.app`（退出0），停止本人测试/应用占用，保留最新安装APK和健康服务供独立验收。41个源码指纹无不一致；逐行源码/文档manifest43项，生成型证据manifest78项。此后不再操作设备、Gradle、产品源码或服务，控制端可交接独立验收；文档结论不代替独立Agent实跑。
