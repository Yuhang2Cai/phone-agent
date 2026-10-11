# AG03 TL-00按步骤验收

日期：2026-10-12。执行者：同一主Agent。规格：ag03-task-lifecycle-r2。结论：夹具/基线与观测接口通过；26个业务场景、78个规则组件及模型/UI/性能功能验收均未运行。

## 冻结与范围

Agent master：`58a3314973c611e1482612a1863b5f9d8352afe8`；方案前置：`2bf112ca3cfebfed34b97ce6ef10a2f3b24a07d8`。Phone从master `0b057716667b183677682e225153b8c513df1ca5`创建`codex/ag03-task-lifecycle`，目录`D:/project/phone-agent/.worktrees/codex/ag03-task-lifecycle`。Agent沿用方案隔离分支；两个master未改。

文件范围为Agent夹具三份、观测驱动、契约自测、授权状态/进度/报告/冻结清单；Phone为相同三份测试资源和台账/报告。没有产品调度实现、数据库迁移、模型请求或APK。共享8080/8091/原MySQL13316不切换；后续任务预留8086/8096/13326，未启动。合成事件保留24小时、幂等墓碑72小时，只作为验收条件，不启用生产留存。

## 步骤结果

| 步骤 | 实际动作与输出 | 结论 |
|---|---|---|
| TL-00.1 | 读取r2规格/计划及双仓规范，核对master/原dirty，Phone显式从master建隔离分支；记录环境及合成保留窗口 | 通过；原Phone未跟踪资料和共享其他改动保留 |
| TL-00.2 | 冻结26个场景的scope/初始task/run/输入/故障/对象/阶段/工具/业务增量/证据；13×6矩阵另文件，manifest记录SHA及版本；篡改task红测退出1并触发指定断言 | 通过；红测不是导入/环境错误 |
| TL-00.3 | run_case显式装配component/live驱动；输入不含金标，观测独立且只读，缺真实适配器拒绝，live须证据文件 | 通过；只验收驱动契约，未实现或冒称26场景业务通过 |
| TL-00.4 | 工作区夹具38项通过；与跨仓契约34项合计72通过；正式隔离命令夹具38通过/0skip | 通过；规范指定夹具命令实际执行 |
| TL-00.5 | 双仓替代index归档，Agent compileall退出0，Phone -DskipTests package退出0；精确提交后另回写步骤和共享 | 通过；Spring业务测试未运行，Android未构建/运行 |

固定金标已有task只冻结已存在的ID，新task检查服务端创建及身份差异；不能让客户端指定未来新task ID。来源和任务事实各自保留，gold不包含grant/真实用户资料。驱动缺实现即报未运行，不能返回gold当观测。

## 命令、失败与复验

运行环境设置PYTHONUTF8=1，MOBILE_PROJECT_ROOT显式指向对应Phone候选。命令：

```text
python -m pytest tests/test_task_lifecycle_fixture_contract.py -q --tb=short --basetemp=<本任务唯一临时目录>
python -m pytest tests/test_task_lifecycle_fixture_contract.py tests/test_cross_repo_contracts.py -q --tb=short --basetemp=<本任务唯一临时目录>
python -m compileall -q conversation intent dag service evaluation tests/task_lifecycle_harness.py
mvn -q -DskipTests package
```

首次Python运行因Windows GBK读取UTF-8配置退出1，不算红测；设置UTF-8后业务红测1 failed，触发“任务金标归属不一致”。首次绿测37通过/1环境错误：系统pytest临时目录访问被拒，指定本任务唯一basetemp后38通过；补齐初始状态后的联合自测72通过。首次隔离解包因Phone历史长路径触发Windows路径限制退出1；改用C:/fdz-build短路径，源码/金标不变，复验38通过、Agent编译0、Spring package0。环境失败与红测日志分别保留，没有把失败或跳过计为通过。

实际树、文件摘要、命令、退出码及日志路径见本仓`tl00-freeze.json`与本任务`.runtime/ag03-task-lifecycle/tl00/acceptance.json`；原始失败见red-environment-failure.log、green-temp-environment-failure.log及acceptance-failure.json。验收源码/规格冻结期间未修改，全部实际输出核对后退出冻结；其后仅写报告、台账及计划勾选，已验收源码/金标与规格SHA复核一致。

## 五维结论

- 需求符合性：TL-00要求的双仓起点、26场景金标、驱动契约和证据模板齐备；功能开发仍待TL-01起。
- 代码质量：驱动显式区分组件替身/live、禁止金标混入观测、预期递归只读、文件SHA变更拒绝；中文说明和敏感字段检查通过。
- 实际运行：夹具38、跨仓基线34通过；真实调度/模型/数据库/UI均未运行。
- 源码可构建性：精确隔离Agent编译及Phone package均退出0；Phone产品Java源码未修改。
- 性能：未测，不宣称达标。

合入master前仍须用户对本次明确同意。新UI的Figma确认、真实MySQL/模型/设备验收属于后续必需步骤。
