# Task5 Fix Round1 开发自测与冻结

状态：**DONE_FIXROUND1，开发修复自测结束，待Jason独立复验**。2026-10-02 07:04已释放设备/服务/Gradle独占，不把开发自测写成独立通过，不执行Task6。

## 产品修复与边界

- AgentModels.kt:33/73/77/163：动作携带原卡，保存runId/traceId/原confirmation_required.eventId/sequence和当前等待卡。身份仅为客户端归属，非凭证，不改变工具参数。
- AgentViewModel.kt:123/136：动作入口必须匹配当前完整摘要及原身份，且非忙碌/错误、摘要合法未过期。无绑定泛化确认/取消、错误确认语句、金额篡改、历史版本均拒绝，不发请求、不追加消息、不清新摘要。
- AgentViewModel.kt:149/176/284/331/485：HTTP/SSE原事件给卡绑定版本；新输入立即撤销旧等待，终态/等待输入/账号变化失效，SavedState仅恢复当前版本；兼容工具成功不冒充终态，原同身份重放不复制卡。
- AgentCardRenderer.kt:171/189/196与Fragment.kt:138/159：原确认卡布局/文案保留，历史失效按钮变灰且禁用；动作仍经ViewModel二次检查，不仅依赖视觉禁用。原售后特定“确认退款/取消退款”通道未改，已有售后回归通过。
- 未改XML/Figma、Spring/D产品、17工具、交易流程、SSE游标、脱敏、归属协议；输入补充及普通手输仍走原发送入口。

## 先红后绿与覆盖

API缺失编译失败保留api-red.log；有入口但无校验时6项全失败保留behavior-red.log；绑定修复后6项绿保留green.log。扩展测试一次AgentEvent参数位置编译失败保留targeted.log，改命名参数后最终通过targeted-final.log。

Task5ConfirmationActionTest 9/9：

1. 数量1取消终态历史卡不能取消跨run数量2，历史展示保留，当前数量2可取消。
2. 同run/同trace重新准备的新摘要，旧确认和旧取消均拒绝。
3. 终态卡确认/取消均拒绝。
4. 忙碌与waiting_input撤销旧动作，普通补充输入仍继续。
5. 篡改金额、错误“确认支付”、无绑定取消拒绝；合法确认只在隔离仓库验证，无真实交易。
6. 过期摘要拒绝；账号切换清历史并拒绝捕获的旧动作。
7. 摘要字段完全一致、run/trace一致、仅eventId变化，旧卡仍拒绝。
8. SSE同run版本替换、原身份重放不重复、兼容工具成功不撤销、其它run终态不撤销、当前终态撤销。
9. SavedState恢复只放行当前有效卡，历史保留但不能动作。

最终受影响定向64/64：专项9、聊天状态10、原ViewModel26、HTTP仓库11、原聊天2、卡片1、售后2、登录门槛3；XML报告已复制本目录。未重复全量Maven/Android，以本轮精确受影响回归为准。

## 最新APK与真实P1

最新构建、安装、设备base.apk回拉SHA均为：

`840D115471CE8BE02ADE51B2CB3E5CF6D3D913EE4D1CA74375DF9250A130A724`

主聊天Maestro完整退出0：游客H、稍后首页、登录返回AI、A空态、问候、缺城市与补槽、E真套餐、显式详情/返回、J多轮。没有沿用旧APK。

同设备/同会话 `1126acbd-6518-4ff9-b33d-adc498b502d2`：数量1准备→合法取消→数量2准备→滚到旧数量1卡→实际点击旧取消→滚回当前数量2→合法取消，3个专项Maestro均退出0。

- before-old-tap-ui.json：只见旧数量1，确认/取消 enabled=false，取消坐标[680,1033][996,1138]；历史摘要仍显示¥90。
- before-old-tap.json与after-old-tap.json：同run `713f0fa5-88ba-4fe8-8c20-a8c0e669fcba`、同trace、同resource/ruleVersion/expiry，quantity=2、amountCents=18000、waiting_confirmation完全不变。
- after-current-cancel.json：同run为cancelled；当前数量2¥180卡合法取消成功。图片已查看，取消后原摘要仍在，按钮失效，出现真实“已取消本次操作”。未点击真实确认按钮。

## 新run身份与失败边界

本轮API会话 `68a3e9e8-8c63-47bb-9a37-a4799d6c8278`，准备run `29052ecf-f56e-4b28-b159-2c846ece6878`。实际缺城市NEEDS_INPUT→同run补充COMPLETED→新run数量2AWAITING_CONFIRMATION→同run取消COMPLETED；未观察到create/pay/refund/complaint/redeem写工具。

fresh-http-sse-identity.json：准备场景原11条eventId/sequence/runId/traceId/status与SSE逐条一致。网关parsed、两个tool_result和confirmation_required共4条兼容事件单列，无原eventId/sequence，不声称完整原身份。此前本人订单查询原9条配对是另一个场景，原文件与原false保留，不替换为本次11条。

fresh-identity.log：旧保存token返回401。fresh-identity-final.log：重新登录后实际3轮HTTP完成，但脚本错误等待completed而超时；等待确认的网关结果实际是confirmation_required。修正脚本边界后复用本轮已完成HTTP，只重新亲自读SSE并取消，同身份11/11通过，fresh-identity-recovery.log退出0；不重复造报价，不把首次超时藏掉。恢复用sessionId只从本任务账号最新行只读取得，完整原件在忽略目录。

其它运行器失败边界：初次健康探针误用端点得到401/404，既定端点核对正常；首次冻结在身份日志写入期间遇文件锁，结束后冻结成功；首次设备APK回拉用了错误工作目录失败，绝对路径严格重跑成功，installed-apk.json已验证两SHA相同。以上均非产品改动，不冒称首次通过。

## 实际命令

JDK：C:/Users/caid0/.jdks/ms-17.0.17；Python：C:/Users/caid0/anaconda3/envs/pinnT/python.exe；Maestro2.8与emulator-5554。密钥/随机账号由忽略目录读取，不在命令记录公开。

```powershell
# android-app目录，先专项红/绿，再最终定向，实际退出0及64/64见targeted-final.log和XML。
./gradlew.bat :app:testDebugUnitTest --tests '*Task5ConfirmationActionTest' --console=plain
./gradlew.bat :app:testDebugUnitTest --tests '*Task5ConfirmationActionTest' --tests '*Task5ChatStateTest' --tests '*AgentViewModelTest' --tests '*AgentChatStateTest' --tests '*HttpAgentRepositoryTest' --tests '*AgentCardRendererTest' --tests '*AgentAfterSaleCardTest' --tests '*AgentLoginGateTest' --console=plain
./gradlew.bat :app:assembleDebug --console=plain
# 以下为C:/fdz-build目录，Maestro运行器注入本任务随机账号与真实Spring资源。
adb -s emulator-5554 install -r android-app/app/build/outputs/apk/debug/app-debug.apk
./scripts/Run-Task5Chat.ps1 -Label fixround1-main
./scripts/Run-Task5Chat.ps1 -Flow task5-fixround1-stale-prepare.yaml -Label fixround1-stale-prepare
. ./scripts/Initialize-FandaziEnvironment.ps1
python scripts/task5-fixround1-session-snapshot.py before-old-tap
adb -s emulator-5554 shell uiautomator dump /sdcard/task5-fixround1-before.xml
adb -s emulator-5554 pull /sdcard/task5-fixround1-before.xml .runtime/task5-fixround1-before.xml
./scripts/Run-Task5Chat.ps1 -Flow task5-fixround1-old-tap.yaml -Label fixround1-old-tap
python scripts/task5-fixround1-session-snapshot.py after-old-tap
./scripts/Run-Task5Chat.ps1 -Flow task5-fixround1-current-cancel.yaml -Label fixround1-current-cancel
python scripts/task5-fixround1-session-snapshot.py after-current-cancel
python scripts/task5-fixround1-identity.py
# 回拉installed路径由pm path读取，绝对路径保存至忽略目录，SHA严格比较。
adb -s emulator-5554 shell am force-stop com.fandazi.app
# android-app目录，实际1个daemon stopped。
./gradlew.bat --stop
# C:/fdz-build目录，不stage/commit。
./scripts/Save-Task5FixRound1Freeze.ps1
```

## 冻结、依赖与释放

- 累计51个Task5源/运行器文件的稳定排序“path:大写SHA256”LF拼接总摘要：`079B60CBF024247094C70D26867303862797BBE20837A78A10CB0FC407A23303`。本轮delta11项：四产品、一个专项测试、两个Python证据脚本、一个冻结脚本、三个Maestro；两报告和证据另列write-set.txt。
- freeze-manifest.json给每项before/after及累计SHA；fixround1.patch只适用本轮前像SHA，前像`.runtime/task5-fixround1-before`已保留。previous-freeze-manifest.json及上轮task5-own-source.patch不覆盖；合并应R0增量后再用修复增量，不能从干净HEAD整文件覆盖混合脏代码。
- MainActivity本轮无新hunk，上轮仅AI tab/账号绑定两个Task5 hunk；nav_graph/SessionRouteCoordinator及12项原脏依赖均与上一冻结SHA一致。最低提交/未提交依赖hunk见上轮scope-boundary.md，后续checkout/payment/voucher/QR/refund/complaint等仍排除，不把能编译当作后续业务交付。
- Task8真实夹具7文件SHA均不变；D服务/测试/契约70文件摘要C977E9EAFE59A6A2658689BE9F8AB634AC5FAE5607C305E54ACCEC6AAEC60BE6仍与R0相同。手机HEAD c614bd5，D HEAD ee04c2c，未stage/commit/创建Agent/改主plan/spec/独立报告。
- 07:04:20释放，app force-stop、Gradle停止，无活跃测试工作进程；Spring8080 PID44776、D8091 PID54004健康保留，entryPoint=service.app:create_app。release-environment.json为实际核对。
- 待覆盖：Jason本轮独立复验及额外全离线/游客禁发点击；真实确认交易刻意未执行，合法确认仅隔离仓库。Task6/7/8写闭环不属本轮，原审计容量关注不扩修。旧P1/原SSEfalse均保留，开发自测不代替独立通过。
