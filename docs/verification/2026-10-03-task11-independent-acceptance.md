# Task11 唯一独立验收报告

## 独立结论

**Task11 当前授权范围独立 PASS，阻塞问题 0。需求符合性 PASS、代码质量 PASS、实际运行 PASS、拟提交源码闭合 PASS。**

验收者亲自导出最终索引、构建、运行测试、切换隔离服务、备份恢复、安装独立 APK、执行 Maestro 并查看截图；未把主 Agent 开发自测当作独立通过证据。本轮没有修复产品、修改 Figma、启动其他 Agent、提交或勾选计划。结论只覆盖旧运行路径退役与当前保留的新路径，**V14 是停用元数据登记，不表示物理 DROP 完成**；Task12、ASR、核销及全业务最终验收不在本次通过声明内。

## 前置、版本与文件范围

已读两仓 AGENTS.md、实施计划 Task11/3.4、设计规格迁移和保留边界、Task11 开发记录及 Task10 Harvey R2 报告。前置 Task10 已独立通过；既有 Task8 库存 71→70 与测试订单接受例外保留，本轮没有回滚或新增购买。

| 项目 | 本轮冻结版本与来源 |
|---|---|
| Phone 工作区 | `C:/fdz-build`，是手机 worktree 的 junction，不是另一份源码 |
| Phone HEAD | `4dda11a0e830cd37233ce2b49a7323f54e26cc0e` |
| Agent 工作区 / HEAD | `D:/project/-Demo-master` / `c76645ab0e2a4ffe39f09134909fb72e3df66f9c` |
| Phone 最终索引树 | `ff212b87a5c37ff916c6d46bc8e5aed7eff68fcc` |
| Agent 最终索引树 | `34d3a3ef7249fea02d646da0e71e0b9e98b2da04`，增量仅 README |
| 独立私有证据根 E | `C:/fdz-build/.runtime/task11-independent-r1` |
| 隔离源 | `E/phone`、`E/-Demo-master`，均从本轮最终索引 ZIP 解压 |
| Phone ZIP SHA-256 | `2b14c7c8431aa5497dc8c67a433e259c1a14d447351979ba775e406385e4fa38` |
| Agent ZIP SHA-256 | `f5932203b056f01444fb05d3e593f60d285b365d22a2760e7d4bb610a80c4208` |
| 独立 APK / 实装 base.apk SHA-256 | `7ca1b36ed0ae540975dbe82a033e0368282c62449edcbeeb518b4836d4f73c9b` |

Phone 本任务拟提交 26 文件，涉及入口/弃用说明、默认组件与安全配置、网关、V14、Android 启动/Profile/导航与兼容 ID、测试、正式启动器、备份脚本及 Maestro。新团购目录、交易、记忆、认证、契约、构建和种子测试资源为 HEAD 已有必要依赖。工作区原有旧外卖注释、旧 Agent、认证/图片辅助、延期 QR、测试及私有证据均保留，未纳入或覆盖。

最终树相对 `0d07cb15632b0e7bf7335c1912307c0bcf4b4585` 仅增加开发文档和备份脚本 `--compare-to` 核对，不改变产品源码。验收采用最终树重新构造和编译，没有复用主 Agent 的构建产物。

`E/check_snapshot.py` 通过批量 Git blob、ZIP 和解压文件核对：Phone 1699 文件、Agent 271 文件无缺失或内容差异；Windows Git archive 换行转换分别涉及 1282/227 文件，规范化 CRLF/LF 后与 blob 一致，解压内容与 ZIP 字节完全一致。`snapshot-summary.json` 保留完整核对及测试套件清单。活动导航无延期 QR，旧 ID 仅资源声明，不注册为目标。收尾两仓索引树与上表仍一致。

## 需求符合性与代码质量

| 要求 | 独立证据及结论 |
|---|---|
| 默认退出旧服务/控制器 | `NoTakeawayRuntimeDependencyTest` 2 项通过；补充真实 Spring 容器探针确认 AddressService、CartService、OrderService、PaymentService、DeliveryService、AutomationPolicyService、AgentToolService、InternalToolController、MerchantController 全部无 Bean。 |
| 保留审计实体/Repository 与菜单 | 独立容器确认 AddressRepository、CartDraftRepository、OrderRepository、DishRepository、MerchantRepository 及 StoreService/V1 桥存在。真实接口读取 30 门店、1 城市；实际选到 9 菜品、1 套餐，详情可读。没有静态响应替代。 |
| 网关不依赖旧地址/购物车 | 最终索引引用核对无 CartService/AddressService 注入；独立 H2 容器使用已编译网关，分别输入 cartDraftId/addressId 旧摘要均 FAILED，错误包含停用，待确认摘要为空。远程返回替身仅用于恶意输出边界；真实新路径另有 HTTP 实测。 |
| 旧 API denyAll | 游客、真实用户 JWT、内部 JWT/旧静态令牌访问地址/cart/carts/orders/automation/旧 internal/tools 共 24 检查均 HTTP401 拒绝；MockMvc 认证边界返回403。旧商家路径真实401，补核 Bean 未注册。错误分派安全链会覆盖部分原403/404，未发现旧能力可用。 |
| 新 Agent 默认8091、seed默认关闭 | 最终配置默认8091、seed=false；正式 Start-Services→Start-Task8 入口只启动新 service.app:create_app，普通 seed/fixture/failure=false。隔离真实启动来源为本轮 ZIP 新服务。停止8091后目录继续200、AI明确FAILED、8090没有监听；恢复后新服务可用。 |
| Android 旧运行入口退役 | 最终 Application 不初始化旧仓库/购物车/自动下单，MainActivity 不创建旧购物车 ViewModel，Profile 隐藏行无监听；历史 accessor 明确 throw。真实 NavController 和资源扫描测试通过，最新 APK 无地址/自动下单/购物车入口，记忆与返回仍可操作。 |
| 现行售后和公开事件继续受保护 | CouponComplaintToolTest 4 项通过，使用 V1 真实桥；InternalV1ToolIntegrationTest 9、AgentGatewayV1ContractTest 4、Task1NestedPublicEventAcceptanceTest 5 项均通过。真实新查询输出未含准备凭证、密码或内部令牌字段。 |
| 迁移不改历史事实 | V14 仅 CREATE 新表、INSERT 5 条 active=false 元数据；无 DROP、无 UPDATE 旧事实。实际 Flyway V1–13 原校验和及成功状态一致；V12/V13 是 Java/JDBC，checksum 为 null，不伪造 SQL checksum。V14 为943078552。 |
| 精确源及共享依赖 | 最终 ZIP 独立 Maven、Android、Agent 构建/测试均可运行，没有取工作区 QR 或遗漏辅助源码补齐。默认入口没有旧 Agent；当前交付树不存在活动 CI/Compose 部署文件，Task12 集成发布仍待实现。历史测试/旧源码/旧手工脚本留作历史或显式开发入口，不视为本期已部署能力。 |

本任务新增说明、迁移、安全分支、旧摘要拒绝、兼容 accessor 和测试具有中文注释；运行边界明确，保留 JPA 审计没有把旧交易组件重新注册。配置与已执行迁移未混入延期核销或 ASR。没有发现本任务产品阻塞缺陷。

既有根 `start-fandazi-services.ps1` 会显式启用 seed，正式 README 指向的新 `scripts/Start-Services.ps1` 普通入口关闭 seed；前者本轮未执行，不把所有历史手工脚本说成普通启动器。历史 Agent README 顶部明确弃用，后文旧示例只作历史参考。

## 亲跑命令与实际运行

工具：JDK `C:/Users/caid0/.jdks/ms-17.0.17`；Maven `D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd`；Python `C:/Users/caid0/anaconda3/envs/pinnT/python.exe`；SDK `C:/Users/caid0/AppData/Local/Android/Sdk`；Maestro `C:/Users/caid0/.local/maestro/bin/maestro.bat`。以下 python/mvn/adb/maestro 对应这些路径；模型/数据库配置从原私有环境读取，未复制密钥到交付树。

| 本轮命令或操作 | 实际结果 / 私有证据 |
|---|---|
| 两仓 `git archive --format=zip <最终树>`，分别 Expand-Archive 到 E | 成功；树、ZIP、解压文件核对通过。`E/check_snapshot.py`、`snapshot-summary.json`。 |
| 隔离 `phone/backend`：`mvn clean verify` | exit0，156 tests、0 failures/errors/skipped，构建可执行 JAR，耗时1分22秒；`maven.log`、Surefire XML。 |
| 隔离 `phone/android-app`：`gradlew.bat testDebugUnitTest assembleDebug --no-daemon --rerun-tasks` | exit0，45任务全部实际执行，179 tests、0 failures/errors/skipped；1分30秒，APK生成；`android.log`、JUnit XML。 |
| 隔离 `-Demo-master`：加载原私有 dotenv、设置 MOBILE_PROJECT_ROOT=E/phone 后 `pytest tests -q -rs -p no:cacheprovider --basetemp=E/pytest-final-temp` | 最终 exit0，413 passed、20 skipped，29.83秒；`agent-pytest-final.log`。跳过不算通过或在线测量。 |
| `pytest tests/test_cross_repo_contracts.py::test_local_copies_match_mobile_authority_when_configured -q` | 补核真实两份最终源码契约，1 passed；最终全量也包含该项，不重复累计。`cross-repo.log`。 |
| `python scripts/verify_retirement_backup.py --output .runtime/task11-independent-backup --compare-to .runtime/task11-backup-main` | exit0，两库真实备份/随机库恢复/逐表摘要一致，previousFactsUnchanged=true；见下一节。 |
| `E/start-isolated.ps1 -Service Spring`、`-Service Agent` | 两服务亲自从最终 ZIP 源码启动并通过实际健康；`isolated-Spring.*.log`、`isolated-Agent.*.log`。 |
| `python E/probe_api.py` 与 `python E/probe_merchant.py` | 初次39检查38通过，唯一失败为脚本强制旧商家404；真实返回401仍拒绝，专项2模式补核通过；其他38检查无需重跑。`api-first-summary.json`、`api-run.log`、`merchant-recheck.json`。 |
| `mvn dependency:build-classpath`；javac 编译 `E/Task11RuntimeProbe.java`；java 运行 | exit0，新 H2/随机端口真实容器核对9退役Bean、7保留依赖、2旧摘要拒绝。`runtime-bean-probe.log`，未编辑产品或连接真实业务库。 |
| adb卸载 com.fandazi.app；核对旧包与 com.dirror.music；安装本轮 APK | uninstall/install均Success，旧包移除后才安装，music列表为空，Bluetooth=0。收尾pull实装base.apk核对与独立APK哈希一致。 |
| `E/run-maestro.ps1 -Flow E/phone/maestro/group-buying-11-retirement.yaml -Label retirement` | exit0；正式登录、我的→真实记忆→返回设置→首页、旧入口缺席全部通过。`maestro-retirement.log`及截图。 |
| `E/run-maestro.ps1 -Flow E/visual.yaml -Label visual` | exit0；已登录我的、实际记忆、返回和首页截图，逐屏对照Figma。`maestro-visual.log`。 |
| `E/run-maestro.ps1 -Flow E/phone/maestro/group-buying-10-memory-guest.yaml -Label guest` | exit0；游客仅账户引导/设置，记忆/订单/帮助入口不可见。`maestro-guest.log`及截图。 |
| 停止本轮隔离8091进程，`python E/probe_degraded.py` | exit0；8090/8091真无监听、普通目录200、AI200但业务状态FAILED。`degraded-summary.json`。 |
| `Start-Services.ps1 -Service Agent`、`-Service Spring`；`python E/check_restored.py` | exit0；普通服务恢复，新Agent来源D:/project/-Demo-master、健康200、记忆200、问候COMPLETED，真实新JWT3600秒。`restore-*.log`、`restored-health.json`。 |
| `python E/check_facts.py` | 最终exit0；只出现会话/工具/意图审计增量，历史事实及Flyway未改变。`final-facts-summary.json`。 |

Maestro运行器只从既有私有合成账户读取凭据，底层命令为 `maestro --device emulator-5554 test --debug-output E/maestro-<label> --test-output-dir E/maestro-<label> <flow>`，没有在公共报告列口令或JWT。设备流程顺序独占，截图、失败和脚本保留私有，不提交含合成身份截图。

## 数据恢复与保护

独立备份根为 `C:/fdz-build/.runtime/task11-independent-backup`，首次创建未覆盖主备份。脚本只从Docker私有环境读取root凭据；备份不带数据库重定向/USE，恢复目标仅自动生成，不恢复源库、不删除旧库/表。两个随机恢复库留存：

| 来源 / 随机恢复库 | 独立实际结果 |
|---|---|
| fandazi_groupbuy_dev / `t11_restore_d169ce0e52f84c56b8cc6dfd5cdf2da9` | 35表、7022行；备份前、恢复后、源库复读逐表摘要和Flyway相同。原主备份34表7016行到V14后仅增加元数据5行及迁移历史1行。 |
| dianping_intent / `t11_restore_26ee8be799dc4e96a9fc991a35b9cf51` | 11表、487行；三份逐表摘要相同，与主备份原事实一致。 |

`summary.json` 两库 equal=true、previousFactsUnchanged=true。业务Flyway历史V1–13保持原checksum与success，V14新增成功；新元数据表正好5行。备份行数仅代表该时点，不当作业务用户数或功能指标。

本轮真实查询允许新Agent审计和运行状态持久化；收尾重新逐表核对，业务变化仅 `agent_events/agent_sessions/agent_tool_executions`，Agent变化仅 `agent_service_runs/intent_decision_audit`。旧订单、库存、支付、地址、购物车、配送及其他原事实表摘要均未变。未付费选择既有套餐、未创建交易、未运行禁止的 `test_live_write_boundaries_current.py`。H2补充探针的合成用户完全隔离于真实库。

## 67 项退役测试

依据最终 pom 的排除规则，与 Task10 隔离 Surefire 报告逐类核对。以下16类合计67项退出默认运行，**均不计入本期PASS**；方法名明细在 `E/snapshot-summary.json` 的 retired.classes.cases。测试源码没有删除。

| 退役类 | 原测试数 |
|---|---:|
| AddressConcurrencyTest | 1 |
| AgentAuthorizationConcurrencyTest | 2 |
| AgentToolAuthorizationTest | 15 |
| AgentToolFieldBoundaryTest | 3 |
| AutomationControllerTest | 2 |
| AutomationDefaultAddressTest | 2 |
| AutomationPolicyTest | 2 |
| SpringBootToolHttpContractTest | 1 |
| Task1PublicToolAuditAcceptanceTest | 1 |
| CartServiceTest | 9 |
| MerchantCatalogControllerTest | 5 |
| CrossLayerOrderingFlowTest | 1 |
| DomainControllerTest | 10 |
| OrderIdempotencyConcurrencyTest | 3 |
| OrderInventoryRollbackTest | 1 |
| OrderServiceTest | 9 |

这些依赖旧地址/购物车/外卖交易、旧自动授权或旧Python静态令牌契约；Task1旧公开审计测试也走旧工具，安全需求由现行V1/嵌套公开事件检查继续验证。4项团购 CouponComplaintToolTest 已迁真实V1桥继续运行。新增运行边界2项使默认后端从原221项变为156项，不把数量降低描述为等价全量覆盖。

## Figma 与实际页面

先读取 figma-use 技能，只读获取 `FxsWKGs10z7BL1MhFAOqFN` 节点截图，未写设计。实际查看下列图稿与本轮1080×2400模拟器截图，按结构、内容来源、重叠、可点击和返回核对，允许既定微小视觉差异：

| 图稿节点 | 本轮实际截图与结果 |
|---|---|
| UI-01 首页 `63:2` | `maestro-retirement/2026-10-03_053937/group-buying-11-retirement/takeScreenshot/retirement-home.png`；另 `maestro-visual/2026-10-03_054325/visual/takeScreenshot/retirement-home-visual.png`。搜索/分类/排序、真实门店套餐列表、四Tab完整，无购物车入口、无重叠。 |
| Task10已登录我的 `192:2` | 同visual目录 `retirement-profile.png`。合成实际用户名换行无溢出，订单/AI记忆/退款/帮助/设置保留，地址和自动下单消失。记忆入口及返回实际点击成功。 |
| Task10记忆 `191:2` | 同visual目录 `retirement-memory.png`。真实API返回川菜、授权状态和有效期，编辑/删除/清空可见，返回到我的正确；没有用图稿第二条示例补造回答偏好。 |
| 游客UI-16 `34:2` | `maestro-guest/2026-10-03_054800/group-buying-10-memory-guest/takeScreenshot/memory-guest-profile.png`。游客账户引导、设置和四Tab存在，受保护入口隐藏。旧UI spec对UI-16仍有待确认记录，此处只核对既有视觉结构，不冒称本轮取得新人工设计审批。 |

首页当前8768图片服务未监听，图像呈明确暂无图片/点击刷新占位，不是假照片；Task10既有合成门店无图、评分未知、未定位也按真实字段展示。本任务未新开发图像能力，图片服务和本期最终图像验收归Task12，不能说已完成。我的帮助/设置图标与排布、标题副文案等既有Task10视觉差异仍保留，本次没有新增错位、不可点击或文字重叠，不作为Task11新增退役回归。

## 首次失败、补核与未运行

所有首次记录保留；以下均为独立验收环境/脚本问题，**没有产品源码修复或产品FAIL→PASS改写**：

- Agent首次解压目录名为 `E/agent`，健康测试强制sourceRoot结尾为-Demo-master；实际411 passed、1 failed、21 skipped。重新从同ZIP解压到 `E/-Demo-master` 后412 passed、21 skipped；配置最终手机契约目录再全量复跑413 passed、20 skipped。日志分别为agent-pytest.log、agent-pytest-rerun.log、agent-pytest-final.log。
- 第一次源核对直接把Windows archive的CRLF当blob内容变化，保存snapshot-first-summary.json。补以批量cat-file逐字节读取并仅规范化换行后，无真实内容差异；产品/ZIP未修改。
- 首次接口探针强制旧商家404失败，保存api-first-summary.json和api-run.log；实际401为拒绝，ERROR安全分派会覆盖404。补跑guest/authenticated两种模式仍拒绝，并以真实容器确认MerchantController没有Bean；其他38项通过证据保持有效。
- 首次收尾事实探针将Agent审计表名误写为intent_audit_logs/agent_runs，保存final-facts-first-summary.json。按已交付源码实际表名intent_decision_audit/agent_service_runs修正私有白名单后通过，没有放宽任何业务事实表比较。
- 首次Figma只读调用缺description，参数校验失败；补齐参数后成功，未发生设计修改。

20项pytest条件跳过分布：新服务端到端4、独立在线冒烟1、Task2显式开发夹具7、Task2页面在线2、Task4在线3、Task8在线3。它们未运行、不能算通过；本轮使用自己的只读接口/工具/降级探针覆盖Task11所需真实链路，未运行会支付既有套餐的历史端到端脚本，也未开启开发夹具。

本轮未运行全业务购买/支付/退款/评价综合Maestro、自然401到期回归、最终lint/集成镜像/评测及图像恢复；前置Task10 R2证据保留，Task12最终门槛继续存在，已知lint遗留不宣称通过。本轮不是新的意图/性能实验，不给出准确率或P95结论。物理DROP、生产部署、ASR和核销未实施。未受影响的Task8历史事故不重做、不回滚。

## 环境释放与主 Agent 后续

独立构建、pytest、数据库备份恢复、探针、Maestro均已结束。普通Spring8080、新Agent8091已通过正式Start-Services恢复：default配置、JWT60分钟、seed/fixture/failure=false；实际新JWT3600秒，Agent/记忆200、问候COMPLETED。设备emulator-5554安装本轮独立APK且实装哈希一致、Bluetooth=0、com.dirror.music不存在。8090和8768无监听，未将8768未运行写为健康。

**2026-10-03 05:53:31 +08:00完成最终核对，现明确释放模拟器、8080/8091与相关服务独占权。** 两个独立恢复库与全部私有证据留存，无恢复写回源库。主 Agent 可对上表已验收精确树办理提交与计划回写，再按授权推进Task12；报告写入本身未加入索引，验收者不提交、不勾选。原dirty、历史失败及延期QR均保留。
