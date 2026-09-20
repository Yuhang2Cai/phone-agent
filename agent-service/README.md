# 饭搭子 AI Agent Service

这是仅供服务端使用的 Python Agent Service。Android 只调用 Spring Boot；本服务也只通过 Spring Boot 的 `POST /internal/tools/{toolName}` 工具契约访问订单、权限和数据，不连接 MySQL。

## 本地运行

需要 Python 3.11+。在 `agent-service` 目录创建虚拟环境并安装：

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e ".[dev]"
$env:SPRING_BOOT_BASE_URL = "http://localhost:8080"
$env:LLM_BASE_URL = "https://your-compatible-llm-endpoint/v1"
$env:LLM_API_KEY = "set-locally-only"
$env:LLM_MODEL = "your-model"
uvicorn fandazi_agent.main:app --host 127.0.0.1 --port 8090
```

不要提交 `LLM_API_KEY` 或任何 `.env` 文件。若未设置 LLM 配置，意图解析使用受限的确定性降级路径，便于契约测试；部署时应提供 LangChain 兼容模型配置。

内部入口为 `POST /internal/agent/run`，请求体：

```json
{"userId":"u-1","sessionId":"s-1","text":"确认下单，宫保鸡丁","mode":"order","traceId":"trace-1"}
```

运行测试：

```powershell
python -m pytest agent-service/tests -q
```

在 Windows 环境中，如果系统 `python` 是 Microsoft Store stub，请使用项目虚拟环境解释器：

```powershell
.\agent-service\.venv\Scripts\python.exe -m pytest agent-service/tests -q --basetemp agent-service/.pytest-basetemp-task12
```

Task 12 的 seed 场景测试读取 `data/seed/agent-scenarios.json`，使用 Fake Spring Boot Tool Client 验证工具序列、确认暂停和失败分支，不调用真实 LLM。

工具调用严格限于 `search_merchants`、`list_dishes`、`get_last_order`、`create_cart_draft`、`validate_cart`、`get_addresses`、`create_order` 和 `pay_mock_order`。每次调用都会传递用户、会话、追踪和幂等键。

## 重启后恢复下单

HTTP 服务可启用文件工作流存储。在启动服务前设置：

```powershell
$env:AGENT_WORKFLOW_STORE_DIR = Join-Path $env:LOCALAPPDATA 'Fandazi/agent-workflows'
$env:AGENT_WORKFLOW_SIGNING_KEY = '<从服务端密钥配置注入的稳定随机密钥>'
```

存储目录必须仅供服务访问，并在进程重启后保留；签名密钥也必须保持一致，不能在每次启动时重新生成。不要提交密钥或工作流快照。配置存储但缺少签名密钥时服务会拒绝启动。未配置存储时仍可使用进程内工作流及手动 `export_session` / `restore_session`，但 HTTP 服务不会自动跨进程恢复。

`FoodOrderingAgent(..., workflow_store=store)` 接受可替换的 `WorkflowStore`，接口为按完整 `(user_id, session_id)` 键执行的 `load`、`save`、`delete`。`save` 必须原子保存完整签名快照并在持久化后返回；读取或写入失败须抛出异常，不能把存储故障当成工作流不存在。内置 `FileWorkflowStore` 使用 JSON、文件刷新和同目录原子替换，不访问业务数据库。

在发送 `create_order` 前，Agent 先保存购物车、授权、原 `workflow_id` 和 `{workflow_id}:create_order` 操作键。遇到不确定结果，新的 Agent 实例从存储自动恢复并通过同一个 LangGraph 入口重试，沿用原键及原请求载荷，更新当前 `traceId`。下单已成功则先记录完成标记，恢复时仅进入后续支付步骤。Spring Boot 仍须按幂等键返回同一逻辑操作的结果，并最终裁定权限、库存、金额和订单状态。

错误分类由工具边界统一处理：

| 工具结果或 HTTP 情况 | 下单处理 |
| --- | --- |
| `error_kind="rejected"`、普通 2xx 的未分类 `ok=false`、除 408/429 外的 4xx | 明确拒绝，终止并清理 |
| `error_kind="uncertain"`、连接/超时/协议错误、408/5xx、无法解析的 2xx | 保留 `order_recovery` 及原幂等键 |
| `error_kind="retryable"` 或 429 | 保留工作流，后续请求仍使用原键重试 |

Spring Boot 的错误响应即使只有 `error` 或 `message` 字段，Agent 也会保留其中的业务原因；例如“会话不存在”“缺少明确菜品”不会被压缩成没有诊断价值的 HTTP 状态码。

明确的 4xx 拒绝优先于响应体中矛盾的重试标记；5xx 中明确标记的 `rejected` 也会终止。对于未分类的 408/429/5xx 错误体，以 HTTP 语义分类，不根据错误消息的子串猜测是否可重试。

`order_recovery` 收到“取消”“不要下单”等明确取消文本后，不再调用 `create_order`，并清理内存工作流、LangGraph checkpoint 和持久快照。取消仅表示停止本次下单重试；此前请求可能已在后端成功，需要到订单页核实。取消和业务拒绝在删除快照前先保存签名终态；若删除失败，后续恢复只返回该终态并重试清理，不会重放下单。存储保存失败时也会保留当前实例已知的恢复/终态，避免把下一次取消误送入旧的工具节点。

`wait_confirmation`、`order_recovery`、`payment_retry` 以及清理未完成的取消/拒绝终态均可导出和恢复；所有恢复字段都参与 canonical JSON HMAC，恢复时校验版本和完整 user/session 所有权。公开 `invoke_tool` 不执行工具，所有业务执行必须通过 `run` 的 LangGraph 流程。

内置文件适配器面向同机进程重启，不提供分布式锁或多主机存储。调用方必须按 `(user_id, session_id)` 串行调用 `run` / `restore_session`；多 worker 部署需提供跨 worker 的串行化及相应存储适配器。文件持久化测试覆盖进程直接退出后的恢复，不代表断电或磁盘丢失保障。
