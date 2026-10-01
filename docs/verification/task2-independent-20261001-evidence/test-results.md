# Task2 独立运行结果

仅保存脱敏统计和命令；原始 Maven 输出保留在 D 仓 `.scratch/task2-independent/`，不作为可提交业务数据。

## 后端定向

环境：`JAVA_HOME=D:/software/IntelliJ IDEA 2025.2.4/jbr`，`MAVEN_OPTS=-Dfile.encoding=UTF-8`。
Maven：`D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd`。
工作目录：`C:/fdz-build`。

```text
mvn.cmd -f backend/pom.xml -Dtest=GroupOrderSummaryContractTest,GroupBuyingSeedDataTest,RefundEligibilityTest,ReviewEligibilityTest,GroupBuyingMigrationTest,Task2DevelopmentFixtureTest,CentsDecimalConverterTest,BusinessTimeTest,Task2FixturesProductionGuardTest,Task2FixturesDisabledTest,StoreDealApiTest,GroupBuyOrderIdempotencyTest,PurchaseConfirmationTest,VoucherLifecycleTest,VoucherTokenSecurityTest,GroupBuyingSchemaTest,CouponComplaintToolTest,AgentGatewayV1ContractTest,AgentContractSchemaTest,SeedDataLoaderTest,Task1NestedPublicEventAcceptanceTest test
```

实测：70 tests，0 failures，0 errors，0 skipped；BUILD SUCCESS；27.967 秒；exit 0。

| 测试类 | 通过数 |
|---|---:|
| GroupOrderSummaryContractTest | 3 |
| GroupBuyingSeedDataTest | 3 |
| RefundEligibilityTest | 7 |
| ReviewEligibilityTest | 4 |
| GroupBuyingMigrationTest | 1 |
| Task2DevelopmentFixtureTest | 1 |
| CentsDecimalConverterTest | 2 |
| BusinessTimeTest | 3 |
| Task2FixturesProductionGuardTest | 1 |
| Task2FixturesDisabledTest | 1 |
| StoreDealApiTest | 3 |
| GroupBuyOrderIdempotencyTest | 1 |
| PurchaseConfirmationTest | 2 |
| VoucherLifecycleTest | 1 |
| VoucherTokenSecurityTest | 1 |
| GroupBuyingSchemaTest | 2 |
| CouponComplaintToolTest | 4 |
| AgentGatewayV1ContractTest | 4 |
| AgentContractSchemaTest | 5 |
| SeedDataLoaderTest | 17 |
| Task1NestedPublicEventAcceptanceTest | 4 |

## 新 Agent 离线

工作目录：`D:/project/-Demo-master`；`MOBILE_PROJECT_ROOT=C:/fdz-build`。

```text
C:/Users/caid0/anaconda3/envs/pinnT/python.exe -X utf8 -m pytest -q --tb=short --ignore=tests/test_agent_service_live.py --ignore=tests/test_agent_service_e2e_live.py --ignore=tests/test_task2_page_data_live.py --ignore=tests/test_task2_fixtures_live.py --basetemp=D:/project/-Demo-master/.scratch/task2-independent/offline-temp-20261001-a
```

实测：352 passed in 15.79s；exit 0。没有把四份被排除在线文件计入此通过数。

## Task2 在线首轮

```text
RUN_TASK2_LIVE=1 MOBILE_PROJECT_ROOT=C:/fdz-build
python.exe -X utf8 -m pytest tests/test_task2_page_data_live.py tests/test_task2_fixtures_live.py -q --tb=short --basetemp=D:/project/-Demo-master/.scratch/task2-independent/live-temp-20261001-a
```

实测：1 failed, 8 passed in 29.14s；exit 1。
失败：`test_real_catalog_uses_uuid_and_has_actual_and_missing_image_paths` 第 69 行调用 `pictures.get(picture)`，HTTP 响应体读取阶段 `httpcore.ReadTimeout` 转为 `httpx.ReadTimeout`。timeout=20、trust_env=False、follow_redirects=True。
其余七账号只读状态/归属/负面 prepare 与随机账号真实支付/退款均通过。尚待图片复测；不把此失败写成全绿。

## 图片原样复测

```text
RUN_TASK2_LIVE=1 MOBILE_PROJECT_ROOT=C:/fdz-build
python.exe -X utf8 -m pytest tests/test_task2_page_data_live.py::test_real_catalog_uses_uuid_and_has_actual_and_missing_image_paths -q --tb=short --basetemp=D:/project/-Demo-master/.scratch/task2-independent/image-retest-20261001-a
```

实测：1 passed in 3.14s；exit 0；未修改测试、URL 或服务。

## 完整后端

```text
mvn.cmd -f backend/pom.xml test
```

实测：195 tests，194 PASS、1 failure、0 errors、0 skipped；BUILD FAILURE；1:18；exit 1。
唯一失败 `AgentCardEnrichmentTest.unknownCardTypeIsRejectedWithoutFallbackData`：期望消息包含“不支持”，实际 `VALIDATION_ERROR:卡片缺少资源或动作`。其生产类和测试类的 `git diff --name-only HEAD` 均为空。Task2 四计划类和 70 项定向范围在全量中仍全通过。
原始日志：`D:/project/-Demo-master/.scratch/task2-independent/backend-full-raw.log`；不宣称全量全绿。

## Task3 双服务回归

```text
. C:/fdz-build/scripts/Initialize-FandaziEnvironment.ps1
RUN_AGENT_LIVE=1 MOBILE_PROJECT_ROOT=C:/fdz-build
python.exe -X utf8 -m pytest tests/test_agent_service_live.py tests/test_agent_service_e2e_live.py -q --tb=short --basetemp=D:/project/-Demo-master/.scratch/task2-independent/agent-live-20261001-a
```

实测：5 passed in 31.04s；exit 0。包含健康来源、JWT 拒绝、问候、空订单、投诉确认、退款确认和复合读取 DAG；仅使用新注册随机账号，不消费七个夹具状态。

## Task2 整组复验

```text
RUN_TASK2_LIVE=1 MOBILE_PROJECT_ROOT=C:/fdz-build
python.exe -X utf8 -m pytest tests/test_task2_page_data_live.py tests/test_task2_fixtures_live.py -q --tb=short --basetemp=D:/project/-Demo-master/.scratch/task2-independent/live-temp-20261001-b
```

实测：9 passed in 5.68s；exit 0。原测试、服务与图片 URL 均未修改。保留首轮超时，不用复验抹去历史失败。

## 独立 Java 探针

脚本：`D:/project/-Demo-master/.scratch/task2-independent/Task2IndependentProbe.java`。直接加载本次 Maven 编译出的实际生产类与实际 Spring 条件注册器，不伪造 Bean 结果。

```powershell
[xml]$task2Xml=Get-Content -LiteralPath 'C:/fdz-build/backend/target/surefire-reports/TEST-com.fandazi.feature.agent.AgentCardEnrichmentTest.xml'
$task2Cp=($task2Xml.testsuite.properties.property | Where-Object {$_.name -eq 'java.class.path'}).value
& 'D:/software/IntelliJ IDEA 2025.2.4/jbr/bin/java.exe' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $task2Cp 'D:/project/-Demo-master/.scratch/task2-independent/Task2IndependentProbe.java'
```

实测：`INDEPENDENT_JAVA_CHECKS=10 PASS`；exit 0。
- prod / production / seed_disabled / fixture_disabled / development_absent 五种组合均未注册真实夹具 Bean。
- 未知类型缺少资源：`DomainException: VALIDATION_ERROR:卡片缺少资源或动作`；未知类型有资源：`DomainException: VALIDATION_ERROR:不支持的 Agent 卡片类型`；两种都拒绝，无 fallback 业务数据。
- 已实付、券仍在未来有效期，但订单为 CANCELLED / EXPIRED 时 canRefund 均 false。
- 10001 分三券首券 3334 分；尝试改为 3333 分抛异常，原支付快照保持 3334。

首轮 Java 启动因 PowerShell 未引用 `-Dfile.encoding=UTF-8` 将参数拆分，exit 1、未运行探针；引用参数后实际运行成功。保留此命令错误，不把未执行尝试计入通过数。

## 独立 MySQL/HTTP 探针

```powershell
. C:/fdz-build/scripts/Initialize-FandaziEnvironment.ps1
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -X utf8 'D:/project/-Demo-master/.scratch/task2-independent/inspect_task2.py'
```

实测：`{"independent_checks":40,"status":"PASS","fixture_state_writes":0}`；exit 0。
包含真实夹具余分、券/退款归属隔离、公开旧目录别名拒绝、新随机账号报价→下单→支付→准备→申请→回读、UTC 实际时钟与 MySQL 微秒对照、整数分与 DECIMAL 元严格对应、新订单 legacy total_cents 为 NULL、申请后重准备拒绝。凭据只从忽略清单读入内存，Docker 密码只通过环境传递。

## 图片补充证据

首次扩展探针实际得到：HTTP 200，Content-Type=image/jpeg，41914 bytes，12.968 秒，JPEG 首字节 FFD8、尾字节 FFD9。未下载图片到仓库。同客户端第二次请求出现 RemoteProtocolError，脚本 exit 1；这轮未执行其后业务写入，不算核心流程通过。

随后把图片稳定性与核心探针分阶段，核心 40 项独立实际运行通过；原在线图片用例单项和整组复验均通过。外部图源仍有偶发超时/连接中断风险，Task2 验证的是实际图片字节路径存在与缺图为 null，不承诺外部图源稳定性。
