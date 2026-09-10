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

工具调用严格限于 `search_merchants`、`list_dishes`、`get_last_order`、`create_cart_draft`、`validate_cart`、`get_addresses`、`create_order` 和 `pay_mock_order`。每次调用都会传递用户、会话、追踪和幂等键。
