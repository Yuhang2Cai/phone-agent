# Task8 独立综合验收

## 结论

**FAIL，阻塞编号 T8-IND-01：本次验收夹具隔离失误导致旧套餐库存变化，事实保护未通过。**

验收实际跨越 2026-10-02 至 2026-10-03（Asia/Shanghai），路径日期按交接约定保留。本人是唯一独立验收者，亲跑冻结索引快照构建、真实接口、最新 APK Maestro 及 Figma 逐屏检查；没有用主 Agent 自测代替，没有另派 Agent、修改产品/计划/索引、提交或勾选任务。

已运行的售后功能没有发现已确认的产品阻塞；这不等于综合验收通过。安全失败由本验收执行引入，不能归责为产品退款缺陷，不能用通过的功能数量抵消。主 Agent 在处理阻塞并由同一验收者定向复验前，不得宣称 Task8 完成。

| 维度 | 独立结论 |
|---|---|
| 需求符合性 | 已测 Task8 功能符合；全过程数据保护不符合，综合 FAIL |
| 代码质量 | 已审售后授权、动作身份、持久化、投影及入口未发现产品阻塞；验收购买夹具选择存在隔离缺陷 |
| 实际运行 | 构建、单测、接口及所列 UI 功能通过；最终事实保护 FAIL |
| 交付源码闭合 | 两索引快照可构建、必要入口/依赖存在、冻结摘要不变；未提交，尚不能闭合 Task8 |

## 阻塞与责任

T8-IND-01（P1，未关闭）：`test_live_write_boundaries_current.py:49` 的 `purchase_paid_voucher` 读取原目录第一门店/第一套餐，通过真实报价、购买、模拟支付取得退款测试券。虽然账号和订单是新建的，套餐不是独占新夹具，创建订单正常扣减了该旧套餐库存。此脚本由本人从历史独立测试复制，并仅补了当前必填 `quoteId`；执行前没有充分检查共享库存副作用，是本次验收失误。

- 收尾 `guard_facts.py independent-final-end` 首次失败：`deal_packages 原事实变化`；只读再次采集同样失败，日志 `.runtime/task8-independent-final/facts-end-failure.log`。
- 完整六表只读比对：原 126 条套餐中恰好 1 条、仅 `inventory` 从 **71 → 70**；对应 1 笔本次边界测试命名空间新订单。原 30 门店、98 订单、136 券、29 退款、306 评价均未改动。不是基线日期变化或序列化差异。
- [脱敏差异](2026-10-02-task8-independent-acceptance-evidence/fact-difference-summary.json)；完整关联订单/账号/行仅在 `.runtime/task8-independent-final/fact-difference.json`，不可提交。
- 正常扣库存代码：冻结快照 `backend/src/main/java/com/fandazi/feature/grouporder/GroupBuyOrderService.java:158`。本报告不建议为迎合验收修改该正常业务逻辑。
- 发现后停止新业务写入；未回滚库存、删除订单/退款、改旧日期、覆盖基线或清库。当前数据库保留这个异常增量供审计，**不能描述为所有原事实已恢复**。
- 后续需主 Agent 与用户明确处理本次新增订单及共享库存的审计处置，并修正验收准备逻辑：购买必须引用独占新克隆套餐，不能读目录首条作为可写资源。不能直接重跑当前边界脚本。处置后由本验收者重查差异、运行隔离边界与必要回归，不重跑无影响的构建/全部 UI；若涉及源码变化，再按实际影响重建和复验。不得静默接受新基线来掩盖首次失败。

## 冻结版本

| 项目 | HEAD | 独立导出索引树 |
|---|---|---|
| Phone | f2f08035ed23676c88269d083b3536e200741ac0 | 1cc8fff9b0077672fd87d26a460a0fda5b7b7996 |
| Agent | 9fdc5e16146423120bab18a722090643fa0d3203 | 0d29e30b066dd0b33d8cf244fa75d71ee3d5995a |

独立 `git archive --format=zip` 导出至 `.runtime/task8-independent-final/{phone,-Demo-master}`，未使用主 Agent 的 delivery 产物。APK：`.runtime/task8-independent-final/phone/android-app/app/build/outputs/apk/debug/app-debug.apk`，SHA256：`5417EF91BC9D2544DC49B24EF35186E83448A98F56555840B4D121AD8756511C`。全部 UI 均安装此 APK；新用户流程卸载两个旧包，同流程恢复段不重装。

末次校验 Phone 114、Agent 6 个冻结工作文件摘要、两 HEAD/索引树完全不变，旧私有 JSON **775/775 哈希不变**。这与数据库库存 FAIL 是两个不同层面的检查，不混淆。证据：[导出记录](2026-10-02-task8-independent-acceptance-evidence/snapshot.json)、[计数/哈希/环境/截图路径索引](2026-10-02-task8-independent-acceptance-evidence/final-evidence.json)。

索引导航只含本任务售后五页，无核销 QR/结果页；Application 接真实售后仓库，Profile 单一帮助入口在最新 APK 实际可达。构建解析布局、颜色、图标及引用；未把工作区未来 Profile/nav 改动整体纳入。Task4–7 已提交独立结论只作既有范围边界，本次受影响共享路径由全量测试和实际售后流程重新覆盖，不重做此前业务验收。

## 构建与测试计数

以下构建/测试均发生在 **10 月 2 日**，原始日志和 JUnit XML 在 `.runtime/task8-independent-final/`；表中“通过”是具体测试断言结果，不代表综合安全通过。

| 实际运行 | 结果 | 原始证据 |
|---|---|---|
| Android 快照 `testDebugUnitTest assembleDebug --no-daemon` | 177 通过，0 失败/跳过，APK 构建成功 | android-build.log、快照 test-results 33 XML |
| Backend 快照 Maven `test` | 220 通过，0 失败/跳过 | backend-full.log、surefire 53 XML |
| Agent 快照 `pytest tests` | 380 通过、20 显式跳过 | agent-offline.xml/log |
| 实际 MySQL 状态机测试 | 10 通过 | agent-mysql.xml/log |
| 首轮真实退款/投诉边界 + 八券接口 | 11 通过、1 失败（旧购买辅助缺 quoteId） | live-boundaries-coupons.xml/log |
| 当前契约边界副本 | 2 通过；但引入 T8-IND-01，不可作为安全闭合 | live-boundaries-r1.xml/log |
| 双服务退款重入/复合城市恢复 | 2 通过（503 单独运行） | support-live.xml/log |
| 本人一次 503/他人/游客隔离 | 1 通过 | support-503.xml/log |
| 他人投诉/评价、游客、保留券未退款 | 1 通过 | actual-ownership.xml/log |

真实 HTTP 不重复计数为 **16 个通过测试**（10 券、2 边界、3 support、1 ownership）；首轮投诉与复跑重复的不累加。另有退款结果 **13 条检查**、UI 写入 **5 条检查**，见 `refund-api-current.json`、`ui-writes-current.json`，不混入 pytest 数字。

20 项离线跳过：旧 agent live 5、Task2 live 9、Task4 live 3、Task8 live 3。Task8 的三项后来分批真实运行通过；旧 Task2/4 live 没有在本次重跑，不把它们说成通过。跨仓契约配置 `MOBILE_PROJECT_ROOT` 为 Phone 快照，实际执行而非因缺环境跳过。

实际边界包括：真实金额与资格、准备零写入、空白/超长/错会话不消耗有效授权、合法上限只写一次、按券进度重入、他人/游客拒绝、评价唯一且资格刷新、投诉准确 OPEN/时间、FAQ 类别与关键词交集、复合缺城市先补槽及独立 FAQ 保留。UI 普通退款、聊天退款各一笔 REQUESTED，评价一次，普通/聊天投诉各一次；第三张保留券经 prepare/cancel 后仍未退款。

### 证据层级

- **真实 UI + 双服务**：普通售后、Agent 售后、FAQ/复合/八券；历史选券/确认/取消动作不可重新执行；等待确认时重启 Agent（12632 → 47544），同安装同会话恢复并取消，未写入第三张退款。
- **真实 MySQL、假模型/工具**：10 项检查持久化状态、非法输入、归属、三阶段取消、过期、不确定写入不重放、明确拒绝、DAG 独立支路。数据库是真实的，不把工具模拟称为在线业务写入故障。
- **离线与源码**：三类售后卡的旧规则版本/身份绑定/篡改/过期、SSE 重放及迟到终态、恢复；资格变化与 uncertain-write 防重放含单元/实际 MySQL 假工具证据。没有在真实生产式写入中人为丢弃成功响应，也没有为截图改旧券资格。此限制单列，不伪称在线 UI 全覆盖。

## Maestro 与逐屏检查

共 **20 个 Maestro 阶段记录：19 阶段成功，1 次自编历史选择器失败**；13 条主要用户流程全部完成，另含登录和连续恢复/补证阶段。复合流程 1 个可选截图分支被跳过，已用同会话尾段实际滚动真实套餐补证，不将 SKIPPED 算 PASS。详见 `final-evidence.json` 中各 commands.json 状态计数。

| 日期（上海） | 流程/唯一运行名后缀 | 实际结果 |
|---|---|---|
| 10-02 | refund-r0、reentry-r0 | 申请/二次确认/REQUESTED/按券重进，金额 ¥33.34 |
| 10-02 | review-r0 | 1→5 星、正文、发布成功、资格刷新 |
| 10-02 | support-r0 | Profile 帮助入口、FAQ 展开/分类搜索空态/恢复、本人订单投诉 |
| 10-02 | agent-refund-r0 | 补槽/选券/二次确认/取消、再确认真实退款 |
| 10-02 | agent-support-r0 | 无可退券、投诉准备/确认/OPEN |
| 10-02 | composite-r0 + composite-deals-tail | FAQ 实际答案与真实门店/套餐均可见，尾段补截图 |
| 10-02 | history-actions-r0 → history-recover-r1 | 首次选择器误匹配用户气泡；资源 ID 限定后，服务重启状态恢复且取消成功 |
| 10-02 | empty-r0 | 空订单及“去逛逛”回首页 |
| 10-02 | server-r0 | 本人真实 503 页、原地重试真实列表；随后正常重启 |
| 10-02 | network-r0 | 真实默认网络 none、错误页、恢复网络后同会话重试 |
| 10-02 → 10-03 | image-fail-r0 → image-recover-r0 | 原 URL 503 后恢复原字节200，保留5星和正文 |
| 10-03 | image-null-r0 | 真实 null 图刷新，保留退款备注，不提交 |
| 10-03 | coupon-20261003-r0 | 当日新批次八券逐项截图，UI 后再次接口/DB核验只读 |

正常 runner 的完整名称为 `independent-final-<后缀>`，证据根 `.runtime/task8-independent-final/phone/.runtime/task8/`；历史/复合尾段直接在本次 private 根。截图全部留 private，不复制任何可能含测试登录名的图片到公开目录。

已调用 Figma 完整 design_context（含截图），文件 `FxsWKGs10z7BL1MhFAOqFN`；不是只枚举节点。亲自打开实际截图逐屏对照：

| Figma 节点 | 实际截图 | 结论 |
|---|---|---|
| 38:2 退款 | refund-request、refund-confirmation、refund-null-image-refreshed | 商品/真实金额/原因/备注/底部操作完整，无重叠 |
| 38:18 进度 | refund-progress、退款重入截图 | 真实 REQUESTED，无假成功/到账；时间与回执对应 |
| 38:36 评价 | review-1-star、review-filled、review-submitted、原图恢复 | 星级和正文可操作，提交/图片状态不丢输入 |
| 38:46 FAQ | faq-expanded、分类搜索/恢复 | 实际后端答案、分类/搜索/投诉入口可达 |
| 38:67 投诉 | complaint-filled、complaint-confirmation、complaint-submitted | 真实类型/本人订单/二次确认/受理回执 |
| 39:2 空态 | orders-empty、orders-empty-return-home | 结构与返回入口正确 |
| 39:9 错误 | orders-503、orders-503-retry-real-list | 可点击原地重试；订单上下文选订单 Tab |
| 39:16 网络 | orders-network-off、orders-network-recovered | 与503区分，真实网络恢复可用 |
| 56:404 已确认聊天完整页及售后约束 | Agent退款/投诉/复合/八券/历史恢复截图 | 真实结果、明确确认状态、历史动作禁用；无假套餐或写入成功 |

允许的差异：原生单选/星形颜色、细微尺寸与间距；未发现结构缺失、内容重叠、必要控件不可点击。八券首日为 2026-10-03、末日为 2026-10-03 均可用；未来/禁用/过期/已使用/持久化过期逐项符合，金额均满 ¥50 减 ¥5。新批次创建与 UI 后核验分别输出状态/金额/日期/归属各8、负向2、持久化8不变。10月2日八券接口证据保留不变，没有延长旧券日期。

图片服务证据：[images-result.json](2026-10-02-task8-independent-acceptance-evidence/images-result.json)。3 次真实503（29字节）、随后3次200（41914字节），原图 SHA 不变、同一URL、故障标记已移除。图片是既有种子开发海报，非实拍；本次只验传输、占位、输入保留。

## 首次失败与复验

1. 旧边界辅助缺 `quoteId`：首轮 400，尚未进入退款；独立副本仅补当前报价身份后售后2/2通过。历史原脚本不改。该副本仍有 T8-IND-01，不能再直接运行。
2. 自编历史 UI 选择器仅匹配“确认退款”+enabled，误中了用户消息气泡：保留失败截图与 XML。改用已有 action 资源 ID 的连续尾段后，定位当前确认卡并在服务重启后取消成功；没有把失败归为产品动作错绑。
3. 收尾原事实 guard：旧套餐库存71→70，仍 FAIL。已保留首次和再次只读失败以及关联证据，未修复、未豁免。

## 可审计命令

以下为本次实际入口摘要，路径缩写 PRIVATE=`C:/fdz-build/.runtime/task8-independent-final`，EVIDENCE=`C:/fdz-build/docs/verification/2026-10-02-task8-independent-acceptance-evidence`。**不是建议整体重跑；含写入的旧边界辅助禁止原样再跑。**

```powershell
& "$EVIDENCE/Export-Snapshot.ps1"
# 在 PRIVATE/phone/android-app，JDK17、ANDROID_HOME/SDK_ROOT 已配置
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon
# 在 PRIVATE/phone；PYTHON_EXECUTABLE 为 pinnT/python.exe
& $maven -f backend/pom.xml test
# 在 PRIVATE/-Demo-master，设置 MOBILE_PROJECT_ROOT、独立basetemp和禁用在线开关
python -X utf8 -m pytest tests -q -p no:cacheprovider --tb=short --basetemp "$PRIVATE/pytest-offline" --junitxml="$PRIVATE/agent-offline.xml"
# 实际MySQL使用 dianping_intent；其他真实接口使用共享业务库环境，原始输出重定向private
python -m pytest C:/fdz-build/docs/verification/task8-service-evidence/test_agent_mysql_acceptance.py
python -m pytest C:/fdz-build/docs/verification/task8-service-evidence/test_live_write_boundaries.py C:/fdz-build/docs/verification/task8-service-evidence/test_live_coupon_projection.py
python -m pytest "$EVIDENCE/test_live_write_boundaries_current.py"
python -m pytest tests/test_task8_support_live.py -k 'owned_refund or live_composite'
python -m pytest tests/test_task8_support_live.py -k support_catalog_and_owned_one_shot_error
python "$EVIDENCE/verify_current_ui.py" refund
python "$EVIDENCE/verify_ui_writes_current.py"
python -m pytest "$EVIDENCE/test_actual_ownership.py"
& "$PRIVATE/phone/maestro/group-buying-05-run.ps1" -Scenario <场景> -Flow <表中流程> -RunName <唯一名称> -FixtureManifest ../../task8-resumed/fixtures-independent-final-r0.json -ApkPath <上述APK>
& "$PRIVATE/phone/maestro/group-buying-05-exceptions-run.ps1" -Mode server # 同一新夹具与APK
& "$PRIVATE/phone/maestro/group-buying-05-exceptions-run.ps1" -Mode network # 同一新夹具与APK
& "$EVIDENCE/Run-Images.ps1"
python C:/fdz-build/docs/verification/task8-resumed-evidence/prepare_coupon.py independent-final-20261003-r0
python C:/fdz-build/docs/verification/task8-resumed-evidence/prepare_coupon.py independent-final-20261003-r0 --verify-only
python C:/fdz-build/docs/verification/task8-resumed-evidence/guard_facts.py independent-final-end
python "$EVIDENCE/inspect_fact_difference.py"
& "$EVIDENCE/Collect-Evidence.ps1"
```

七场景清单 `fixtures-independent-final-r0.json`、八券清单 `coupons-independent-final-20261003-r0.json` 均新建独占；图片使用交接明确许可的 `fixtures-images.json` 且不提交业务写入。所有账号/密码/令牌/完整 SQL 关联和请求日志仅保留 `.runtime`。历史报告在启动时已逐字节备份为 `.runtime/task8-independent-final/task8-acceptance-original.md`。

## 环境释放与收尾

**2026-10-03 00:20（Asia/Shanghai）正式释放** emulator-5554、8080/8091/8768、构建与 Maestro 操作权给主 Agent；最终状态仍为 **FAIL / T8-IND-01 未关闭**。本轮测试及保密检查命令均结束，无本轮仍运行的 Maestro test、pytest、Gradle 构建；既有 Maestro MCP 服务未停止。

最终保密检查：两仓暂存文本108份、本次公开文本21份，0命中，见 `independent-secret-scan-r1.json`。初次扫描误将快照中公开资源审计的 `token`（颜色资源名称）当作秘密，保留 `independent-secret-scan.json` 和私有溯源；收紧候选为运行私有材料后通过，没有改任何产品内容来避开命中。108是本扫描实际文本计数，不用交接时105替代；00:20再次核对两索引树仍与冻结值完全一致。历史报告原字节前缀逐字节一致，仅追加当前入口。

- Spring 8080 PID **34804**：正常 default 模式，seed/Task2 fixtures/Task8 failures 关闭，JWT60分钟；真实目录健康，已登录本人访问故障注入路由为404。
- Agent 8091 PID **47544**：health ok，sourceRoot `D:/project/-Demo-master`，入口 `service.app:create_app`，buildVersion `9fdc5e1-task8-working-tree`。
- 图片 8768 PID **2464**：原进程、同URL200、原字节未变、blocked不存在。
- emulator-5554：wifi=1、mobile_data=1、airplane=disabled、http_proxy=null，实际默认网络恢复。当前为本次测试安装/合成登录状态，无秘密进入公开截图。
- 环境操作开关已恢复；T8-IND-01 的库存增量未擅自回滚，继续保留以供审计处置。

释放后的文档一致性项由主 Agent 统一回写：顶部当前状态表 Task7“待闭合”、Task8“进行8.5”是旧文字，详细章节已更新；当前 Task8 应准确记录本次 FAIL/待定向复验，不可改成完成。`task8-write-set.txt` 的 `source-freeze.json` 引用与实际 `freeze-preacceptance.json` 命名也需统一。本验收未改计划、冻结清单或产品索引。

Task9–12 仅计划完善，四类清单/源闭合/单一独立验收/精确提交约束保持未实施；ASR、核销延期。后续只处理本次阻塞及 Task8 闭合，不开展未来功能。未受影响的本报告通过证据可在版本、配置与环境一致性核对后复用，日期敏感券若需另日复验必须新建当日批次。
