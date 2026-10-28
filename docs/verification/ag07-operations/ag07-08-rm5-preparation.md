# AG07-08 RM-R5 离线准备验收

2026-10-27，同一主Agent。**RM5-00离线通过，RM5-01—03未运行，等待独立新轮授权**。方案见[已建单后续验](../../superpowers/plans/2026-10-27-ag07-ui-post-order-continuation-r5.md)，原失败见[RM-R4实际报告](ag07-08-rm4-result.md)。

精确候选Agent `16b9095a41eda922ceec6551399a4fb29c1f73ca`（`C:/ag07-build/08-rm5-agent`）；Phone `371a6c706ef839942ff4f3abf335e8fb6f8bab73`（`C:/ag07-build/08-rm5-phone`）。仅新增已建单前置、两文本零确认旅程、18发送预算、领域写传输前拒绝和三个Maestro断言文件；产品源码/模型提示词/数据库结构均未修改。

## 验收与原失败

- 10项原前置指定红→80项组合通过；新增4项领域写指定红（`post-order-tool-red-r2.xml`），触发“缺少已建单工具发送守卫”。第一次使用系统pytest临时目录遇到WinError5，4环境ERROR保留在`post-order-tool-red.xml`，不算有效红测；改用自有私有临时目录。
- 修改后46项受影响开发测试通过；精确隔离候选完整7文件组合**84项通过**，退出0，`rm5-isolated.xml`。命令：`python -X utf8 -m pytest tests/test_ag07_ui_{post_order,paused,remaining,revalidation,hierarchy,runtime,send_guard}.py -q --basetemp=<本任务私有目录> --junitxml=<rm5-isolated.xml>`，花括号表示7个显式文件，实际按列表传参。
- 31个采集/驱动Python文件语法编译退出0；三个实际Maestro文件`check-syntax`均退出0（rv-confirm、operations-smoke、rm-post-order-restore），`rm5-syntax.json`及各日志。
- 原Phone后端/Android编译输入未变：以RM4精确树为基底仅覆盖3个Maestro文件，569个既有target产物逐文件SHA256一致，限定复用原构建。不复用新驱动测试、不把旧APK当新构建。
- 12份原冻结材料全文SHA256一致，未更新金标。新输入`ui-remaining-input-r5.json`冻结4182文件，包括精确双树、产物、依赖、工具、驱动、原业务事实与方案。
- `run_ui_remaining_r5.py --execute`在**approval=pending**时实际退出1、指定`new_round_not_authorized`；无轮目录、无资源启动。`rm5-pending-guard.stdout/stderr`保留。此为离线负例，不是新真实尝试。

## 证据选择

| 范围 | 选择 | 依据和限制 |
|---|---|---|
| 原会话、订单、授权、原键、实际页面 | 待新运行 | 既有事后事实只做前置期望，当前动态状态必须实际核对，0模型通过后才可发送。 |
| 新预算、顺序、写工具前置、来源检查 | 新运行 | 84隔离项通过；四种领域写网络前阻断，其他工具依然核scope/fence/配额并真实记录。 |
| Android/Spring构建 | 限定复用 | 精确基树不变，仅Maestro文件变化；569既有产物摘要一致，APK身份沿原验证。 |
| 旧RM4建单事实及暂停/恢复/fresh | 限定复用 | 仅对应独立报告范围；不补原confirm采集缺口、不把失败整轮改判。 |
| 真实两文本、原键HTTP、质量及09 | 待验收 | 未发送、未评分，不能用离线测试计实际业务通过。 |

需求范围和驱动质量离线通过，真实运行未完成，精确候选可解析且相关构建限定复用；性能债务继续延期。该准备新增模型/DB/设备采集均0，不重跑矩阵、不合master。08未完成、09未开始。
