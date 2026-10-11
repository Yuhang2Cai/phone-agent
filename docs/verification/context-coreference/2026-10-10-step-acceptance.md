# 上下文重构手机仓按步骤验收

主 Agent 顺序执行；基线 `3e5096b951a752d2d93a87b75dd6f8d745dbddbc`，分支 `codex/context-coreference`，未合入 master，未切换共享部署。详细规格、计划、冻结 SHA 清单和逐项失败记录在对应 Agent 分支的 `docs/verification/context-coreference/`。

CC-01：可信消息来源、同展示键正文冲突、8 路输入顺序、历史缺口、跨用户/会话拒绝、活动任务版本保持及 2.1 严格协议已完成。来源和会话更新在同一事务，冲突一起回滚；来源不作为业务幂等或确认资格。

首次新增协议红测 2 tests/1 failure，指定合法 2.1 断言触发；首次实现复验因 Maven 未打包新契约失败，补资源 include 后复验通过，中途一次运行被中断不计通过。最终开发自测 39 tests 全通过。

冻结手机 16 文件后，精确隔离快照 `.runtime/cc01-r3` 执行 `mvn -B -Dtest=AgentConversationSourceTest,AgentContextProtocolTest,AgentProtocolBoundaryTest,AgentHistoryRecoveryTest,AgentGatewayV1ContractTest,AgentCompareContextTest test package`，退出码 0，39 tests/0 failures/0 errors/0 skipped，22.495 秒，见 `.runtime/context-coreference/cc01-isolated-spring.log`。构建后源码与冻结清单相等。JAR 内新契约与权威原字节一致，SHA256 `b5729dda29d2c79a81bbd476f718a705815bd98d1e1bcd20d2d484f90948bb88`。

本轮来源事务测试实际使用 H2；MySQL 迁移/生产并发在后续专项实测，不混称已通过。客户端模型仍未修改，无新 APK。真实工具执行证据、在线质量、性能及 CC-02 至 CC-11 尚未验收。

## CC-04 展示引用

前置 `a4a40b9`，Agent 前置 `01d0c16`。本任务 17 文件冻结于 `cc04-freeze.json`，精确快照 `.runtime/cc04`；构建/设备测试后所有摘要仍一致。既有 ASR AAR/模型/词表作为固定构建依赖单列摘要，补足缺失的本地模型，不提交这些资源或宣称 ASR 新功能交付。

CC-04.1 至 .4 均通过：最终重查卡片登记持久顺序与版本、过滤/重排使旧版本失效；跨用户/会话/epoch/越界拒绝；客户端只传公开引用，Spring 生成可信 evidence；历史恢复、发送重试及页面重排不串引用；列表关联不证明视觉注意，不省略 ordinal 后默选。来源幂等指纹纳入引用，正文相同但引用变化拒绝复用原输入。

隔离快照 Maven 定向七类测试与 package 退出 0，38 tests/0 failures/0 errors/54.889 秒，日志 `.runtime/context-coreference/cc04-isolated-spring-r2.log`。Android 离线 Gradle 助手全部单测及 assembleDebug 退出 0，121 tests/0 failures/0 errors，3 分 36 秒，日志 `cc04-isolated-android.log`。Agent 精确快照对应组合测试 113 passed、compileall 退出 0，详见 Agent 同名验收报告。

独占 MySQL 新库 fandazi_context 上实际执行全部 Flyway 与 JPA 校验；独占 Spring 8083 配展示探针 8093 实际调用目录工具，无真实模型/交易工具。HTTP 三项顺序、引用回传和第四项 400 拒绝通过。数据库只读核查 HTTP/Android 两个会话各两条来源和四条消息，登记顺序与 evidence 相等；该合成账户订单/退款/投诉/团购券/确认凭证等七张表为零。探针只用于展示传输验收，不算 CC-07/08/10 的语义和模型质量结果。

最新 APK `.runtime/cc04/android-app/app/build/outputs/apk/debug/app-debug.apk`，SHA256 `579BD621F94F394B87BE2DDD157A06601B99F104023890701227C87DEA6542C5`。独占 emulator-5580 安装前确认旧应用及 com.dirror.music 均不存在。Maestro 第二轮 JUnit 1 test/0 failure/74.536 秒；停止/重启进程后历史三卡顺序保留，发送“第二个”回传对应列表。实际截图已逐屏核对既有 Figma 56:134、56:404，结构延续、无重叠、没有新增 UI。证据 `.runtime/context-coreference/cc04-maestro-r2/2026-10-10_225909/`、`cc04-maestro-r2-report.xml`、`cc04-http-proof.json`、`cc04-db-proof.json`。

保留失败：首次缺模块红测；Java 17 测试 getFirst 不兼容；参数/日志路径环境错误；Android 缺既有模型；第一次 Maestro 系统蓝牙崩溃弹窗及流程失败。只在独占模拟器禁用实际故障包 com.google.android.bluetooth 后完整重跑，冻结产品源码不变。性能未测、在线指代质量未测，后续 CC-08/10 继续，不称全项目完成。

APK 清理：核对绝对路径、包名、SHA256、最新保留包及占用后，删除系统临时目录内同摘要重复应用/测试包及本轮应用临时副本 120 份、11,901,546,121 字节；本任务工作树只保留上述最新验证应用。C 可用空间 36,558,573,568 → 48,456,630,272 字节，D 58,579,001,344 → 58,578,919,424 字节。不同摘要且无法确认所属用途的其他任务临时 APK 留到其归属确认后清理，不停未知进程。完整路径/摘要/原因及复查清单 `.runtime/context-coreference/cc04-apk-cleanup.json`。退出冻结后仅回写验收与步骤状态，准备精确提交。

清理补记：最后一份本轮临时应用最初被占用，确认本任务 Maestro 已退出后复试删除成功；未停止其他 Maestro 进程。清理最终总数以上文 120 份为准。
