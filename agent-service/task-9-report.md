# Task 9 实现报告：Python LangChain + LangGraph Agent Service

## 状态

已完成并准备提交。新增的 `agent-service` 是内部 Python 服务：它只通过 `SpringBootToolClient` 调用 Spring Boot 的 `/internal/tools/{toolName}`，不访问 MySQL，也没有 Android 入口。

## 实现内容

- FastAPI 内部入口：`POST /internal/agent/run`，以 Pydantic `AgentRunRequest` 接收 camelCase 请求，返回结构化 `AgentResult`。
- Pydantic 契约：`AgentState`、`AgentEvent`、`AgentResult`、`ToolRequest` 与 `ToolResult`。
- 单一 `FoodOrderingAgent`：意图解析（可选 LangChain 结构化输出）和确定性订餐节点分离；LangGraph 拓扑包含 `parse_intent → collect_missing_info → search_candidates → build_cart → validate_order → wait_confirmation → create_order → payment_followup`。
- 固定八项工具白名单，所有工具请求携带 `userId`、`sessionId`、`traceId` 和 `idempotencyKey`。
- 单次图步数和工具调用次数上限会返回可解释错误及事件；不会暴露隐含推理。
- 确认前状态可导出为 JSON 安全快照并恢复，已完成工具标记不会在恢复后重放。
- 涉及“感冒”等表述时只做一般饮食筛选，明确提示不是医疗建议。

## 测试

执行命令：

```powershell
python -m pytest agent-service/tests -q
```

完整结果：

```text
.................                                                        [100%]
17 passed, 9 warnings in 0.75s
```

另外执行：

```powershell
python -m compileall -q agent-service/src
git diff --check
```

两项命令均以退出码 0 完成。测试覆盖普通推荐、缺少信息追问、确认下单、自动下单、长期策略、精确复购、复购下架终止、金额与工具循环上限、非法工具、确认状态同进程/序列化恢复、健康敏感表述、工具契约字段、图步数上限及 FastAPI 路由契约。

## 依赖与限制

已在当前 Python 3.13 环境安装并验证 FastAPI、LangChain、LangGraph 与项目可编辑包；无需兼容实现替代真实依赖。`LLM_BASE_URL`、`LLM_API_KEY` 与 `LLM_MODEL` 只从环境读取且未提交。未配置真实模型时，服务使用受限的确定性意图降级路径，方便离线契约测试。

测试中仍有 9 条第三方依赖告警：LangChain 0.3 在 Python 3.13 的 Pydantic v1 兼容层警告、LangGraph 0.6 的未来默认值警告，以及 Starlette TestClient 的 AnyIO 弃用警告。它们不来自本项目代码，未影响 17 个测试全部通过；后续依赖大版本升级时应复查。

## 修复轮次 1（Task 9 复核整改）

已将手写流程替换为实际执行的 LangGraph `StateGraph`：八个节点均执行真实业务编排，条件路由覆盖推荐、策略、信息缺失、校验失败、确认、自动下单与终态；确认使用 LangGraph `interrupt` 和 `MemorySaver` checkpoint 暂停/恢复。

- C1：LLM 仅用于意图识别，绝不写入确认授权。只有暂停态下的精确确认词可进入下单；“不要下单”“取消”等确定性否定会终止工作流。
- C2：活动状态和恢复快照均校验 `userId + sessionId` 所有权，工作流另有不可变 `workflowId` 与版本。
- I2/I4：终态会清理 workflow/checkpoint；每个工具操作的幂等键固定为 `{workflowId}:{toolName}`，恢复和新 trace 不会改变逻辑操作键。
- I3：全部节点经统一图步数硬限制，工具调用同样受每次图执行预算保护；超限立即产生错误事件并终止。
- M1/M2：显式 `max_order_amount=0` 生效；已配置 LLM 的失败在订单/自动下单模式失败关闭并记录结构化事件，非订单模式才使用可观测的受限降级。

本轮验证命令：

```powershell
python -m pytest agent-service/tests -q
python -m compileall -q agent-service/src
git diff --check
```

结果：`27 passed, 9 warnings in 1.05s`，其余两项退出码为 0。新增回归覆盖否定确认、LLM 授权隔离、跨用户同 session 隔离、终态后新订单、真实 LangGraph checkpoint/interrupt、策略图步上限、稳定恢复幂等键、显式零金额上限、快照所有权以及模型失败关闭。9 条告警仍全部来自已记录的第三方依赖兼容层。
