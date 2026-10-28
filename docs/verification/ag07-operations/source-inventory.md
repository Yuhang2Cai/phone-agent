# AG07 最新双仓只读能力盘点

2026-10-27，基线Agent master `8eed53203441a4c72a0ff624a094bad8d871a529` / Phone master `e6877e2fe838ac20c7f2ac2eeaae0f09883b2a34`。本次只读代码和版本，模型0、数据库查询0、性能采样0、部署动作0。具体能力与最小缺口见[技术方案](../../superpowers/specs/2026-10-27-ag07-operations-design-r1.md)。

核实的重要边界：

1. Recorder/StageMetrics已存在，不能再声称“尚无追踪”。现Agent文件sink同步追加、没有轮换；两端只做阶段关联，尚未闭合request→run→工具回执的运维查询。
2. usage部分已采集，DecisionModelAdapter返回领域ModelDecision未携带供应商usage；观察逻辑调用不能替代实际HTTP发送和账单。
3. WorkLifecycle是关闭时的请求所有权与收束，不是运行时正常请求全局准入；只读/流队列已有硬上限，应复用而不扩大。
4. AgentTaskAdmissionController服务于持久轮次许可/完成核查，不是请求限流器；不得因名称含Admission而误认为AG07限流已实现。
5. outbox Dispatcher为显式deliverOnce，方法存在不能证明运行环境周期消费者已启用。历史63535积压只能按已证上下文说明，AG07不自动清空/补投。
6. 集成Compose是历史2.0部署，Agent初始化只挂001/002；当前源码已有008及后续领域表建立路径，Spring迁移到V2026101801。本计划要求清点实际完整schema，不把“最大版本号”当全部迁移证明。旧部署文档写/internal/v1/health，实际服务入口为/internal/v2/health，须在新运维手册中纠正。
7. 旧备份脚本真实做dump/新库恢复/摘要，不能重复建设算法；但默认mysql-server/3306和同容器两库不适合当前显式双来源。源码存在不代表当前双库一致性与灾备演练已通过。
8. 本轮检索backend/pom.xml和src/main未发现Actuator/Micrometer/Prometheus接入声明；结论只限该范围，不声称用户机器没有监控平台。本轮不引入平台依赖。

下表是从Git master对象直接读取的身份，字节SHA保存在[source-inventory.json](source-inventory.json)。只读盘点中路径探索曾命中不存在的service/work_lifecycle.py、config/goals.yaml及PowerShell通配符路径错误，已按实际文件更正为service/streaming.py、config/goal_recovery.yaml；这些命令错误不算测试失败或功能通过证据。

|仓库|实际路径|Git对象|
|---|---|---|
|Agent|`service/telemetry.py`|`d5b188f4528c1ccbc411360b37cf876125802519`|
|Agent|`service/runtime_resources.py`|`772d0e836c81f48f6d363e70f96417470ff98de4`|
|Agent|`service/streaming.py`|`1f90abb4a07e5ab570c65207e470498f565a2f1c`|
|Agent|`service/app.py`|`24746512a7128f0b7890d38ee7269964c1f346c0`|
|Agent|`service/spring_client.py`|`e8461e7e0ed11d546b1b958883ff07e4412ce8a3`|
|Agent|`service/providers.py`|`3da3f9902a09fdf254a8bc542e3428a12c766e23`|
|Agent|`service/task_inputs.py`|`b88905740014ab9add0ce31d118c5c432b8d74de`|
|Agent|`service/task_controls.py`|`39b833da521397e1e54838b36a41c3b5ae05d908`|
|Agent|`service/engine.py`|`313c9e54d1146a72bbce2ae367493e7bbd8ab092`|
|Agent|`service/goal_runtime.py`|`25816895d5227e47d39bf189286037eb0a2261aa`|
|Agent|`conversation/task_store.py`|`af516d0ba750834746ee5aab832ab8286476c02a`|
|Agent|`intent/decision_model_adapter.py`|`ebf4bda7d12b11156c47e5a83ce1dd0c0a406293`|
|Agent|`intent/models.py`|`fa2af87ee7446682011af76fa1e7665c8c478ae4`|
|Agent|`config/runtime_resources.yaml`|`588426381c1ae88177deaba76025da84cca871ff`|
|Agent|`config/task_lifecycle.yaml`|`071aa5a0e93c0c3fc3a84d091e8c8e7abe63b5f2`|
|Agent|`config/conversation_context.yaml`|`9bc3e255ac1e6a4e988dd31fba241429ebb065b7`|
|Agent|`config/goal_recovery.yaml`|`fd14addc0ffb2788fb8f3d5d804b095e6e4c5933`|
|Agent|`config/contracts/source.sha256`|`3d33be5b4bbcf66c9288b70b59c5449915544d68`|
|Agent|`config/contracts/manifest.yaml`|`3afad6bea097bebb2c21b475ba43336254c8a258`|
|Agent|`database/schema/008_task_source_evidence.sql`|`1a97d68f69e12ebed364222d912f51df5f1d5f45`|
|Agent|`docs/verification/acceptance-workflow.md`|`589a783dbe3e8de82de53ec714d3d18c2b0ddb43`|
|Agent|`docs/verification/ag06-latency-resources/usability-close/performance-debt.md`|`da6711f302676b3bbec11b9edd1f64fc62313e38`|
|Phone|`backend/src/main/java/com/fandazi/feature/agent/AgentStageMetrics.java`|`5220a52e5077fbf1edbbc1da4bbef2d3acc580ea`|
|Phone|`backend/src/main/java/com/fandazi/feature/agent/AgentTaskGatewayService.java`|`be4fc03103593f5cfdba71767251d7e9fd6c02c1`|
|Phone|`backend/src/main/java/com/fandazi/feature/agent/AgentGatewayController.java`|`a1b85afd1d2d81f633bc9993349666421d418fe7`|
|Phone|`backend/src/main/java/com/fandazi/feature/agent/AgentTaskInputController.java`|`e6617a7fd87629cf64f8dbea5a54fe2631eecf70`|
|Phone|`backend/src/main/java/com/fandazi/feature/agent/AgentTaskAdmissionController.java`|`035cb9289afb1b08b323a967ca0c7f7b34627895`|
|Phone|`backend/src/main/java/com/fandazi/feature/agent/AgentTaskEventOutbox.java`|`18834ea4f60edf640e7b367a274befe866160611`|
|Phone|`backend/src/main/java/com/fandazi/feature/agent/AgentTaskEventDispatcher.java`|`0c8fe83c5fdf99b499546e5b57104aaf1e2cb977`|
|Phone|`backend/src/main/java/com/fandazi/feature/agent/AgentToolExecution.java`|`030afe94411be0cddade240519a075e4e0264f42`|
|Phone|`backend/src/main/java/com/fandazi/feature/agent/AgentToolReceiptController.java`|`99bc04bf0a477d16c534726718988043c853f955`|
|Phone|`backend/src/main/java/com/fandazi/feature/agent/AgentTaskTurnService.java`|`f3bade5223891a8fb8255ac74ee5463d0cd391a1`|
|Phone|`backend/src/main/java/com/fandazi/feature/agent/ConfirmationGrantService.java`|`44778b0ba94b6115c28c0bbcd0b3c14625265efd`|
|Phone|`backend/src/main/java/com/fandazi/feature/agent/AgentSessionService.java`|`859f8e27a05f2e2240a68aceb2679e05b1260e1c`|
|Phone|`backend/src/main/java/com/fandazi/core/security/SecurityConfiguration.java`|`412383fbdc927559f9c5c01d33070a996f1ef7a3`|
|Phone|`backend/src/main/resources/application.yml`|`d3045ba378d0587f90b812a93475b5d2121b3281`|
|Phone|`backend/src/main/resources/db/migration/V2026101801__expand_agent_tool_execution_result.sql`|`f54b0c5332a8366575338d368c33bbf8e16d1f60`|
|Phone|`backend/pom.xml`|`58bbd93c4b26438d72c90c17782a98a9937b478c`|
|Phone|`docker-compose.integration.yml`|`26149e690c713392afd64363a536ae80f60209a5`|
|Phone|`backend/Dockerfile`|`62f1b50acdc44680e700cf7acaefca320480a7e7`|
|Phone|`scripts/verify_retirement_backup.py`|`19585513fdd81774de12daa2c49d018ee52accf6`|
|Phone|`docs/integration-deployment.md`|`b778004e5d7263625ed8886b871d505d7b924853`|
