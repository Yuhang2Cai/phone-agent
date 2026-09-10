"""Serializable state used by the LangGraph workflow and confirmation resume store."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from .models import AgentEvent


class AgentState(BaseModel):
    user_id: str
    session_id: str
    workflow_id: str
    workflow_version: int = 1
    messages: list[str] = Field(default_factory=list)
    intent: str | None = None
    candidate_cart: dict[str, Any] = Field(default_factory=dict)
    authorization: dict[str, Any] = Field(default_factory=dict)
    events: list[AgentEvent] = Field(default_factory=list)
    next_action: str | None = None
    error: str | None = None
    completed_tools: list[str] = Field(default_factory=list)
    operation_keys: dict[str, str] = Field(default_factory=dict)
    graph_steps: int = 0
    tool_calls: int = 0
