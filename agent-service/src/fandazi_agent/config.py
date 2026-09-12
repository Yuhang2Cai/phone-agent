"""Environment-only configuration; application secrets are never committed."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    llm_base_url: str | None
    llm_api_key: str | None
    llm_model: str | None
    spring_boot_base_url: str
    max_graph_steps: int
    max_tool_calls: int
    max_order_amount: int
    workflow_signing_key: str | None = None
    workflow_store_dir: str | None = None
    agent_internal_token: str | None = None

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            llm_base_url=os.getenv("LLM_BASE_URL"),
            llm_api_key=os.getenv("LLM_API_KEY"),
            llm_model=os.getenv("LLM_MODEL"),
            spring_boot_base_url=os.getenv("SPRING_BOOT_BASE_URL", "http://localhost:8080"),
            max_graph_steps=int(os.getenv("AGENT_MAX_GRAPH_STEPS", "16")),
            max_tool_calls=int(os.getenv("AGENT_MAX_TOOL_CALLS", "8")),
            max_order_amount=int(os.getenv("AGENT_MAX_ORDER_AMOUNT", "100")),
            agent_internal_token=os.getenv("AGENT_INTERNAL_TOKEN"),
            workflow_signing_key=os.getenv("AGENT_WORKFLOW_SIGNING_KEY"),
            workflow_store_dir=os.getenv("AGENT_WORKFLOW_STORE_DIR"),
        )
