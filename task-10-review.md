# Task 10 任务级复核报告

## 结论

- **Spec compliance: FAIL**
- **Task quality: FAIL**

提交范围：`8af1df7..1b1b8aa`。

虽然定向测试和全量后端测试均通过，但实现仍有会阻止生产启动、使 Spring Boot 与现有 Agent Service 无法联调、以及允许确认授权被跨订单复用的 Critical 问题。当前提交不应作为 Task 10 验收通过。

## Critical findings

### C1. 新增持久化实体没有数据库迁移，生产配置会在启动时校验失败

Task 10 新增了 `agent_sessions`、`agent_events`、`automation_policies`、`automation_grants` 四类持久化实体，但 diff 没有新增 Flyway migration。生产配置 `backend/src/main/resources/application.yml` 使用 `spring.jpa.hibernate.ddl-auto: validate`，现有唯一迁移 `V1__create_seed_metadata.sql` 也没有这些表。因此真实 MySQL 数据库仅执行仓库迁移后，Hibernate 会因缺表而拒绝启动，整个后端（不只是 Agent 功能）不可用。

现有测试没有发现该问题，是因为 `backend/src/test/resources/application.yml` 使用 `ddl-auto: create-drop`，由 Hibernate 在测试中自动建表。

影响：部署阻断；也违背“持久化会话、事件、策略和授权”的交付要求。

### C2. Python Agent Service 不发送 Spring Boot 强制要求的 `X-Agent-Token`

Spring Boot 的 `InternalToolController` 对每个 `/internal/tools/{toolName}` 请求读取并校验 `X-Agent-Token`；缺失或不匹配会返回 403。现有 Python `SpringBootToolClient.call()` 仅发送 JSON 和 timeout，没有发送任何 header；`Settings` 也没有读取 `AGENT_INTERNAL_TOKEN`。因此现有 Agent Service 发出的全部八类工具调用都会被 Spring Boot 拒绝。

`backend/task-10-report.md` 已明确承认该不匹配，但 Task 10 review package 特别要求检查跨服务契约，且目标是“现有 Python Agent Service 必须能够认证调用”。以“Python 文件不在本任务范围”为由保留一个必然失败的跨服务契约，不能满足任务验收。

影响：推荐、检索、加购、校验、下单和支付等所有实际 Agent 工具流程端到端不可用；只有普通非 Agent 页面仍可访问。

### C3. `CONFIRM` 授权是永久会话布尔值，可被后续不同订单复用

`AgentGatewayService` 在收到一次精确确认文本后调用 `sessions.confirm()`；`AgentSession.confirmed` 只会从 `false` 变为 `true`，`start/update` 不会重置它，也没有绑定 workflow、购物车、地址、金额、trace、幂等键或有效期。`OrderAuthorizationService` 对 `CONFIRM` 模式只检查这个布尔值，随后 `create_order` 可以传入任意属于该用户的新购物车和地址及新幂等键。

因此同一会话中第一次确认下单后，后续新的订单任务即使没有新的用户确认，Spring Boot 最终授权边界仍会放行。Python Agent 当前工作流会自行等待确认，不能替代 Spring Boot 的最终授权责任。

影响：可能产生未经本次明确确认的新订单，属于订单授权边界失效。

## Important findings

### I1. SSE 只是一次性历史回放，不会推送连接建立后的新事件

`AgentGatewayController.stream()` 查询当前数据库事件、逐条发送，然后无条件 `emitter.complete()`。同时消息 POST 同步等待 Agent Service 返回，`AgentGatewayService` 也只在完整结果返回后批量持久化 Agent Service 事件。

结果是：

- 客户端不能通过已建立的 SSE 连接看到后续解析、工具执行或完成事件；
- `Last-Event-ID` 只对连接建立前已存在的历史记录生效；
- 现有测试只断言响应中包含 `agent-event`，没有覆盖 `Last-Event-ID`，也没有验证断线期间新增事件或连接后的实时推送。

这不满足“推送事件”和“SSE 断线重连”的完整语义。

### I2. Agent 下单重试在幂等命中前重新执行授权和购物车校验，可能无法返回已创建订单

`AgentToolService.createOrder()` 先执行 `authorizations.authorize()`，之后才进入 `OrderService.create()`；后者才查询 `(userId, idempotencyKey)` 对应的已有订单。

第一次调用已经成功但响应丢失后，使用相同幂等键重试时可能在到达幂等查询前失败，例如：

- 第一次下单已耗尽该菜品库存，重试的 `carts.validate()` 返回库存不足；
- 单次授权在响应丢失后过期；
- 地址或商品状态在重试前改变。

此时后端不会返回已成功创建的同一订单，破坏 Agent Service `order_recovery` 所依赖的幂等对账语义。现有重复请求测试使用剩余库存充足、授权未过期的即时重试，未覆盖这一情况。

### I3. 自动支付没有绑定本次会话创建的订单，也不重新检查授权

`pay_mock_order` 只检查会话模式为 `THIS_TASK_AUTO`，然后按用户和请求体 `orderId` 支付。它不验证该订单是否由当前 session/workflow 的 `create_order` 创建，也不检查任务授权/长期策略是否仍有效。因此持有内部 token 的 Agent 调用可在一个自动模式会话中支付该用户任意待支付订单，即使该会话没有有效下单授权或目标订单来自手工流程。

用户归属由 `PaymentService` 保证，不会跨用户支付；但“本次任务授权只作用于本次订单”的边界没有建立。

### I4. `THIS_TASK_AUTO` 和长期策略只有内部 Service 方法，没有可用的生产配置入口

`AutomationPolicyService.upsert()` 和 `grantForTask()` 只在测试中被直接调用，仓库中没有 Controller 或其他生产调用方。消息 API 的请求体也只有 `{text, mode}`，无法提交地址、金额上限或有效期。

因此在真实运行中，除非绕过应用直接预置数据库记录，否则无法创建一次性授权或长期策略；`THIS_TASK_AUTO` 会因“自动授权不存在或已失效”而失败。测试通过依赖测试代码直接注入授权记录，未证明用户可走完整接口建立授权。

## Minor findings

### M1. Agent Service 异常响应的降级映射不完整

`HttpAgentServiceClient.run()` 只把 `RestClientException` 映射为 `AgentServiceUnavailableException`。如果 Agent Service 返回可解析 HTTP 但响应结构异常，`result()` 内的运行时错误（例如事件字段为异常类型）会直接冒泡为后端 500，而不会形成统一的可重试失败结果。

### M2. 测试缺少真实跨服务契约和关键负向场景

现有测试没有让真实 `SpringBootToolClient` 调用真实 `InternalToolController`，因此漏掉 token header 不匹配；也没有覆盖：生产 Flyway schema validation、实时 SSE/`Last-Event-ID`、确认授权跨订单复用、库存耗尽或授权过期后的同键恢复、以及自动支付错误订单。

## 已确认符合的部分

- Spring Boot 工具白名单恰好为要求的八个名称，白名单外路径返回 404。
- 内部 token 在 Spring Boot 侧使用常量时间比较；未配置、缺失或错误 token 均拒绝访问。
- 公共 Agent 消息和事件接口需要 JWT，事件查询按 `userId + sessionId` 隔离；其他用户不能复用已存在会话。
- 内部工具忽略请求体中的 `userId`，从服务端持久化 session 推导用户；地址、购物车、订单和支付服务继续执行用户归属检查。
- `create_order` 前会检查地址归属、地址元数据有效性、确认/自动授权、金额上限，并通过购物车服务重新读取菜品、规格和库存；最终创建订单时使用原有原子库存扣减和订单唯一幂等约束。
- 复购会在原菜品或规格不可用时终止。
- Agent Service 超时/连接异常会返回终态可重试失败；已有普通商家接口不依赖 Agent Service，相关测试通过。
- diff 未发现提交真实密钥；`test-agent-token` 和测试 JWT secret 仅位于测试配置。

## 测试与检查结果

- `git diff --check 8af1df7..1b1b8aa`：通过。
- Task 10 定向测试：`10` passed，`0` failed/errors。
- 后端全量测试：`63` passed，`0` failed/errors。
- Agent Service 全量测试：`122` passed，`2` 个第三方弃用 warning。

这些绿灯证明当前单体测试覆盖的路径可运行，但不能消除上述跨服务、生产迁移、授权生命周期和 SSE 语义问题。

## 最终判定

Task 10 当前版本不具备验收条件。至少应先修复 C1-C3，并补齐 C2 的真实跨服务测试、I1 的实时/重连测试和 I2 的不确定结果恢复测试；随后重新运行生产 schema validation、定向测试、后端全量测试和 Agent Service 契约测试。
