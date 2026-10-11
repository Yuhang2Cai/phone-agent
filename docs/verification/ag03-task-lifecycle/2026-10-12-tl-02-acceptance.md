# AG03 TL-02按步骤验收

日期2026-10-12；同一主Agent；规格`ag03-task-lifecycle-r2`。结论：持久目录/归属/版本、唯一前台与在途run、容量预检、终态边界、真实库并发和重启回读通过；真实生命周期交接待TL-03，组合模型/UI/性能待后续任务。

## 前置与冻结

前置Agent `f805bfc64ae2ea9515be577934f818fd97d56ba5`、Phone `035c82616751c2352bc242d9fbf575e6f26b58ba`。master仍为Agent `58a3314973c611e1482612a1863b5f9d8352afe8`、Phone `0b057716667b183677682e225153b8c513df1ca5`。Agent仅计划/TODO增量，Phone为会话两类、目录/task/run六类、第一迁移、四个测试/夹具；原dirty不纳入。

冻结树：Agent `8514b062d06949ffb3d01acacdf668792d6fb9ff`；Phone `ca3a3c61730f6b2363d913068f20b4585bdc2a0f`。精确清单/SHA/命令/退出码见`tl02-freeze.json`。冻结期间源码、配置及规格不改；验收后报告/台账/计划回写不改变验收标准，仅文档差异明确复用构建证据。

## 步骤结果

| 步骤 | 实际验收动作及输出 | 结论 |
|---|---|---|
| TL-02.1 | TL-00已冻结S07/S08/S09金标；旧入口可运行红测触发`第二个前台任务必须拒绝且不覆盖已有任务`（预期409/实际200，退出1）。新增越权/旧epoch404、五暂停容量与净零交换断言；旧终态run显式进入2.2另建指定失败断言后修复 | 通过 |
| TL-02.2 | session→task→run锁序；完整scope；数据库唯一active_scope_key/inflight_task_key；task1:Nrun；焦点/任务/代次分开；公开目录只查active/paused并严格投影；旧活动run不迁移，终态后显式进入不重开旧run | 通过 |
| TL-02.3 | H2新增18＋旧来源/协议12＝30通过；真实MySQL18通过，12并发创建1成功/11冲突且active=1，8并发run唯一；容量拒绝不改目录、净零交换预检可行；推荐旧run终态跟进新run、查询完成清焦点；GET执行/准备/业务表增量0 | 通过；容量交换是预检，非真实控制交接 |
| TL-02.4 | 精确快照Python91、H2 30、真实MySQL18通过/0skip；Agent compileall0、Spring package0；同一精确JAR在8086两次启动，真实JWT目录200/匿名401/越权404，任务/run/版本/等待输入停点相同，整个数据库观测不变 | 通过 |
| TL-02.5 | 报告先写，再精确提交Phone实现和Agent文档，回写计划/共享；真实MySQL与H2区别和失败记录保留 | 通过；组合SHA见下方闭合记录 |

目录GET不依赖模型/工具；捕获的Agent HTTP请求0，执行记录、报价、确认凭证、订单、退款、投诉计数全部无增量。没有以HTTP200单独替代字段/数据库核验。S21只验收目录task—run语义，真实跟进分类/事实继承留待TL-05/06/09。目录水位字段已持久化且与目录同快照，TL-03 outbox尚未装配前为0，不冒称事件补读已实现。

## 真实库与重启证据

本任务native MySQL8.0.45仅监听127.0.0.1:13326，独立数据目录`C:/Users/caid0/.codex/worktrees/ag03-task-lifecycle/-Demo-master/.runtime/ag03-task-lifecycle/infra/mysql/data`。当前合成schema `fandazi_ag03_r2`，Agent后续预留独立`dianping_intent`。PyMySQL只安装到任务忽略目录，未改全局环境。旧13316/13315数据库和共享8080/8091不变。

首轮开发schema `fandazi_ag03`保留；run表按r2命名为`agent_task_runs`后使用新schema重新迁移，不修改已应用迁移的原库或清空数据。正式冻结的迁移通过H2和MySQL，Hibernate ddl-auto=validate，成功Flyway历史核验通过。真实并发测试使用真实连接/事务，不把内存锁或H2结果当MySQL证明。

冻结JAR SHA256 `65ded3263ada54bf2f7b9d0370f179249dbae64aa587cf3d12223ccd627224d3`。重启目标task `6f148a21-43d3-4784-91f8-5cd6375fdd41`、run `037be852-d9a1-479c-abcf-b5af5da3a5fc`，原停点waiting_input；两次目录相同且数据库观测不变。只停止/重启本任务拥有的只读Spring进程，结束后8086/8096释放；13326保留供TL-03继续使用。

私有日志根`C:/Users/caid0/.codex/worktrees/ag03-task-lifecycle/-Demo-master/.runtime/ag03-task-lifecycle/tl02`：`isolated-python.log`、`isolated-h2.log`、`isolated-mysql.log`、`isolated-compile.log`、`isolated-spring-package.log`、`spring-restart-r2-1.log`、`spring-restart-r2-2.log`。真实命令/退出码见冻结清单；所有正式命令退出0，JUnit无失败/错误/跳过。

## 失败、修复与复验

`red-java-r2.log`保留指定状态红测；初次测试枚举拼写编译失败`red-java.log`不算红测。初版样例用了不存在的意图标签，权威Schema正确拒绝，改为原`deal_recommendation`，没有新增意图。H2测试自身数字节点类型比较与Flyway历史表大小写查询错误分别修复为JSON同构比较与Flyway真实info API；失败留存在`green-h2-2/3.log`，`green-h2-5.log`30通过。

自审发现旧门禁误拒已终态会话的显式升级；`red-legacy-terminal.log`指定断言失败后修复，只允许旧终态进入，活动旧run与旧入口降级仍拒绝；H2/MySQL正式候选均复验通过。

首次重启验收驱动使用系统代理，默认客户端本机502/显式直连401，未记通过。`acceptance-failure.json`与`spring-restart-1.log`保留；仅驱动改为本机直连，双仓源码/依赖/规格/产物相同，复用91/30/18及编译/package通过证据，重新执行两次启动/真实HTTP/数据库观测后通过，记录`driver_retry`。未按实现修改验收标准。

## 五维结论及遗留

- 需求符合性：本Task目录/归属/容量预检/run关系通过；真实暂停/取消/恢复、事件outbox尚未交付。
- 代码质量：严格scope投影、中文注释、数据库唯一约束、固定锁序；目录只查非终态行；双仓diff检查通过。
- 实际运行：隔离Python91/H2 30/MySQL18、真实JWT HTTP及同JAR重启通过；26完整业务场景运行0，真实模型/UI未运行。
- 源码可构建性：精确隔离归档compileall/package通过，重启运行的产物与冻结树对应。
- 性能：本Task无性能达标结论，TL-10固定条件性能尚未运行。

未构建APK，无本轮APK清理项；未合入master。下一任务TL-03持久控制命令/安全退出/普通轮次许可与完成补交。
