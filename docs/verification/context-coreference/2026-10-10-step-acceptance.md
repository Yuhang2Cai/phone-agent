# 上下文重构手机仓按步骤验收

主 Agent 顺序执行；基线 `3e5096b951a752d2d93a87b75dd6f8d745dbddbc`，分支 `codex/context-coreference`，未合入 master，未切换共享部署。详细规格、计划、冻结 SHA 清单和逐项失败记录在对应 Agent 分支的 `docs/verification/context-coreference/`。

CC-01：可信消息来源、同展示键正文冲突、8 路输入顺序、历史缺口、跨用户/会话拒绝、活动任务版本保持及 2.1 严格协议已完成。来源和会话更新在同一事务，冲突一起回滚；来源不作为业务幂等或确认资格。

首次新增协议红测 2 tests/1 failure，指定合法 2.1 断言触发；首次实现复验因 Maven 未打包新契约失败，补资源 include 后复验通过，中途一次运行被中断不计通过。最终开发自测 39 tests 全通过。

冻结手机 16 文件后，精确隔离快照 `.runtime/cc01-r3` 执行 `mvn -B -Dtest=AgentConversationSourceTest,AgentContextProtocolTest,AgentProtocolBoundaryTest,AgentHistoryRecoveryTest,AgentGatewayV1ContractTest,AgentCompareContextTest test package`，退出码 0，39 tests/0 failures/0 errors/0 skipped，22.495 秒，见 `.runtime/context-coreference/cc01-isolated-spring.log`。构建后源码与冻结清单相等。JAR 内新契约与权威原字节一致，SHA256 `b5729dda29d2c79a81bbd476f718a705815bd98d1e1bcd20d2d484f90948bb88`。

本轮来源事务测试实际使用 H2；MySQL 迁移/生产并发在后续专项实测，不混称已通过。客户端模型仍未修改，无新 APK。真实工具执行证据、在线质量、性能及 CC-02 至 CC-11 尚未验收。
