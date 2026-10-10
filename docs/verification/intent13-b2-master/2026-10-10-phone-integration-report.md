# B2 十三类意图手机与 Spring 集成验收

日期：2026-10-10。分支 `codex/refactor-intent13-b2-integration`，基于手机仓 `master` 的 `11e7c83bad711394e38da82cf03e77149209dfd9`。配套 Agent 分支同名，基于 Agent 仓 `master` 的 `2904033a364f7d3ead1cc223285b711647cf8801`。本轮收到用户“同意合入”后已合入主分支，合并后回归通过；共享部署未切换。

Spring 显式选择内部协议 `2.0`，v2 契约打包入 JAR；仍保留旧 1.1 校验能力。内部令牌、数据库密码由本机忽略文件与独立容器环境提供。Agent 启动入口要求 `FANDAZI_NEW_AGENT_ROOT` 指向本轮源码，并把 Agent 独立 MySQL 与 Spring 业务库分开。`mvn test` 实际 202 项 0 失败，`mvn -DskipTests package` 通过；原始日志 `.runtime/intent13-b2-master/mvn-test.log` 和 `mvn-package.log`。

Android 新版 `intent=composite` 展示复合进度，旧历史 `isComposite` 仍可读取；回复或等待状态时进度不滞留。针对强停重启丢失会话 ID 的真实失败，新增按认证身份绑定的本机指针；同账号恢复原会话，退出或换用户清除旧指针。首次失败、修复与复验均已保留。`gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PdebugApiBaseUrl=http://10.0.2.2:8080/api/` 退出码 0，387 单测 0 失败，lint 与两种 APK 构建通过。最终精确快照 APK SHA256：主包 `F591241599C608E06FEC8BE05FA405D63D5A5FCC4B5D12774750281E6FE72DB3`；测试包 `45281CC58E2A31A0605246201967CBC1B11BED47F38C8C1D3C3ADACCEA0547C5`。本地构建依赖的已核验 ASR 模型 assets 未暂存，也不进入 Git。

真实模拟器 `emulator-5560` 上已通过本轮流程：`intent13-b2-single`、`intent13-b2-composite`、`intent13-b2-confirmation`、`intent13-b2-composite-write`、`intent13-b2-errors`、`intent13-b2-recovery`、`intent13-b2-active-progress`；现有 `chat-progress-lifecycle`、`chat-store-without-location`、`refund-greeting-ending`、`store-tab-stability` 也通过。最新复合双写日志 `.runtime/intent13-b2-master/maestro-b2-composite-write.log`，截图 `C:/Users/caid0/.maestro/tests/2026-10-10_200148/intent13-b2-composite-write/takeScreenshot/`：先确认退款，再确认投诉，完成回复明确是退款申请待处理和投诉工单待处理；本合成订单两种记录各 1 条。取消退款场景的三张合成券在取消后退款申请数为 0。未确认写入与越权写入均未观察到。

恢复场景首次失败是生产会话 ID 跨强停丢失，修复后同账号恢复与续填通过；第二次脚本断言文字不符，按真实产品提示修正流程后通过。服务重启期间曾因同一 shell 继承 Agent 的 DB 密码使 Spring 启动失败，隔离启动环境后复验通过。所有首次失败日志均在本任务 `.runtime`，没有将预期结果改成首次通过。

Android 单目标 UI 与在线分类 13 类覆盖分别验证；随后十二个普通标签在最终快照 APK 上逐类独立会话截屏，12 张截图与后端标签/终态的关联见 Agent 仓 `docs/verification/intent13-b2-master/android-label-matrix-summary.json`。其中 11/12 标签与冻结预期一致，退款状态仍误判范围外。 同一空订单合成账号的业务库复查为购买订单 0、退款申请 0、投诉工单 0。完整列表、性能统计与已接受的 B2 误判见 Agent 主报告和统一实验文档。共享旧任务切换、arm64 真机性能、ASR 人工验收及核销仍未进行。精确拟提交树隔离构建和 APK 例行清理结果见下；本轮 master 合并结果见报告末尾。

精确拟提交树 `d4207befef1cd386799b30ecd91750f870ca3a5d` 在独立快照 `D:/project/phone-agent/.worktrees/codex/intent13-b2-snapshot` 通过 Maven 202 项与 package、Android 387 项与 lint/两 APK 构建。快照只额外注入两份已核对 SHA 的本地 ASR 依赖，模型文件未入库。最终主包安装到原隔离模拟器后，复合双写两次确认、强停恢复与复合只读重新通过；复合只读第一次用了已有订单账号，固定“0 单”断言失败，换预设空订单夹具复验通过。日志为 `.runtime/intent13-b2-master/maestro-snapshot-*.log`。最终主包与测试包均保留在隔离快照 `android-app/app/build/outputs/apk/`。

按项目 APK 规则删除本任务开发工作树中被快照包替代的旧主包和测试包 2 份，共 239286506 字节；D 盘可用空间从 65686257664 增至 65925545984 字节。复查开发工作树旧包 0 份；其他任务的 APK 不在本次清理范围。保留截图、日志、源码及新包摘要。

在同一最终 APK 上追加四组十二类 Maestro 流程 `intent13-b2-label-matrix-1..4.yaml`，全部退出码 0；每个固定合成句子均看到真实助手第二气泡，进度结束。后端 12 个会话全部有分类和终态，平均首次进度 93.9 ms，平均终态 10032.4 ms（n=12）。这些脚本没有修改产品代码，时延没有预设达标阈值。断开本任务 Agent 进程的 `intent13-b2-network-error.yaml` 也通过：显示可重试错误，无进度滞留或确认按钮，随后 Agent/Spring 健康探针均为 200。真实断连截图位于 `C:/Users/caid0/.maestro/tests/2026-10-10_205326/intent13-b2-network-error/takeScreenshot/`。

产品集成提交：手机仓 `73886120d076080586c30049ad57813f44b0c5e3`，配套 Agent 仓 `19e51d43892d804891ac685ab6429d95066fb665`；测试脚本与最终报告另行补充提交。上述为产品提交；本轮最终来源与已完成的双仓 master 合并见下。共享实验库存在未完成旧任务，实际部署切换继续延期到按原版本收尾之后。

## 本轮 master 合并与回归

用户本轮明确回复“同意合入”后，手机 master 从 `11e7c83bad711394e38da82cf03e77149209dfd9` 使用 `--no-ff` 合入批准源 `55ea95a681307fbfaf480f8e4501188895e86fe6`，合并提交 `c4c3b16a09fd3d1a873e6df5d1c3340e2b4d83d7`、树 `b9ea8ada441b98aafcbcf6503c369c88d8b21b03`；配套 Agent master 从 `2904033a364f7d3ead1cc223285b711647cf8801` 合入 `81a08744310351cbbb09ef194c3add37e6cab9c6`，合并提交 `73ce5c64427e2ef2811400db1bc14df65d6489c4`、树 `187a1af0d5b5257061ec7194ebfcafb0f9531981`。均无冲突、退出码 0，`git diff --exit-code <批准来源> HEAD` 均为 0，合并树与来源一致。

手机原工作树没有已跟踪改动；原有未跟踪 9585 项与合入路径无冲突，合并前后清单相同，未暂存原材料。安全清单和本次日志在集成工作树 `.runtime/intent13-b2-master-merge/`，不会进入提交。

合并后在手机 master 的 backend 亲跑固定工具链 `mvn test`：202 项、0 失败、0 错误、0 跳过，48.856 秒，退出码 0；`mvn -DskipTests package` 成功，4.018 秒。日志 `mvn-test.log`、`mvn-package.log` 位于上述目录。配套 Agent master 同时亲跑全套测试 610 通过、20 环境跳过，59.80 秒，compileall 通过。两仓协议 SHA256 仍为 `C68BF77967199B088C632C8F6595E66730E5272A4DEAC5DD465898D1CC4162B0`，两服务探针 HTTP 200。

手机 master 对隔离快照 `7a67cc7` 排除文档与已运行 Maestro 脚本后无差异，产品源码、配置和依赖一致，因此复用精确快照的 Android 387 单测、lint、双 APK 构建及最终 APK 的模拟器证据；本次未重新构建、安装或运行 Maestro。再次核验最终两包 SHA256 与本报告一致，快照保留主包/测试包各一份，开发工作树旧包仍为 0，本次无新增 APK 清理项。合并记录回写只涉及文档，不改变冻结验收标准。

两仓集成分支与日志/快照保留用于追溯，未推送远端。实际共享部署仍因旧版 6 个未完成任务而延期，本次 Git 合并没有替其确认、取消或续跑。误判、性能样本限制与 ASR、核销、arm64 真机延期结论继续有效。
