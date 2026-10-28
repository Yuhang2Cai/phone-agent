# AG07 双 master 合入核查（2026-10-28）

用户本轮明确同意“将本次 AG07 合入双 master，合并后核对提交树和验收证据，更新报告”。同一主 Agent 已执行；本次为本地合入，没有推送或部署。

## 合入结果

|仓库|原 master|授权并快进到的提交|授权/合入完整树|
|---|---|---|---|
|Agent|`8eed53203441a4c72a0ff624a094bad8d871a529`|`b9d887e011c61170fc46b1b6d4f2107d0ae1a27f`|`b693d2e12832ac2f150101050d7dd0b81ff5e7ad`|
|Phone|`e6877e2fe838ac20c7f2ac2eeaae0f09883b2a34`|`8a1cccd6bac78d6eecf317cc3d85517bcec6b80b`|`c58e8a091b2c925c296343b2de364295c12d0b2f`|

两仓均执行 `git merge --ff-only <固定授权提交>`，退出0，无冲突。合入后完整树与对应授权提交相等；相对已验源码树 Agent `5b21bf76701b963e46e7596fa413f52d812fbe28`、Phone `90397d711709169744e9506cc96277eadaf4ae2e`，全部非文档文件无差异。原始Agent开发区未切分支；Phone master原有未跟踪文件清单合入前后相同，没有清理或纳入提交。

## 本轮新核验与限定复用

|项目|本轮结果|证据与边界|
|---|---|---|
|授权版本/完整树/祖先关系|双仓通过|`premerge.json`、两仓merge回执、`postmerge.json`|
|冻结规格/计划副本/契约/数据集|12份全文SHA256一致|未改验收标准或更新金标掩盖差异|
|正式场景证据|14个唯一场景重新只读核验通过|索引r2 SHA256 `0891cfbee0c2054fe70439386f3df7a026a6970addc892b9a23e90884d89223e`；逐原文件摘要及实际结果字段核验|
|合入后定向测试|44项通过，退出0|当前master上运行入口/范围/评测契约/统一执行器消费者；`postmerge-tests.xml`|
|正式清单|validate退出0|`manifest-validate.log`；未重新执行模型、故障或真实交易|
|Agent回归/编译|限定复用1557唯一测试有效通过及168文件编译|源码/配置/相关驱动与已验树一致；未宣称本轮重新全跑|
|Spring回归/构建|限定复用459项通过、真实MySQL B01六例及package0|对应生产与测试树均不变；JAR SHA256 `b0d329cd52d9edf49a71f6701cd69ce14f2217b2b5b7492a36992d27f710dbe8`本轮重新核对一致|
|真实模型/工具/UI/恢复|限定复用原按步骤验收事实|任务/对象/旧确认/唯一执行边界保持；失败trial仍失败，不重放已消费业务|

新测试命令：`python -X utf8 -m pytest tests/test_ag07_action_evidence.py tests/test_ag07_acceptance_manifest.py tests/test_ag07_evaluation_contract.py tests/acceptance/test_actions.py -q --tb=short`，独占basetemp和JUnit输出。新增模型调用、业务请求、数据库操作均0。

## 五维结论与保留项

- 需求符合性：AG07已验非延期范围准确进入双master，14场景与r2评测入口证据保持。
- 代码质量：合入无冲突、无额外业务差异；本轮44项定向通过，历史失败和后续修复链完整。
- 实际运行：原真实链路按相同输入限定复用，本轮只新运行合入核验，未伪称重跑在线业务。
- 源码可构建性：精确树与已验构建输入一致，原构建/产物证据有效；最后报告提交仅文档。
- 性能：AG06原10%门槛、PD01—05及未测矩阵仍延期。整体模型质量、随机稳定性和未覆盖评分维度仍不宣称通过。

本次没有启动服务、数据库、设备或生成APK，不触发新的APK替换清理。验收工作树和`.runtime`内原始失败/截图/私有证据继续保留，防止收尾删除证据；未停止其他任务进程。原1557/459有效测试范围及质量不可评边界详见[最终验收报告](acceptance.md)。

证据目录：`.runtime/acceptance/ag07-operations/merge-20261028/`。本报告及入口状态作为纯文档提交追加到各master；追加后再次验证非文档树仍等于授权版本，并保存`final-integration.json`记录最终master提交号。历史验收回执不改写为合入前已合并。
