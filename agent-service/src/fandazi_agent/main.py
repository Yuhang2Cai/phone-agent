"""内部 HTTP 入口；安卓客户端只能通过后端访问代理能力。"""

from __future__ import annotations

import hmac

from .config import Settings
from .graph import FoodOrderingAgent
from .models import AgentResult, AgentRunRequest
from .spring_client import SpringBootToolClient
from .workflow_store import FileWorkflowStore


"""创建app。"""
"""创建app。"""
def create_app(agent: FoodOrderingAgent | None = None, *, settings: Settings | None = None):
    try:
        from fastapi import FastAPI, Header, HTTPException
    except ImportError as exc:
        raise RuntimeError("Install agent-service dependencies to run the HTTP service") from exc

    settings = settings or (agent.settings if agent is not None else Settings.from_env())
    agent = agent or FoodOrderingAgent(
        SpringBootToolClient(settings.spring_boot_base_url), settings=settings,
        workflow_store=FileWorkflowStore(settings.workflow_store_dir) if settings.workflow_store_dir else None,
    )
    app = FastAPI(title="饭搭子 AI Agent Service", docs_url=None, redoc_url=None)

    """运行助手。"""
    """运行助手。"""
    @app.post("/internal/agent/run", response_model=AgentResult)
    def run_agent(request: AgentRunRequest, x_agent_token: str | None = Header(default=None)) -> AgentResult:
        if not settings.agent_internal_token or not hmac.compare_digest(settings.agent_internal_token, x_agent_token or ""):
            raise HTTPException(status_code=403, detail="内部 Agent 凭据无效")
        return agent.run(request)

    return app


try:
    app = create_app()
except RuntimeError:
    # 最小测试环境也应能够导入核心代码。
    app = None
