# Task7 主 Agent 源码闭合补正

## 状态与依据

主 Agent 开发自测，待原 Lorentz 独立定向复验。原 R0 FAIL、R1 功能/质量/运行 PASS 与全部证据保留；新用户要求将拟提交源隔离构建作为当前任务完成门槛，不再推迟到 Task8。

手机 BASE c386a05，Agent BASE c339237。只从精确索引导出到忽略目录 `.runtime/task7-delivery-r2/phone` 和 `agent`，不复制原运行数据库、凭据、整个工作区或未来售后模块。Android SDK 的本机 local.properties 仅作为受控构建环境加入，不提交。

## 首次失败及根因

1. 隔离 `assembleDebug` 首次失败：已验收支付页引用 `after_sale_card.xml`，聊天页引用 `agent_avatar_bot.xml`，均遗漏于交付。补两项原共享显示资源，不代表售后写功能完成。
2. 补资源后 Kotlin 编译失败：已验收多个页面缺原 `core/time/BusinessDisplay.kt`；券页直接引用未提交 `afterSaleRepository` 和未来退款/评价/核销资源 ID。
3. 消除上述耦合后 APK 构建通过，但测试编译失败：HEAD 的 AgentViewModelTest 仍引用原 `AgentProgressPresentation.kt`。补该必要历史纯格式化依赖及中文注释，不重新启用旧 Agent。

原失败输出由主 Agent 亲自读取；第三次及后续完整日志保存忽略目录 development-build-r2.log、development-build-r2b.log，不能将最终通过改称首次全绿。

## 最小修正

- 只读 VoucherRepository 新增 `getRefundLink`，只 GET 本人券已有申请，校验同券公开身份，404/401保留；券详情不再编译依赖未来售后提交仓库。
- 新 `RegisteredBusinessRoute` 边界只打开当前导航图实际注册的固定 UI 路由；未来页面未注册明确提示，不通过添加 QR 或售后整套实现来凑编译。现有工作区已注册的页面继续原入口及真实公开参数。
- 原延期 requestToken 方法继续隐藏、无调用；可选路由守卫不签发令牌、不启用核销。
- 新增只读身份/错误/GET回归及导航注册回归；没有改已确认页面布局，UI不采用TDD。
- 原未来售后、评价、投诉、QR源及接线继续在工作区保留；Application/nav_graph/Agent engine 和 reads 测试仅索引投影本任务部分。

## 开发自测结果

隔离命令：批准 JDK 下 `gradlew.bat testDebugUnitTest --tests com.fandazi.app.feature.voucher.VoucherRepositoryTest --tests com.fandazi.app.core.navigation.RegisteredBusinessRouteTest assembleDebug --console=plain`，10秒，退出0，五项测试通过。原工作区同命令8秒退出0，同五项通过，不等于独立通过。

新工作区 APK 2F78FDE966979334F11A7E9F3F7F9319DD58B73D1B6BD0E472F18C74F2E09836；先卸载两旧包再装，拉取设备APK同SHA。`Run-Task7Query.ps1 -Label r2-main-query` 完整只读链退出0，结果在 task7-evidence/maestro-r2-main-query/result.json，含不同券、分段、真实金额、退款资格导航后返回及待付资格；仅开发自测，不计独立通过。

冻结清单 task7-r2-evidence/source-freeze.json 为895项；相对R0九项改变：六项产品/测试补正、两仓AGENTS及计划的新执行规则；另增加两项导航源/测试。所有原R0/R1清单保留。两仓精确拟提交路径和 index tree 在该目录，独立验收须检查其与快照一致。

## 未完成

原验收者亲跑拟提交源隔离构建与适用回归，亲复验新增读取、导航和最新APK受影响券链后才能关闭本补正。Task7未提交、未标记最终完成、未启动Task8；ASR/核销/售后写入不在当前补正范围。

## R3：独立 B2 后主 Agent 最小补正

Lorentz R2发现只读后端依赖遗漏并FAIL释放；其隔离Android69项通过记录保留，不重复不受影响的Android编译或功能代码。主Agent核对三份后端工作区差异均仅新增 GET /by-voucher、本人券校验与用户/券联合仓库查询，将三个必要只读片段和原退款进度恢复回归纳入精确清单；没有新增 prepare/submit 写行为。

亲跑快照 `mvn -f backend/pom.xml -Dtest=RefundEligibilityTest test` 首次编译失败，暴露原已提交订单Controller静态依赖未提交开发故障类。主Agent将可选钩子改为具名JDK LongConsumer，由ObjectProvider按需读取；正常交付不依赖开发类。工作区原开发故障组件仅增加接口适配，仍受development和显式开关限制，留Task8未暂存，不借该未来组件凑编译。

同源快照再次亲跑上述命令，15.203秒，8项失败/错误/跳过均0，BUILD SUCCESS。日志 read-api-development.log 保留首次失败，read-api-development-r3.log 保存最终结果。读方法先核本人券，未申请空、已有申请持久化同记录、他人拒绝均在该回归覆盖；H2运行只在快照，不消费真实业务事实。

R3两仓index、拟提交路径与源清单另存task7-r3-evidence，R2元数据/独立证据不覆盖。唯一新增实际产品行为是可选故障钩子的解耦，需原验收者亲复核与剩余Maven/Agent/真实读取/最新APK受影响链；未改Android源和APK，所以其R2已亲跑69项有版本一致的复用依据，但尚不能宣称Task7已完成。

## 独立收尾结果

恢复后原Lorentz给出Task7 R3需求、质量、运行和拟提交源码闭合整体PASS，B1/B2关闭并释放环境。隔离后端32、Agent93、同Android版本69项均有独立证据；新增4编译探针、16真实HTTP权限读取、两种APK110条Maestro命令通过。原首次失败与用户暂停记录保留，详见2026-10-02-task7-independent-acceptance.md。

工作区售后已注册入口会准备未消费凭证但未提交退款；拟提交未注册入口给出明确提示。本人真实已有退款申请200及进度页UI留Task8，H2和MVC结果不冒充该线上场景。当前源码已具备精确提交条件，提交编号及计划勾选由主Agent在实际提交后记录。
