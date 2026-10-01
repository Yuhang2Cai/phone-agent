# Task3 Spring 桥接开发验证记录

日期：2026-10-01。工作树：`C:/Users/caid0/Documents/ChatGPT/手机agent/.worktrees/fandazi-ai`，分支：`feature/fandazi-ai`。

状态：**开发自测通过，待独立验收**。本报告只覆盖 Spring 工具桥接与公开卡片兼容；没有勾选实施计划，没有提交 Git。真实 MySQL、8091 新 Agent、脚本启动和独立端到端验收由父任务继续运行，不能将下列 H2/MockMvc 结果写成真实部署已通过。

## 接口交接

- 入口：`POST /internal/v1/tools/{toolName}`，17 个工具来自权威 `agent-tools-v1.yaml`，不调用旧外卖 `AgentToolService`。
- 请求：`schemaVersion/userId/sessionId/traceId/runId/taskId/idempotencyKey/payload` 必需，`actionVersion` 可选、缺省 1。执行器键必须严格等于 `runId:taskId:actionVersion`。
- 鉴权：`Authorization: Bearer <HS256 JWT>`，共享环境变量 `AGENT_INTERNAL_TOKEN`，至少 32 UTF-8 字节。签名、`sub==userId`、整数 `exp`、未过期和剩余期限不超过 120 秒均验证；发送端签发 60 秒有效期。若存在 `iat/nbf`，不能晚于当前时刻。
- 会话：核对用户归属、`activeRunId`、本轮 `traceId` 和状态 `running`。暂停与终态不能继续调用工具。
- 返回：统一 `{schemaVersion:"1.1",ok,data,error}`。失败 `data={}`；固定错误码与安全中文文案，不公开异常堆栈、SQL 或原请求。鉴权拒绝 HTTP 403，缺工具/资源可返回 HTTP 404，其余业务错误用标准结果表达。
- `query_faq` 只接受 `question`，V1 不接受旧 `keyword` 或旧 `confirmationGrant`。旧页面领域结果的凭证名字只在兼容出口转换为 `confirmationGrantId`。
- 超时：`application.yml` 优先读取 `AGENT_SERVICE_TIMEOUT_MS`，兼容 `AGENT_SERVICE_TIMEOUT_MILLIS`，默认 90000 毫秒。

### 准备与独立支付

| 场景 | 资源绑定 | 规则与真实截止 |
|---|---|---|
| `create_purchase_quote` | `quoteId` | 返回 `confirmationRuleVersion=deal-vN`、`confirmationExpiresAt` |
| `prepare_refund` | `voucherId` | 返回 `confirmationRuleVersion=refund-v1`、`confirmationExpiresAt`；原 `expiresAt` 仍为券有效期 |
| `prepare_complaint` | 本人 `orderId`，无订单为内部 `general` | 返回 `confirmationRuleVersion=complaint-v1`、`confirmationExpiresAt` |
| `create_group_buy_order` 成功 | 新建真实 `orderId`、真实 `totalCents` | 额外返回 `paymentConfirmationGrantId/paymentConfirmationExpiresAt/ruleVersion=pay-v1` |

三个准备输出的确认元数据是兼容性的可选契约字段，但 Spring 新 V1 实际返回。创建和支付的 `data` 均为**直接订单摘要**，不是额外 `{order:...}` 包装。支付元数据只在创建结果返回，列表、详情和支付结果不补造新授权。

Agent 持久化创建输出后必须单独询问支付；`pay_mock_order` 使用支付专属 `confirmationGrantId`，可选 `ruleVersion` 只能是 `pay-v1`。支付调用 `GroupBuyOrderService.payConfirmed`，不调用旧外卖支付，不复用创建授权，不自动支付。

### 写入与重试

四个业务写动作要求本轮 `mode=CONFIRM` 且会话已明确确认。网关保存已经重查的待确认摘要，写工具核对动作、资源、真实金额、规则，以及退款原因/投诉正文。泛化“确认”不能授权其他动作；一次成功写入消费本轮确认，创建之后不能继续支付。

创建从持久化 `quoteId` 恢复真实数量、金额与版本；重查报价有效期、归属、状态及套餐价格、版本、库存、限购和日期。套餐行锁保护库存核验与扣减，报价消费、订单及券生成在同一事务中完成。凭证锁保护跨入口一次性消费，金额也必须匹配。

`agent_tool_executions` 保存 SHA-256 请求指纹与真实成功结果；业务写入、凭证消费和动作记录原子提交。JSON 对象递归排序，指纹包含工具、用户、会话、run、task、版本和载荷，排除恢复输入会变化的 trace。相同键和指纹只重放结果；载荷、工具或身份变化返回 `CONFLICT`。成功准备也重放原凭证，避免网络重试重复签发；会话准备动作限流为每分钟 30 次。

新增迁移：`V10__persist_agent_tool_execution.sql`、`V11__bind_agent_pending_confirmation.sql`。没有修改旧迁移或 Task2 货币迁移。

## 事实投影与公开边界

门店、套餐和评价读取真实实体；订单、券和优惠券按当前用户查询。金额保持整数分，公开 ID 不使用数据库自增键。旧订单/券 `LocalDateTime` 按上海业务时区转换成 UTC；套餐业务日期仍为 `date`。优惠券首尾业务日转换成实际 UTC 起止边界，避免已有券结果因非法日期格式无法通过输出校验。

canonical `cardType/resourceId/data/actions` 在集中适配器转换为旧 Android 平铺卡片。模型名称、金额、状态和资格被领域事实替换；退款和投诉采用旧端能够识别的售后卡片类型。复杂卡片强制可信用户上下文。

即时 NDJSON 事件在进入审计/SSE 前就重查卡片与确认摘要，返回事件同样使用该事实版本，不能先发布伪金额再等最终响应补全。公开摘要截止来自本会话匹配的确认签发记录，不能信任 Agent 或券有效期。

`AgentContractCompatibility` 原递归脱敏保留，并补充 `paymentConfirmationGrantId`。业务完成后隔离事务写公开工具审计；审计失败仅记录可定位链路，不改变已提交业务结果。内部执行重放记录可含凭证，不用作公开事件历史。

旧内部工具源码保留，新 V1 桥接没有调用旧分发器；旧源码/旧路由的完整退役仍属于 Task11。本次没有更改 Android UI、意图、Python service 或启动脚本。

## 契约同步

同步手机权威契约到 `D:/project/-Demo-master/config/contracts/`，更新 `manifest.yaml` 和历史 `source.sha256`，新增 `tests/test_task3_tool_preparation_contract.py`。

- tools SHA-256：`a17702e36789e05ba1dd1c62d7c1a7ff923b019e69d0741cbde3d269aa35b954`
- protocol SHA-256：`a6f6a9f4544c37105b77bee3b9dac357febec0a1a91e010717870bc592cff2d5`
- 协议 `inputKey` 增加合法 `cityCode/storeId`，仍保留 `dealId/orderId` 等明确槽位。

## 实际运行证据

工具链为 Windows PowerShell、IDEA Maven 3、JBR Java 21，编译目标 Java 17；Spring 测试使用真实 H2、JPA、Flyway、MockMvc，没有替换 V1 领域服务。

```powershell
$env:JAVA_HOME = 'D:/software/IntelliJ IDEA 2025.2.4/jbr'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'
& 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' '-Dtest=InternalV1ToolIntegrationTest,AgentGatewayTest,AgentRunIdentityTest,AgentProtocolBoundaryTest,Task1IndependentAcceptanceTest,Task1NestedPublicEventAcceptanceTest,Task1PublicToolAuditAcceptanceTest,PurchaseConfirmationTest,RefundEligibilityTest,CouponComplaintToolTest,GroupOrderSummaryContractTest' test
```

最后运行结束：2026-10-01 22:21:47 +08:00。实际结果：**53 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS**，28.042 秒。专项 `InternalV1ToolIntegrationTest` 为 **9/9**。证据在 `backend/target/surefire-reports/` 对应 XML/TXT 中。

专项覆盖：JWT 用户/有效期拒绝、17 白名单未知工具拒绝、FAQ canonical 字段、run/trace/动作键拒绝、完整指纹冲突、准备不写业务、报价数量 2/金额 2468 分恢复、创建后独立支付确认、跨动作凭证拒绝、同键创建/支付/退款/投诉重放、退款/投诉正文绑定、canonical 卡片伪价格替换、实时确认审计金额替换、实际已领取优惠券 UTC 投影。

开发 RED 证据：入口未实现时 4/4 失败；canonical 卡片无法识别时失败；实时摘要 1 分未重查导致期望 2468 分失败；已有优惠券日期无法满足输出契约时失败。上述均修复后运行 GREEN。旧退款测试曾用零金额授权已付款券；现在明确验证零金额拒绝，正常申请改用真实 prepare 输出，未放宽金额校验来迁就夹具。

```powershell
$env:MOBILE_PROJECT_ROOT = 'C:/Users/caid0/Documents/ChatGPT/手机agent/.worktrees/fandazi-ai'
python -X utf8 -m pytest tests/test_cross_repo_contracts.py tests/test_task3_tool_preparation_contract.py -q --basetemp=pytest-task3-spring-contract-final
```

实际结果：**35 passed in 1.12s**。`git diff --check` 对本次涉及模块与契约通过；现有 CRLF、过时 MockBean、H2/Flyway 版本及契约文档元数据未知关键词警告保留，不构成上述测试失败。

## 独立验收交接

Maven 已在 22:21:47 退出并释放，父任务可串行启动真实 Spring。本实现 Agent 没有启动独立验收 Agent，也没有运行真实 MySQL/新 Agent/Android 端到端；因此仍是**待独立验收**，不宣称 Task3 完成。

独立验收应亲自运行真实 Spring 与新 Agent，覆盖确认跨 trace 恢复、quoteId 摘要绑定、购买后单独支付、退款/投诉正文拒绝、公共 SSE/历史凭证隔离，以及并发重试和重启后的执行记录重放。真实 MySQL 上 V10/V11 的应用和 Hibernate 校验也需由该运行环境确认。

当前已有优惠券领域只有 `scopeDescription`，工具会验证可选门店/套餐引用并返回本人真实规则，不捏造结构化适用资格；报价暂不支持优惠券抵扣，显式传 `couponId` 时返回验证错误，避免静默忽略并声称已优惠。完整页面字段和金额迁移继续归 Task2。

## 22:30 补槽暂停兼容回归

父任务真实端到端发现：SSE 有 `input_required.question/inputKey`，但最终 DTO 的 `candidateCart` 为空，退款原因和投诉标题、正文无法结构化补槽。根因是 `HttpAgentServiceClient.result` 无条件使用只读嵌套 `summary` 的确认摘要适配器，而补槽暂停只有顶层元数据。

本轮生产代码仅修改该 `result` 方法：有 `inputKey` 或 `question` 时使用现有 `publicEventPayload` 递归脱敏后作为公共暂停元数据；候选仍合并到 `cards`，不重复放入摘要。确认暂停保持原摘要和资源别名映射，没有修改其他生产方法、服务、契约或脚本，也未停止或重启父任务告知的 Spring PID 41712。

新增 `AgentProtocolBoundaryTest.inputRequiredRetainsStructuredSlotMetadataInFinalResult`，通过真实本地 HTTP/NDJSON 分别运行退款原因、投诉标题、投诉正文三个场景，检查最终状态、动作、`question/inputKey` 和候选出口。

- RED：用前文相同 Java 环境运行 Maven `-Dtest=AgentProtocolBoundaryTest#inputRequiredRetainsStructuredSlotMetadataInFinalResult test`，22:29:50 退出；1 test、1 failure、0 errors，实际 `candidateCart={}` 缺少 `question=请补充退款原因`。
- GREEN：用前文原 11 类聚合命令完整重跑，22:30:35 退出；**54 tests、0 failures、0 errors、0 skipped、BUILD SUCCESS**，28.062 秒，其中协议边界 **10/10**。原 53 项与新增回归均通过。
- 证据：`backend/target/surefire-reports/com.fandazi.feature.agent.AgentProtocolBoundaryTest.txt` 及同名 XML；本轮源文件 `git diff --check` 无错误。

Maven 已释放，父任务负责重启真实 Spring 并复验原端到端失败；第二个确认 ValueError 继续由父任务定位。本轮结论仍为开发自测通过，不替代独立验收或真实端到端复验。
