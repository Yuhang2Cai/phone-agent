# AG07 最小生产运维闭环实施计划 r2

> 执行者：同一个主Agent，使用 executing-plans 按编号顺序开发、自测、验收、修复和精确提交；不派子Agent。用户已授权执行；AG07-00原范围已完成，先执行00-E，再接续01；尚无业务/模型调用或真实故障演练。AG07合入master另行询问。

**目标：** 在受控单实例部署中能够追踪请求及真实工具结果、发现故障、限制影响，并安全回退和恢复数据。

**架构：** 扩展既有Agent Recorder和Spring StageMetrics，配有界本地事件sink/汇总CLI；业务账本保持权威。复用现有部署和备份算法，增加显式资源守卫、配对版本清单和一致恢复验证；不新建大型监控平台。

**技术栈：** 当前Python/FastAPI/HTTPX、Java/Spring/JPA、MySQL8、Docker Compose、既有Android/Maestro。新增模块优先标准库，无新数据库、中间件或业务schema。

**规格：** [技术方案r2](../specs/2026-10-27-ag07-operations-design-r2.md)（继承r1全部运维义务）。基线Agent `8eed53203441a4c72a0ff624a094bad8d871a529` / Phone `e6877e2fe838ac20c7f2ac2eeaae0f09883b2a34`，[源文件盘点](../../verification/ag07-operations/source-inventory.md)。AG07文档分支从此Agent master创建；双仓实现分支codex/feat-ag07-operations已从各自该master创建。

## 共同约束与验收成本

- 开工前读双仓实际AGENTS.md及项目acceptance-workflow。新实现分支从当时对应master创建并记SHA；若master较此基线变化，只复核受影响差异，不直接继承过期结论。
- 固定规格全文不再改状态；本计划记录动态进度，验收阶段先独立报告后回写。任何变更验收门槛均须明确用户依据，不用实现倒改金标。
- 原AG06 10%、PD01—05、历史失败和未运行矩阵保持延期。本计划没有性能矩阵动作，没有深层探针动作；功能/安全/持久化/持续泄漏不能延期。
- 下述文件均为计划目标：`新增`尚不存在，`修改/复用`已在基线核过。Python公开接口/核心分支和Java公开方法、测试均须中文说明。
- 普通开发默认模型0。真实服务/数据库动作只在预登记的自有目标执行，先记录前置、来源库、容器/卷、端口、预算与清理方式；不得复用AG06已结束轮次的未用额度。
- 最后UI旅程≤8输入/≤1新订单确认/≤40可观察客户端模型发送，一次、首错停止。此预算仅用于新功能必要真实链路，不是重新执行AG05/AG06付费矩阵；未证明发送拦截完整性则不开跑。
- 原来同版本、同输入且不受影响的用例可复用。共享入口/模型适配/输出sink/关闭/工具出口改动必须列消费者，受影响项新验。没有证据不得把历史整组标为通过。
- 只修改文档不重跑业务；每个阶段复验一次组合，最终对稳定候选跑一次适用回归及精确隔离构建。前面通过且候选输入未变的昂贵组合在最后复用，避免再安装APK/重走模型旅程。

## 顺序与交付物

|任务|交付物|依赖|验收组|当前状态|
|---|---|---|---|---|
|AG07-00|输入冻结、预算/资源清单、验证入口与范围选择|用户开始执行|全部前置|完成；见00报告，40项及隔离40项通过|
|AG07-00-E|任务规则/来源/复用与r2冻结|00|E-contract|完成；23定向/隔离23通过|
|AG07-01|安全事件契约、跨服务关联和有界sink|00|O01/O02|步骤完成；真实回执关联仍待02/08|
|AG07-02|请求/工具/模型错误耗时与费用汇总|01|O01/O03|开始|
|AG07-03|本地可用告警、查询与确认闭环|02|O04|已完成|
|AG07-04|受控入口准入与控制/核查独立通路|01|O05|已完成|
|AG07-05|配对发布清单、维护与回退脚本|03/04|O06|已完成（真实切换待07）|
|AG07-06|显式目标双库/索引备份恢复|05|O07|已完成（真实恢复待07）|
|AG07-07|五类真实故障及恢复演练|03—06|F01—F05|分项验收通过；精确提交中，首次失败保留|
|AG07-08|必要真实模型组合和当前APK UI|07|O08|未开始|
|AG07-09|最终适用回归、隔离构建、资源/文档/提交闭合|08|O09|未开始|

建议以三个检查点安排剩余时间：先01—04可观测/保护，再05—07回退/恢复，再08—09交付。每个检查点先报告实际完成和硬阻塞，不用继续加诊断任务消耗时间；估时不是跳过验收依据。

## AG07-00 前置、范围与冻结

文件：新增 `config/acceptance/tasks/ag07-operations.yaml`、`config/acceptance/verification-policy-ag07-r1.yaml`、`tests/fixtures/ag07-operations/cases.yaml`、`tests/test_ag07_acceptance_manifest.py`；复用既有 `tests/acceptance/` 入口。证据 `.runtime/acceptance/ag07-operations/ag07-00/`，独立报告 `docs/verification/ag07-operations/ag07-00.md`。

- [x] 00.1 记双master、实现分支起点、工作树原改动、当前Python/JDK/Docker/Maestro和数据库版本；沿基线盘点核实际源码消费者与跨仓契约。
- [x] 00.2 结合[公开实践映射](../../verification/ag07-operations/evaluation-practice-mapping.md)登记稳定caseId、变更到消费者的影响表、输入/规则摘要及六类互斥处置；守卫拒绝遗漏、重复身份和缺证据冒称复用。写独立AG07动作策略：O01—O09与F01—F05都有稳定ID/执行命令/预期/证据。引用AG06性能债务只作边界，不将AG06原policy的性能动作改passed/N/A。
- [x] 00.3 预登记仅自有目标的部署manifest、容器标签+实际ID、端口、镜像、卷、私有输出根及总预算。已运行/属他人/无法确认归属时拒绝；停止原因与可恢复动作明确。
- [x] 00.4 先写缺字段/命令串注入/遗漏必需安全动作/模型额度缺失等失败断言，定向通过后冻结完整方案、计划验收段及cases/配置SHA。冻结前不启动真实业务。

验收命令：`python -m pytest tests/test_ag07_acceptance_manifest.py -q`，`python -m tests.acceptance.cli --help` 后按实际Schema运行validate/select。禁止猜CLI参数静默绕过门控。通过：每个必需组可追溯，原AG06冻结未变，预算0/有限付费分开、首次失败退出码明确；清单合法不等于业务已通过。

## AG07-01 事件与有界sink

新增Agent：`operations/__init__.py`、`operations/events.py`、`operations/sink.py`、`config/operations.yaml`、`tests/test_ag07_events.py`、`tests/test_ag07_sink.py`。修改 `service/telemetry.py`、`service/app.py`。Phone新增 `backend/src/main/java/com/fandazi/feature/agent/AgentOperationsEvent.java`、`AgentOperationsSink.java`；修改同目录 `AgentStageMetrics.java`；新增测试 `AgentOperationsEventTest.java`、`AgentOperationsSinkTest.java`。

接口：`make_event(event_type: str, fields: dict) -> dict`只输出版本化白名单；`BoundedEventSink.offer(event: dict) -> bool`不阻塞业务；`snapshot() -> dict`报告队列/丢弃/错误；`close(timeout_seconds: float) -> dict`不隐瞒未落盘数。Java提供同等语义；现Recorder的`span()`/异常传播保持。

- [x] 01.1 编写O01字段白名单、身份证明不入日志、随机键跨语言摘要一致与O02满队列/坏时钟/不可写/关闭断言，先核指定失败。
- [x] 01.2 实现事件与sink；256条/8KiB/20MiB×5/7天/关闭5秒是硬边界，不允许默认无界。挂在应用生命周期，不因导入模块就启动后台线程。
- [x] 01.3 在原span计数基础添加事件；原AG06观测格式如有消费者，提供显式版本适配，不能改变旧指标分母。事件元信息错误不吞业务异常。
- [x] 01.4 运行定向组与现有StageMetrics/相关资源关闭测试，核自有临时文件轮换及拒绝记录；读写失败只造本任务目录条件，不能改系统磁盘。

关键断言示例（完整fixture在测试模块定义）：

```python
assert make_event("tool_result", {"status": "unknown", "password": "合成哨兵"}).get("password") is None
assert sink.offer(valid_event) is False  # 测试先填满固定容量
assert sink.snapshot()["dropped"] == 1
assert original_exception is captured_exception  # 观测失败不替换业务异常
```

运行 `python -m pytest tests/test_ag07_events.py tests/test_ag07_sink.py -q`；Phone `mvn -f backend/pom.xml -Dtest=AgentOperationsEventTest,AgentOperationsSinkTest,AgentStageMetricsTest test`。通过：无敏感哨兵、跨语言字段/摘要一致、业务执行恰好一次、所有自有线程/文件句柄归还；证据JUnit/安全样本/资源快照。这里验证正确性，不跑吞吐矩阵。

## AG07-02 汇总、真实调用与费用

新增 `operations/aggregate.py`、`operations/pricing.py`、`tests/test_ag07_aggregation.py`、`tests/test_ag07_usage.py`。修改Agent `service/spring_client.py`、`service/providers.py`、`intent/decision_model_adapter.py`、`service/task_inputs.py`；意图其余模型适配器仅在00识别实际usage丢失消费者后定向纳入。Phone修改 `AgentTaskGatewayService.java`、`AgentGatewayService.java`、`InternalV2ToolController.java`及实际工具执行出口，新增 `AgentOperationsCorrelationTest.java`。

接口：`aggregate(records, window_start, window_end) -> dict`返回计数、桶、覆盖率/缺口；`estimate_cost(usage, model_id, at, price_book) -> dict`返回Decimal金额或unknown及原因。跨服务request→run桥接只用受信身份；指标不读取完整数据库result_json。

- [x] 02.1 核请求/工具/费用各自分母、输入集合完整性及跨轮事实不串用；不可评单列，不按费用0或成功处理。建O03 usage三态（可知、未知、非法）、Decimal精算、缓存口径、重复eventId、重放receipt和模型异常计数断言；数据全部合成，价格明确标synthetic。
- [x] 02.2 在SDK响应尚未被领域转换前提取usage；维持原ModelDecision/路由顺序/提示词/失败兜底。区分逻辑调用和真实可观察HTTP发送；不能观察时明确unknown。
- [x] 02.3 工具span覆盖完整HTTP/业务解析结果，attempt/committed/denied/unknown互不混淆；只有Spring权威receipt可判写提交。汇总器增量读取、轮换和重启不重计，不输出高基数原ID标签。
- [x] 02.4 运行本任务定向及已有意图/工具契约/异常消费者；一组专属双服务+真实MySQL原生工具回执验证，固定1成功写+1同键回读+1拒绝+1模拟丢响应核原键，模型0。所有业务对象合成；新写最多1，不为观察重复下单。

示例判定：`assert estimate_cost(None, "known", at, prices)["status"] == "unknown"`；输入1000/output500、合成每百万2/4元应得0.004元；同eventId读两次计数1。命令 `python -m pytest tests/test_ag07_aggregation.py tests/test_ag07_usage.py tests/test_spring_tool_client.py tests/test_decision_model_adapter.py -q`；Phone定向Correlation组。证据四类回执/计数/跨服务键及业务表唯一行；不知道账单金额必须明确。

## AG07-03 查询与告警闭环

新增 `operations/alerts.py`、`operations/cli.py`、`tests/test_ag07_alerts.py`、`tests/test_ag07_cli.py`；Phone新增 `AgentOperationsHealth.java`/`AgentOperationsHealthTest.java`，扩展受控本地状态输出；不新开公网HTTP管理口。

接口：`AlertEngine.evaluate(snapshot, now) -> list[dict]`、`ack(alert_id, reason_code) -> dict`；CLI支持规格六类查询/有限watch。规则固定A01—A06；普通重复冷却300秒，采集10秒、无数据30秒，状态open/acknowledged/resolved独立。

- [x] 03.1 使用确定性时钟测试六类触发、边界前后、告警升级、冷却、ack不等于恢复、进程恢复及monitor_degraded；确认不触发模型/业务工具。
- [x] 03.2 实现有界文件读取、局部持久状态和CLI；费用缺价格或预算时输出unknown/未配置；outbox未开启消费时不虚报生产投递故障。
- [x] 03.3 真正启动本机watch一次最多6轮，注入合成事件文件→看见告警→CLI确认→恢复信号→resolved，重启读取相同状态；本步骤模型0、数据库0。
- [x] 03.4 核输出目录ACL、路径逃逸/超大行/损坏JSON拒绝，结构化stderr不泄漏内容。远端通知默认未启用，不以本机日志证明短信/飞书送达。

命令 `python -m pytest tests/test_ag07_alerts.py tests/test_ag07_cli.py -q`；`python -m operations.cli watch --root <本次私有目录> --max-polls 6`（启动前根目录必须写入freeze）。通过：六规则与生命周期精确、无漏报指定输入、重复读取不刷屏、无数据不健康、真实控制台可见；证据告警状态文件和脱敏命令回执。

## AG07-04 入口准入及控制保留

新增Agent `service/admission.py`、`tests/test_ag07_admission.py`；修改 `service/app.py`、`service/task_inputs.py`、`service/streaming.py`必要接线。Phone新增 `AgentRequestAdmission.java`、`AgentRequestAdmissionTest.java`，修改 `AgentGatewayController.java`、`AgentTaskInputController.java`及其实际管理调用入口，保护账号/目标校验顺序。

接口：普通许可`acquire(scope_key) -> lease`/退出归还；管理许可独立。Python异步context manager和Java AutoCloseable语义一致，许可与真实在途绑定。规格默认普通1/等待0、每账号6每分钟/突发2、控制2；不改变AG06原池/只读/流上限。

- [x] 04.1 红测固定三同时请求：首请求占用、第二普通拒绝、原键核查成功；外账号不消耗目标额度，重复/取消/异常归还不多减。同步SDK未退出时不可提前归零。
- [x] 04.2 在鉴权归属后、模型与状态写前接入准入；拒绝无turn/run/新grant/领域写，使用现有UI识别错误信封；控制保留授权但不让管理路径绕过业务额度发起新写。
- [x] 04.3 定向复验工作生命周期、流关闭与只读调度消费者；真实双服务固定小场景确认429/503、Retry-After和原业务账本不变，模型0，最多3同时请求。
- [x] 04.4 记录限制只对当前进程与核定单实例部署成立，不宣称分布式限流、并发容量或资源压力矩阵通过。

命令 `python -m pytest tests/test_ag07_admission.py tests/test_ag06_resource_lifecycle.py tests/test_ag06_stream_connection.py tests/test_ag06_control_lock_scope.py -q`；Phone定向 `AgentRequestAdmissionTest`及受影响网关/权限消费者。通过：普通饱和时合法控制可完成、非法目标仍拒绝、许可最终归还、无重复写。证据发送计数、HTTP结果、数据库前后摘要、关闭资源快照。

## AG07-05 配对发布、维护和回退

Phone新增 `scripts/operations/release.py`、`scripts/operations/targets.py`、`scripts/operations/test_release.py`、`docker-compose.operations.yml`、`docs/operations/release-runbook.md`；复用 `docker-compose.integration.yml`与现有Dockerfile。Agent新增 `operations/release_manifest.py`及测试用于核双仓/契约身份；实际维护接线只影响受信部署状态，不能用聊天字段开关。

接口：`validate_release(manifest, observed) -> checked_plan`、`apply_release(checked_plan, maintenance_receipt) -> receipt`、`rollback_release(checked_plan, previous_release) -> receipt`。默认为只验证；实际动作必须有明确自有目标/产物SHA/操作类型，非交互执行不能默认启动生产目标。

- [x] 05.1 固定release manifest schema、协议/开关组合、迁移清单、归属与回退AG06基线；坏SHA/未知路径/他人容器/replicas>1/版本不兼容在变更前拒绝。
- [x] 05.2 实现维护状态：先阻止新普通请求，排空并核写结果，再停写者；120秒到期标未完成，不能kill后自称安全。只读目录与原receipt在正常排空期仍可用。
- [x] 05.3 复用隔离产物，部署入口不用动态工作区build。把旧集成说明的健康路径/2.0默认/SQL范围与当前实际代码对齐，保持旧文件作为历史参考。
- [x] 05.4 离线驱动测试一次预演所有失败守卫；真实前进/回退放到07统一演练，不在此阶段重复切服务。

命令 `python -m pytest tests/test_ag07_release_manifest.py -q` 与Phone `python -m pytest scripts/operations/test_release.py -q`。断言 `validate_release(tampered, observed)`抛稳定身份错误且变更调用0；排空超时同样变更0。通过：脚本安全可预览、兼容配置不复活确认/不重置预算、未运行真实回退明确标待07。

## AG07-06 双来源备份与隔离恢复

Phone新增 `scripts/operations/backup.py`、`scripts/operations/test_backup.py`、`docs/operations/backup-restore-runbook.md`；提取/复用 `scripts/verify_retirement_backup.py`算法，旧Task11入口行为保持，不将默认旧库用于新演练。

接口：`create_backup_set(checked_targets, maintenance_receipt, output) -> manifest`；`restore_backup_set(manifest, fresh_targets) -> verification`。来源显式双库/Chroma，目标必须新且私有；manifest要验证hash/版本/停写水位，不能拿两份不同时刻dump拼接。

- [x] 06.1 红测来源=目标、已有目标、缺库、错误镜像/容器、无停写回执、跨库水位改变、dump重定向、凭据argv、损坏dump及Chroma缺失；错误时不得恢复/开放业务。
- [x] 06.2 实现停写一致性、两库dump、索引快照、逐表/Flyway/业务键摘要和源前后核对；数据库口令通过私有配置或环境传入，不进入命令日志。禁止隐式重新生成embedding。
- [x] 06.3 失败清理只按本次创建台账，保留源及有效备份；加容量/时长上限和当前环境预检，资源不足标阻塞，不缩减应恢复的数据范围。
- [x] 06.4 运行离线失败守卫与真实本地归档校验；真正两库恢复只在07执行一次，避免“脚本验一次、最终又全备一次”。

命令 `python -m pytest scripts/operations/test_backup.py -q`。关键断言：缺第二库或停写证明→restore调用0；篡改摘要→对外开放0；恢复结果缺原执行键→失败不是跳过。通过：脚本可安全运行与证据完整，真实恢复能力仍待07，不预写通过。

## AG07-07 五类有界真实故障与恢复

新增Agent `tests/ag07_operations/`包及 `test_live_operations.py`、`driver.py`；Phone沿用上述部署/备份驱动。静态fixture放 `tests/fixtures/ag07-operations/`；私有环境参数仅runtime。父/子进程网络/模型守卫、命令超时和资源所有权在任何动作前自测。

- [x] 07.1 固定一次完整自有环境，记录所有容器ID/卷/端口/进程、数据库/配置/产物SHA和初态；先小冒烟，无需调用真实模型。两库/索引数据全合成，旧AG06库不改。
- [x] 07.2 F01依赖不可达：仅切自有测试代理至拒绝端点或停自有Agent；真实业务正确报错、指标归类、A02可见；恢复原地址/进程后原键回读，禁止重做未知写。
- [x] 07.3 F02一次Agent重启：先有持久停点，停止/恢复本进程；任务/限制/纠正/恢复预算保留，旧确认拒绝。F03入口饱和：最多3同时请求，普通拒绝与控制通路通过，资源归还。
- [x] 07.4 F04一次候选→AG06配对回退→候选恢复：沿05清单执行，旧版本读取新持久状态/原receipt，既有业务计数不增加、无确认复活。原版本不是更早未修B01/B02版本。
- [x] 07.5 F05一次backupSet（双库+索引）和完整隔离恢复：dump/恢复/源前后摘要、跨库身份和真实服务原键回读全部通过；一份损坏副本只做离线拒绝，不重复备份。
- [x] 07.6 每类记录故障前基线、故障中保护断言、恢复后原键业务回读；健康200不代替业务恢复，失败按产品/驱动/环境/外部依赖/证据不足分类。每类核告警触发→定位→处理→恢复→ack记录，只有恢复条件满足才resolved。恢复自有资源初态，输出未清理原因/保留用途；其他任务资源不改。

执行入口拟为 `python -m tests.ag07_operations.driver --manifest <冻结manifest> --suite bounded-live --model-sends 0`；实现00时将参数登记进实际策略。总上限：F01—F05各一次注入/恢复，整轮30分钟；超时先保留现场并按归属恢复，不循环重试。真实恢复数据一致性优先，超时未完成不能计通过。证据每类独立result/JUnit、日志/业务回读/版本/资源恢复，不把无异常日志当恢复成功。

## AG07-08 最小真实模型与受影响UI

新增 `tests/ag07_operations/test_live_journey.py` 和Phone `.maestro/ag07/operations-smoke.yaml`。复用同源码Android APK及AG03/AG04确认设计；只有实际修改Android时才新构建并清理被替代APK。

- [ ] 08.1 冻结精确候选、当前服务版本/数据、已有证据适用表与最多40发送的共享计数拦截。健康/指标/告警查询禁止调用模型。首请求同时是实际供应商冒烟，失败终止后续付费步骤。
- [ ] 08.2 一个≤8输入旅程：已验查询/指代→准备→纠正→旧确认禁用→新确认单次写→饱和提示/控制→恢复或原键核查。最多一新订单确认，其余用既有合成前置，不执行实际支付/外部退款。
- [ ] 08.3 核追踪跨Spring/Agent/工具/业务账本；usage真实存在就记录，不存在则unknown；核已观测客户端发送总数和计费可见性，不用输出文本推断工具成功。
- [ ] 08.4 固定账号/对象/种子状态、设备和APK；同版本前置只有摘要匹配才复用，真实网络/业务结果不得以录制响应替代。Maestro真实操作并逐屏看当前截图，对照原确认设计的禁用、核查、取消/恢复及错误提示。没有新增视觉需求则不另开Figma设计；功能不可点击/越权/错误确认必阻塞。
- [ ] 08.5 核原订单唯一、同键回执、其他副作用0、资源退出和自有转发清理；不再重复07恢复或质量矩阵。运行故障先区分驱动/环境/产品，修复仅补受影响范围，新轮付费预算不得自动继承。

通过：真实协议/模型/工具及账本关联成立、观测不改变业务、控制不被限流锁死、UI安全边界全部满足。不是仅使用模拟结果交付；未运行此步必须标待验。截图、业务安全摘要、发送预算回执、Maestro退出码及原APK SHA保留。

## AG07-09 最终适用回归与交付

新增 `docs/verification/ag07-operations/acceptance.md`、最终evidence-index/failure-ledger/final-summary，更新本计划及项目共享TODO。原AG06冻结规格、失败和债务保留，不重写旧状态。

- [ ] 09.1 核冻结caseId集合无遗漏、无重复及处置互斥，别名/尝试不增加通过场景数；保留首次失败，不适用必须有范围依据。汇总逐需求→代码/依赖→新运行/复用/失效/未运行表。特别核B01/B02、权限/对象、确认失效、未知写/原键唯一、持久恢复和关闭消费者；旧229/2531或安全44不能整包直接复用。
- [ ] 09.2 稳定候选执行一次最终适用Agent/Spring测试集合；枚举测试ID，排除线上/性能仅按明确适用依据，不用不加选择的pytest tests触发矩阵。必需实时项不允许门控skip充数；失败第一次保留。
- [ ] 09.3 不修改开发工作区的精确源码隔离构建：Agent受控Python编译＋适用测试，Phone Maven package及必要实际产物，Android资产无变按版本证明复用；配置/依赖/产物SHA对应候选，禁止PYTHONPATH回退旧开发目录。
- [ ] 09.4 已在相同稳定候选通过的07/08不重跑；若之后改公共源码，按调用关系只重验失效组合。费用/性能债务不靠日志采样核销。
- [ ] 09.5 核日志/临时备份私有权限、自有进程/容器/文件句柄收回、旧APK按用途清理、证据无密钥。输出需求、代码质量、实际运行、可构建性与性能边界五维结论。
- [ ] 09.6 先报告后勾计划，精确提交与共享；本轮计划仅文档时只验链接/范围/冻结摘要。实现完成后询问AG07当次双master合入，历史AG06合并同意不可沿用。

最终通过条件：O01—O09、F01—F05全部适用必需项有有效证据，硬阻塞0；单实例/本机告警/维护备份/性能未定限制明确。未经真实运行的远端通知、无人值守生产、并发容量和RPO/RTO均不得宣称已达成。


## AG07-00-E 规则、来源与复用冻结（先于01继续）

文件：新增 tests/fixtures/ag07-operations/evaluation-contract-r2.yaml、tests/ag07_operations/evaluation_contract.py、tests/test_ag07_evaluation_contract.py、config/acceptance/tasks/ag07-operations-r2.yaml；新增冻结计划副本implementation-frozen-r2.md。原r1 manifest/policy/cases保持原字节；r2 manifest继承全部必需动作，新增r2文件摘要；O01/O03/O04/O08/O09执行器必须纳入相应E组，不能只读旧r1 manifest闭合r2。

- [x] 00-E.1 固定8个development引用：GRE-DIR-01、ORD-COR-02、ORD-CON-03、ORD-FIX-04、ORD-AMB-05、ORD-CTL-06、REF-CTL-06、MIX-DIR-01，均使用完整AG05-DEV前缀。逐项记录原金标作用域、规则适用和证据缺口，不复制任务正文，不消费heldout新运行。8是资产映射数，不是在线样本预算。
- [x] 00-E.2 固定E01—E07、来源权限、三种结果边界、零分母/未知展示、8/64/256/2MiB/32MiB读取上限；task-ID、turn-ID、scope与权威来源不得缺失。使用load_contract(root)->dict、validate_contract(root,contract)->dict返回task/turn/rubric计数，不返回Agent成功率。
- [x] 00-E.3 先建立缺模块指定断言，补实现；测试删规则、重复case、未知case、改数据摘要、放宽预算、缺证据映射、改目标语义均拒绝。运行 python -m pytest tests/test_ag07_evaluation_contract.py tests/test_ag07_acceptance_manifest.py -q。隔离候选再跑同组并受控compileall；不重跑00无关环境/资源创建。
- [x] 00-E.4 校验r1原SHA不变，r2全文与计划副本/契约/原任务集SHA登记。独立报告ag07-00-e.md先写，退出冻结后更新本页并精确提交；同步共享。原00报告完整保留。

核心断言：
```python
assert summary["tasks"] == 8 and summary["rubrics"] == 7
with pytest.raises(ValueError, match="rubric_set_mismatch"):
    validate_contract(root, missing_safety_rule)
assert summary["model_calls"] == 0  # 入口仅读文件，无业务或模型执行能力
```

## AG07-01 r2补充：最小关联及既有工作接续

01.1—01.4仍必须完成。Agent已有events/sink/telemetry/lifespan及62项开发结果、Java6项红测保留。开始先比对现WIP与报告，不删除重做。实际工具/业务关联在02接入；01只保证关联字段和资源生命期正确。

- [x] 01.E1 核随机request→run/epoch/execution与受控证据包可关联；不可得字段null/coverage，不从哈希逆推业务事实。不将Task正文、expected、完整工具结果加入普通日志。
- [x] 01.E2 完成Java对等契约与StageMetrics/lifecycle接线；补跨语言SHA固定向量、两个应用实例隔离、停机/取消/sink错误不吞业务异常。此前Python定向通过只复用未改变的行为；修改共享接线必须重验其消费者。
- [x] 01.E3 对稳定双仓候选分别隔离构建与定向测试，报告01完整范围后精确提交。01追踪资源自测不标O01真实业务组合完成，后续02/08继续核实。

## AG07-02-E 受控证据适配及客观评分

顺序为02.1—02.3开发、02-E.1—02-E.3离线守卫、02.4真实组合、02-E.4归档；这样真实链路只执行一次就能产生可验证证据。

新增 tests/ag07_operations/evaluation_evidence.py、evaluation_report.py（评分核心）、evaluation_cli.py 和 tests/test_ag07_evaluation_evidence.py、tests/test_ag07_evaluation_scoring.py。复用 tests/ag05_evaluation/scoring.py:score_run 与 adapters.py:adapt_capture，不改原金标及评分阈值。

- [x] 02-E.1 建立typed envelope字典契约，load_verified_bundle(root, manifest, trusted_index)->dict仅从指定本地根读取；可信索引由验收控制端冻结，含path/sha/source/task/trial/turn/business_scope。输入自己的source或hash不能自证。逐文件预算先扣再读，拒绝链接/重解析点、超限和身份混用；证据错误抛固定ValueError码。
- [x] 02-E.2 to_ag05_observations(verified)->dict只复制真实观察；缺必需事实返回not_judgeable，不能以expected补齐。evaluate_tasks(tasks,verified)->dict调用score_run，同时保留独立goal/handling/safety判定。映射字段和来源逐条记录，不能用仅结构validate_evaluation_evidence返回值当质量结论。
- [x] 02-E.3 离线注入漏检查、篡改真实文件、索引不可信、错轮/对象receipt、假成功无业务行、旧确认可写、缺来源、重复样本、未知/零分母等反例；socket与模型/业务发送入口设拒绝哨兵。运行 python -m pytest tests/test_ag07_evaluation_evidence.py tests/test_ag07_evaluation_scoring.py -q 及实际依赖AG05评分/适配测试；反例必须触发相应原因码。
- [x] 02-E.4 在原02.4一次真实工具组合内产出源索引与证据包；先验完整性再离线评分，核同键唯一行。证据不足则记录缺口并修采集器，不能为评分再下单。记录same-run、model0、业务写预算和原结果。

断言示例：
```python
with pytest.raises(ValueError, match="evidence_hash_mismatch"):
    load_verified_bundle(root, tampered_manifest, trusted_index)
assert waiting_result["handling"] == "passed"
assert waiting_result["goal"] == "not_achieved"
assert missing_result["status"] == "not_judgeable"
assert synthetic_result["provenance"] == "synthetic_grader_test"
```

## AG07-03-E 四栏报告、归因与回归入口

顺序：03.1—03.2 → 03-E.1/2 → 原03.3/4同次CLI检验 → 03-E.3。
新增 tests/test_ag07_evaluation_report.py；扩展evaluation_report.py:render_report与evaluation_cli，普通operations.cli仅链接脱敏摘要，不直接公开私有证据正文。

- [x] 03-E.1 固定结果/过程/效率/安全四栏；每一结论关联Task/Trial/规则/来源。显示总样本、可判、未知、未运行、复用；零分母rate=null/no_data；同任务多trial各自保留，不拼接成功分子。不提供混淆安全的综合总分。
- [x] 03-E.2 案例台账包含来源、规则、归因及证据、修复范围、回归命令、处置；product/tool/environment/driver/standard/unknown固定枚举。原Good/Bad证据引用经过核对才登记；无原因就unknown，未执行回归标unrun。
- [x] 03-E.3 命令 python -m tests.ag07_operations.evaluation_cli score --bundle <冻结私有包> --trusted-index <受信索引> --output <私有结果>；先validate再score，所有路径有界，禁止execute/live。真实本地CLI一次读合成已知正反包，核四栏与case定位且model/business0。复用03原watch不另跑故障。

通过：所有硬安全失败独立可见，正确等待不等于业务完成、未知不计成功、历史不冒当前、新结果可定位到可执行回归入口；评分器自测成功不产生Agent质量“全通过”。

## AG07-04—07 r2证据复用约束

原全部步骤与通过条件不变。04准入、05回退、06备份、07演练的同次业务回执/持久状态/资源结果进入可信索引。新增07.E：每类演练产出Task/Trial/Trace/Outcome引用与失败归因，缺项标缺项；不用新增一次演练补报告格式。源码修复影响真实行为时仍按原流程重新冻结和定向复验，不借复用省必需场景。

## AG07-08 r2同次Agent效果验证

08.1冻结时额外固定每个输入的预期规则和业务终态、来源映射、实际采集器与可信索引，不把AG058项资产盘点当这次8输入旅程。08.2—08.5原预算和安全/UI要求不变。
- [ ] 08.E1 同一旅程生成Task/Trial/Trace/Outcome，包含实际用户纠正、旧确认拒绝、一次唯一写与权威结果；每输入的E规则事前明确，无模型金标提示注入。
- [ ] 08.E2 离线用同次证据评分并人工核关键业务回读/截图，区分任务处理与完成、真实模型与固定夹具；不能估计十三意图总体质量、随机稳定性或业务转化。
- [ ] 08.E3 主观质量只交少量可审阅正反例与规则草案，不宣称人人/人机一致，不调用模型Judge。此部分不替代任何功能安全验收。

## AG07-09 r2最终闭合

09.1—09.6原要求保留；新增09.E：核E-contract/E-adapter/E-grader/E-report/E-case/E-live/E-final逐项及O/F索引，工程验收与Agent效果范围分栏；同版本同输入的08重评分不重发模型，未证明适用的历史结果不可复用。
交付结论必须说明实际覆盖及未覆盖质量维度、性能债务延期和运行限制。案例回归选择清单可追到精确源码；构建产物必须覆盖新增配置与评测模块，两个master合入仍需当次明确授权。

## r2初始复用/失效选择表

|范围|原证据/身份|本轮处置|原因与必须新增|
|---|---|---|---|
|00原范围守卫/资源声明|0af5a46，ag07-00.md及40组合/隔离40|限定复用|r1原输入保持；r2契约新增，不由旧测试覆盖|
|AG05语料与评分器|development/calibration/heldout文件SHA及原源版本|复用实现/金标；质量结论未判适用|仅引用development8项，未运行旧240矩阵；适配变化补评分器消费者|
|01Python开发62|ag07-01/lifecycle-green.xml及当时WIP|开发参考，不标任务完成|新接线/资源修复改变消费者需定向重验|
|01Java红测6|Phone ag07-01/red|保留历史失败|仍须产品实现/绿测/隔离构建|
|AG06安全/UI/构建|原final-evidence-index|逐项待选择|准入/SDK/关闭/工具出口受影响不能整包复用|
|AG06性能PD01—05|原门槛/历史失败/未测范围|授权延期|不跑性能、不以局部指标核销|

## r2动态进度

- 用户已明确批准r2并持续顺序执行；本页是动态计划，tests/fixtures/ag07-operations/implementation-frozen-r2.md保存初始完整副本，规格与副本不改状态。
- 00原范围完成；00-E开始；01原WIP保留，02—09尚未验收。按00-E→01→02/02-E→03/03-E→04→05→06→07→08→09执行。

## 00-E完成检查点

[00-E报告](../../verification/ag07-operations/ag07-00-e.md)：新增契约8任务/10轮/7规则，23定向与精确隔离23通过，编译及r2 CLI退出0；模型/业务写0。r1原范围保留，开始接续01。原计划副本与规格全文保持冻结，本页状态更新不回填金标。

## 01完成检查点

[01报告](../../verification/ag07-operations/ag07-01.md)：Agent精确隔离66通过/编译0、Phone精确隔离60通过/package0；新事件类型、空闲保留期及作用域守卫修复均保留首失败。r2/00-E已提交f723f78。现在继续02/02-E，真实模型/UI/故障演练尚未执行，不能将01测试当完整Agent效果通过。

## 02开发检查点

见[02报告](../../verification/ag07-operations/ag07-02.md)：费用/汇总首批25通过，SDK及可观察发送/相关资源组合83通过；均模型0。当前未冻结02、未勾完整步骤，继续工具权威关联、增量消费及02-E守卫，真实组合尚未运行。

## 02完成检查点

见[02报告](../../verification/ag07-operations/ag07-02.md)：单轮真实工具订单增量1、原执行唯一行1、原键回读与跨账号拒绝通过、模型0及资源归还。221/100隔离基础按影响范围复用，8守卫/58评分修复隔离通过；完整Agent质量缺事实明确不可评，08仍必需。下一步03/03-E，不重跑业务或矩阵。

## 03完成检查点

[03报告](../../verification/ag07-operations/ag07-03.md)：六类告警与四栏/案例CLI通过，119/22隔离、ACL修复相关15通过；唯一6轮watch和2个正反包真实CLI退出0，模型/数据库/业务写0。进入04，04—09真实必需项未豁免。


## 04开发检查点

03已精确提交Agent727c58c/Phone108b786。[04报告](../../verification/ag07-operations/ag07-04.md)记录6指定红测及6开发绿测，入口接线和真实双服务尚待完成；不勾任务通过。

## 04完成检查点

[04报告](../../verification/ag07-operations/ag07-04.md)：一次8HTTP/峰值3在途、拒绝不写库/原键仍可读、模型0、资源回收通过；Agent123项隔离有效证据及2项锁范围限定复用，Spring88/构建0。两次前置驱动失败保留、业务发送0；通过轮不再重复。下一步05配对发布/维护/回退驱动，真实发布与恢复统一待07。


AG07-05：Agent15/Phone23隔离通过，双源码编译0；树0a07c86a/045609f4。真实切换按原计划待07，模型0。见[05报告](../../verification/ag07-operations/ag07-05.md)。

AG07-06：51唯一隔离项通过，Windows私有归档首次ACL失败及修复后真实文件验证通过；模型/DB0，真实恢复待07。见[06报告](../../verification/ag07-operations/ag07-06.md)。


07检查点：F01定向补验通过；F02准备来源完整性失败，真实重启及F04/F05未运行。已停自有资源，先做3SELECT只读定位；详见07独立报告。


07：F02来源缺口已定位为夹具跳过历史关联；测试装配修复22 Python/2 Java通过，定向复验与首次F04/F05待新冻结。历史失败/原数据不改判，详见07报告。


07完成检查点：F01/F02分项复验与限定复用、F03原真实动作及新告警回放、F04配对回退、F05唯一双库索引恢复均闭合，07.E索引已核。所有首次失败保留，不改原失败整轮；r7模型0/业务摘要不变/资源归还。见[07报告](../../verification/ag07-operations/ag07-07.md)。正式动作入口尚待09接入，下一步08真实模型/UI前置，性能债务延期。
