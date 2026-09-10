"""Public API and backend-tool contract models."""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from .config import Settings


class AgentRunRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    user_id: str = Field(alias="userId", min_length=1)
    session_id: str = Field(alias="sessionId", min_length=1)
    text: str = Field(min_length=1, max_length=2000)
    mode: Literal["recommend", "order", "auto_order", "strategy"] = "recommend"
    trace_id: str = Field(alias="traceId", min_length=1)


class AgentEvent(BaseModel):
    node: str
    kind: Literal["info", "tool", "warning", "error"]
    message: str
    tool_name: str | None = None
    data: dict[str, Any] = Field(default_factory=dict)


class ToolRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    tool_name: str
    user_id: str = Field(alias="userId")
    session_id: str = Field(alias="sessionId")
    trace_id: str = Field(alias="traceId")
    idempotency_key: str = Field(alias="idempotencyKey")
    payload: dict[str, Any] = Field(default_factory=dict)


class ToolResult(BaseModel):
    ok: bool
    data: dict[str, Any] = Field(default_factory=dict)
    error: str | None = None


class AgentResult(BaseModel):
    status: Literal["completed", "needs_input", "awaiting_confirmation", "failed"]
    message: str
    next_action: str
    candidate_cart: dict[str, Any] = Field(default_factory=dict)
    events: list[AgentEvent] = Field(default_factory=list)
    error: str | None = None


class IntentOutput(BaseModel):
    """The narrow structured output expected from the LangChain model adapter."""

    intent: Literal["recommend", "order", "reorder", "strategy"]
    needs_confirmation: bool = False
    health_sensitive: bool = False


class LangChainModelAdapter:
    """Optional LangChain structured-output adapter with a safe deterministic fallback."""

    def __init__(self, settings: Settings):
        self._settings = settings
        self._chain: Any | None = None
        if settings.llm_api_key and settings.llm_model:
            try:
                from langchain_openai import ChatOpenAI

                self._chain = ChatOpenAI(
                    base_url=settings.llm_base_url,
                    api_key=settings.llm_api_key,
                    model=settings.llm_model,
                    temperature=0,
                ).with_structured_output(IntentOutput)
            except ImportError:
                # The service remains testable without optional installed packages.
                self._chain = None

    def classify(self, text: str, requested_mode: str) -> IntentOutput:
        lowered = text.lower()
        fallback = IntentOutput(
            intent=(
                "strategy"
                if requested_mode == "strategy" or "一周" in text or "策略" in text
                else "reorder"
                if "上次" in text or "复购" in text
                else "order"
                if requested_mode in {"order", "auto_order"}
                else "recommend"
            ),
            needs_confirmation=("确认" in text or "下单" in text),
            health_sensitive=any(word in lowered for word in ("感冒", "发烧", "咳嗽", "生病")),
        )
        if self._chain is None:
            return fallback
        try:
            result = self._chain.invoke(
                "Classify this food-ordering request. Return only the schema. "
                "Health mentions must remain general dietary filters, never diagnoses. "
                f"Request: {text}"
            )
            return result if isinstance(result, IntentOutput) else IntentOutput.model_validate(result)
        except Exception:
            return fallback
