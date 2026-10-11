# AG03 TL-01按步骤验收

日期2026-10-12；同一主Agent；规格`ag03-task-lifecycle-r2`。结论：新旧结构、受控加载、打包与执行关闭门禁通过；生命周期调度、工具执行栅栏和真实业务链路尚未实现/验收。

## 版本与冻结

前置Agent `2e2fbf21d5948e8ca432dd1eb774f6ad800e9641`、Phone `e145cddab6b313db0f7a791fd036b20bdbde3427`；双仓master保持`58a3314973c611e1482612a1863b5f9d8352afe8`/`0b057716667b183677682e225153b8c513df1ca5`。冻结Agent树`703565f6bfedc8a93336040585764ad6ea1c71f8`，Phone树`d7205801280e76e9ebcd27b40a4b15fac9813e10`。完整文件/SHA、命令、退出码见同目录`tl01-freeze.json`。冻结期间无源码或规格修改，验收后只回写报告、台账和计划，复用相同源码构建证据。

## 步骤与实际结果

| 步骤 | 实际动作/证据 | 结果 |
|---|---|---|
| TL-01.1 | Python及JUnit人工合法2.2样例在原校验器触发`合法2.2必须可校验`，分别退出1；旧4份YAML原字节SHA冻结 | 通过 |
| TL-01.2 | 独立2.2任务/run/turn许可、目录水位、控制/对账/安全退出回执、支付重准备、control_reply；工具2.0包含必需taskFence/actionVersion；原17工具及payload完全相同 | 通过；仅结构能力 |
| TL-01.3 | 工作区Python定向53通过，含夹具和API回归97通过；JUnit新6＋旧20＝26通过，失败/错误/跳过均0；原4份SHA相同，两个新权威/镜像逐字节相同 | 通过 |
| TL-01.4 | 隔离Python97通过/0skip；Java26通过/0skip；compileall退出0；Spring -DskipTests package退出0；实际JAR包含与权威同字节的新2份YAML | 通过 |
| TL-01.5 | 报告后精确提交Phone权威/Java/pom/测试，再提交Agent镜像/Python/测试；回写计划与项目共享 | 通过；组合提交号在下方闭合记录 |

原契约文件`agent-protocol-v1.yaml`、`agent-tools-v1.yaml`、`agent-protocol-v2.yaml`、`agent-protocol-v2.1.yaml`及`source.sha256`未改。旧2.0/2.1仍拒绝taskAdmission；2.2普通许可允许commandId=null。新请求缺任一栅栏、额外价格/身份/授权字段、未知控制动作、新增工具名称均拒绝。结构校验不代替后续归属/版本/候选证据/分数差值/执行授权检查。

执行门禁：当前2.2 run HTTP返回503且未进入authorize；TL-05装配持久控制/轮次/栅栏前不能启用或降级旧工具入口。未新增模型业务工具，支付重准备只是内部结构。

## 命令与失败留存

真实命令记录在`tl01-freeze.json`：pytest四组指定文件、compileall；Maven指定`AgentTaskProtocolContractTest,AgentContextProtocolTest,AgentContractSchemaTest,AgentProtocolBoundaryTest test`和`-DskipTests package`。

私有证据根：`C:/Users/caid0/.codex/worktrees/ag03-task-lifecycle/-Demo-master/.runtime/ag03-task-lifecycle/tl01`。`red-python.log`/`red-java.log`保留指定红测；`green-java-1.log`保留首次启动失败，根因为pom白名单漏打包新YAML，补最小资源清单后`green-java-2.log`26通过。生成脚本语法错误和PowerShell逗号参数错误未执行业务测试，不计红测或通过。隔离日志为`isolated-python.log`、`isolated-java.log`、`isolated-compile.log`、`isolated-spring-package.log`。

## 五维结论与遗留

- 需求符合性：TL-01规定的独立结构与旧兼容通过；未声称控制/调度已完成。
- 代码质量：启动完整校验、未知字段严格拒绝、17工具同源约束和中文注释；双仓diff --check通过。
- 实际运行：Python97/JUnit26组件与旧HTTP边界回归通过；AG03的26业务场景运行0，真实MySQL/模型/UI均未运行。
- 源码可构建性：双仓精确隔离候选编译/package通过，JAR真实资源核验通过。
- 性能：本Task无性能门槛；TL-10实测未运行，不能视为性能达标。

没有启动共享服务、修改设备或构建APK，无本轮APK清理项。下一步TL-02任务目录及真实MySQL并发/重启验收。尚未合入master，最终合并需用户另行明确同意。
