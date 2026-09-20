# Agent 完整开发与测试 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with verification checkpoints.

**Goal:** 完成 Agent 服务的真实后端联调、契约回归和文档收尾，确保有效会话下的推荐、确认下单、恢复与错误处理可验证。

**Architecture:** 保持 Python Agent 只通过 Spring Boot `/internal/tools/{toolName}` 访问业务数据，不增加数据库访问。先用测试固定会话、工具请求和 HTTP 错误分类契约，再补真实 Spring Boot 会话创建后的端到端 smoke 流程；恢复、幂等和取消逻辑继续由现有 LangGraph 与 WorkflowStore 负责。

**Tech Stack:** Python 3.12、FastAPI、Pydantic、LangGraph、pytest、httpx；Spring Boot 3.4、MySQL 8、PowerShell smoke scripts。

**Spec:** `docs/superpowers/specs/2026-09-09-fandazi-ai-design.md`

## Global Constraints

- Android 不直接访问 Python Agent；Python Agent 只调用 Spring Boot 工具边界。
- 订单创建、权限、库存、金额和支付状态以 Spring Boot 为最终裁定。
- 所有下单操作必须携带稳定幂等键，并覆盖不确定失败恢复。
- 不提交 LLM 密钥、JWT 密钥、数据库密码或工作流签名密钥。
- 所有新增生产行为必须先有一个能正确失败的回归测试。

---

### Task 1: 固定有效会话与工具契约回归

**Files:**
- Modify: `agent-service/tests/test_spring_tool_errors.py`
- Modify: `agent-service/tests/test_food_ordering_agent.py`
- Create: `agent-service/tests/test_http_contract.py`

**Interfaces:**
- Consumes: `SpringBootToolClient.call`, `FoodOrderingAgent.run`, `create_app`.
- Produces: 可重复的有效会话 HTTP smoke 契约和无效会话/令牌拒绝断言。

- [ ] **Step 1: 写失败测试**：用真实 HTTP transport stub 固定 `/internal/tools/search_merchants` 的请求体，断言 Agent 传递 `userId/sessionId/traceId/idempotencyKey/payload.query`；增加 FastAPI 缺少令牌、错误令牌和合法请求体的响应断言。
- [ ] **Step 2: 运行定向测试确认失败**：`.\agent-service\.venv\Scripts\python.exe -m pytest agent-service/tests/test_http_contract.py agent-service/tests/test_spring_tool_errors.py -q`，确认失败原因是契约覆盖缺失而非测试配置错误。
- [ ] **Step 3: 实现最小修复**：只在必要处调整 HTTP 入口、请求序列化或测试 transport；保留业务执行只能经 LangGraph 的约束。
- [ ] **Step 4: 运行定向测试确认通过**：重复同一命令，预期全部通过。

### Task 2: 真实 Spring Boot 会话联调 smoke

**Files:**
- Create: `agent-service/tests/test_live_spring_smoke.py`
- Modify: `agent-service/README.md`
- Modify: `docs/verification-report.md`

**Interfaces:**
- Consumes: Spring Boot auth/session/agent gateway endpoints and running Agent HTTP service.
- Produces: 可在本地环境重复运行、无硬编码秘密的 smoke 流程；环境缺失时明确跳过并说明原因。

- [ ] **Step 1: 写失败测试/探针**：先定义 smoke 所需的注册或登录、创建 Agent session、发送推荐消息的响应断言；无服务时必须报告连接失败，不得伪造通过。
- [ ] **Step 2: 运行探针确认当前状态**：启动当前服务后执行有效用户会话流程，记录状态码、响应结构和 Agent 工具事件。
- [ ] **Step 3: 修复发现的真实契约问题**：若字段、token、会话生命周期或响应模型不一致，先补最小回归测试，再修改对应 Python 代码。
- [ ] **Step 4: 重跑 smoke**：至少覆盖推荐、需要确认的订单路径和取消/恢复路径；输出可审计的结果摘要。

### Task 3: Agent 全量回归与质量收尾

**Files:**
- Modify: `agent-service/README.md`
- Modify: `agent-service/task-9-report.md`
- Modify: `docs/verification-report.md`

**Interfaces:**
- Consumes: 全部 Agent 单元、恢复、错误分类和 live smoke 测试。
- Produces: 完整验证记录、已知限制和启动方式。

- [ ] **Step 1: 运行 Agent pytest 全量**：使用项目虚拟环境及项目内临时目录，记录通过数和仅来自第三方的告警。
- [ ] **Step 2: 运行编译与差异检查**：执行 `compileall` 和 `git diff --check`。
- [ ] **Step 3: 运行真实 HTTP 探活**：验证 8080、8090、有效会话工具调用和无效凭据拒绝。
- [ ] **Step 4: 更新报告**：只记录有证据的结果；若 Android 模拟器不可用，明确列为外部阻塞，不把 Agent 结果标成失败。

## Self-review

- 覆盖了当前已知的直接无效 `sessionId` 400 根因和有效会话联调。
- 没有扩大到 Android UI 或 Spring Boot 业务改造。
- 所有生产代码变化均要求先写失败回归测试。
- 未引入真实 LLM 依赖或任何提交中的秘密。
