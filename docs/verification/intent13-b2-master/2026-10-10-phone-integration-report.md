# B2 十三类意图手机与 Spring 集成验收

日期：2026-10-10。分支 `codex/refactor-intent13-b2-integration`，基于手机仓 `master` 的 `11e7c83bad711394e38da82cf03e77149209dfd9`。配套 Agent 分支同名，基于 Agent 仓 `master` 的 `2904033a364f7d3ead1cc223285b711647cf8801`。当前未合入主分支，也未切换共享部署。

Spring 显式选择内部协议 `2.0`，v2 契约打包入 JAR；仍保留旧 1.1 校验能力。内部令牌、数据库密码由本机忽略文件与独立容器环境提供。Agent 启动入口要求 `FANDAZI_NEW_AGENT_ROOT` 指向本轮源码，并把 Agent 独立 MySQL 与 Spring 业务库分开。`mvn test` 实际 202 项 0 失败，`mvn -DskipTests package` 通过；原始日志 `.runtime/intent13-b2-master/mvn-test.log` 和 `mvn-package.log`。

Android 新版 `intent=composite` 展示复合进度，旧历史 `isComposite` 仍可读取；回复或等待状态时进度不滞留。针对强停重启丢失会话 ID 的真实失败，新增按认证身份绑定的本机指针；同账号恢复原会话，退出或换用户清除旧指针。首次失败、修复与复验均已保留。`gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PdebugApiBaseUrl=http://10.0.2.2:8080/api/` 退出码 0，387 单测 0 失败，lint 与两种 APK 构建通过。最终精确快照 APK SHA256：主包 `F591241599C608E06FEC8BE05FA405D63D5A5FCC4B5D12774750281E6FE72DB3`；测试包 `45281CC58E2A31A0605246201967CBC1B11BED47F38C8C1D3C3ADACCEA0547C5`。本地构建依赖的已核验 ASR 模型 assets 未暂存，也不进入 Git。

真实模拟器 `emulator-5560` 上已通过本轮流程：`intent13-b2-single`、`intent13-b2-composite`、`intent13-b2-confirmation`、`intent13-b2-composite-write`、`intent13-b2-errors`、`intent13-b2-recovery`、`intent13-b2-active-progress`；现有 `chat-progress-lifecycle`、`chat-store-without-location`、`refund-greeting-ending`、`store-tab-stability` 也通过。最新复合双写日志 `.runtime/intent13-b2-master/maestro-b2-composite-write.log`，截图 `C:/Users/caid0/.maestro/tests/2026-10-10_200148/intent13-b2-composite-write/takeScreenshot/`：先确认退款，再确认投诉，完成回复明确是退款申请待处理和投诉工单待处理；本合成订单两种记录各 1 条。取消退款场景的三张合成券在取消后退款申请数为 0。未确认写入与越权写入均未观察到。

恢复场景首次失败是生产会话 ID 跨强停丢失，修复后同账号恢复与续填通过；第二次脚本断言文字不符，按真实产品提示修正流程后通过。服务重启期间曾因同一 shell 继承 Agent 的 DB 密码使 Spring 启动失败，隔离启动环境后复验通过。所有首次失败日志均在本任务 `.runtime`，没有将预期结果改成首次通过。

Android 单目标 UI 与在线分类 13 类覆盖分别验证；部分非聊天页或需要业务资源的十二类标签未逐条形成单独 Maestro 截图。完整列表、性能统计与已接受的 B2 误判见 Agent 主报告和统一实验文档。共享旧任务切换、arm64 真机性能、ASR 人工验收及核销仍未进行。精确拟提交树隔离构建和 APK 例行清理结果见下；尚未合入 master。

精确拟提交树 `d4207befef1cd386799b30ecd91750f870ca3a5d` 在独立快照 `D:/project/phone-agent/.worktrees/codex/intent13-b2-snapshot` 通过 Maven 202 项与 package、Android 387 项与 lint/两 APK 构建。快照只额外注入两份已核对 SHA 的本地 ASR 依赖，模型文件未入库。最终主包安装到原隔离模拟器后，复合双写两次确认、强停恢复与复合只读重新通过；复合只读第一次用了已有订单账号，固定“0 单”断言失败，换预设空订单夹具复验通过。日志为 `.runtime/intent13-b2-master/maestro-snapshot-*.log`。最终主包与测试包均保留在隔离快照 `android-app/app/build/outputs/apk/`。

按项目 APK 规则删除本任务开发工作树中被快照包替代的旧主包和测试包 2 份，共 239286506 字节；D 盘可用空间从 65686257664 增至 65925545984 字节。复查开发工作树旧包 0 份；其他任务的 APK 不在本次清理范围。保留截图、日志、源码及新包摘要。
