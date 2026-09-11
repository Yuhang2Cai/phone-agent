"""Internal HTTP entry point. Android must call Spring Boot, never this service."""

from __future__ import annotations

from .config import Settings
from .graph import FoodOrderingAgent
from .models import AgentResult, AgentRunRequest
from .spring_client import SpringBootToolClient
from .workflow_store import FileWorkflowStore


def create_app(agent: FoodOrderingAgent | None = None):
    try:
        from fastapi import FastAPI
    except ImportError as exc:
        raise RuntimeError("Install agent-service dependencies to run the HTTP service") from exc

    settings = Settings.from_env()
    agent = agent or FoodOrderingAgent(
        SpringBootToolClient(settings.spring_boot_base_url), settings=settings,
        workflow_store=FileWorkflowStore(settings.workflow_store_dir) if settings.workflow_store_dir else None,
    )
    app = FastAPI(title="饭搭子 AI Agent Service", docs_url=None, redoc_url=None)

    @app.post("/internal/agent/run", response_model=AgentResult)
    def run_agent(request: AgentRunRequest) -> AgentResult:
        return agent.run(request)

    return app


try:
    app = create_app()
except RuntimeError:
    # Importing core code remains possible in minimal test environments.
    app = None
