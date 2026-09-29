"""公开接口与后端工具的数据契约。"""

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


class AgentRunRequestV1(BaseModel):
    """定义 Spring Boot 调用独立 Agent 服务时使用的 V1 请求。"""

    model_config = ConfigDict(populate_by_name=True)

    schema_version: Literal["1.0"] = Field(alias="schemaVersion")
    user_id: str = Field(alias="userId", min_length=1)
    session_id: str = Field(alias="sessionId", min_length=1)
    trace_id: str = Field(alias="traceId", min_length=1)
    # 兼容已发布契约中的 runId；缺少时由 Agent 服务生成新的运行标识。
    run_id: str | None = Field(default=None, alias="runId")
    query: str = Field(min_length=1, max_length=2000)
    context: dict[str, Any] = Field(default_factory=dict)

    def to_legacy_request(self, run_id: str) -> "AgentRunRequest":
        """把 V1 请求转换成现有 LangGraph 使用的内部请求模型。"""
        mode = self.context.get("mode", "recommend")
        if mode not in {"recommend", "order", "auto_order", "strategy"}:
            mode = "recommend"
        return AgentRunRequest(
            userId=self.user_id,
            sessionId=self.session_id,
            text=self.query,
            mode=mode,
            traceId=self.trace_id,
        )


class AgentEventV1(BaseModel):
    """表示可被 Spring 逐行消费的公开 Agent 事件。"""

    schema_version: Literal["1.0"] = Field(default="1.0", alias="schemaVersion")
    event_id: str = Field(alias="eventId")
    event_type: Literal[
        "run_started", "intent_decided", "progress", "text_delta", "card",
        "confirmation_required", "tool_result", "run_completed", "error"
    ] = Field(alias="eventType")
    trace_id: str = Field(alias="traceId")
    run_id: str = Field(alias="runId")
    plan_id: str | None = Field(default=None, alias="planId")
    sequence: int
    status: Literal["running", "waiting_confirmation", "succeeded", "failed", "cancelled"]
    payload: dict[str, Any] = Field(default_factory=dict)


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
    workflow_id: str | None = Field(default=None, alias="workflowId")
    payload: dict[str, Any] = Field(default_factory=dict)


class ToolResult(BaseModel):
    ok: bool
    data: dict[str, Any] = Field(default_factory=dict)
    error: str | None = None
    # 普通业务拒绝默认是终态，只有明确分类后才可重试。
    error_kind: Literal["rejected", "retryable", "uncertain"] | None = None


class AgentResult(BaseModel):
    status: Literal["completed", "needs_input", "awaiting_confirmation", "failed"]
    message: str
    next_action: str
    candidate_cart: dict[str, Any] = Field(default_factory=dict)
    events: list[AgentEvent] = Field(default_factory=list)
    error: str | None = None


class IntentOutput(BaseModel):
    """定义模型适配器必须返回的最小结构化结果。"""

    intent: Literal["recommend", "order", "reorder", "strategy"]
    needs_confirmation: bool = False
    health_sensitive: bool = False


class LangChainModelAdapter:
    """可选的结构化输出适配器；缺少依赖时使用确定性回退。"""

    """处理 `__init__` 相关逻辑。"""
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
                # 未安装可选依赖时仍允许测试核心服务。
                self._chain = None

    """处理 `fallback_classify` 相关逻辑。"""
    def fallback_classify(self, text: str, requested_mode: str) -> IntentOutput:
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
            needs_confirmation=False,
            health_sensitive=any(word in lowered for word in ("感冒", "发烧", "咳嗽", "生病")),
        )
        return fallback

    """处理 `classify` 相关逻辑。"""
    def classify(self, text: str, requested_mode: str) -> IntentOutput:
        fallback = self.fallback_classify(text, requested_mode)
        if self._chain is None:
            return fallback
        try:
            result = self._chain.invoke(
                "Classify this food-ordering request. Return only the schema. "
                "Health mentions must remain general dietary filters, never diagnoses. "
                f"Request: {text}"
            )
            return result if isinstance(result, IntentOutput) else IntentOutput.model_validate(result)
        except Exception as exc:
            raise ModelClassificationError("llm_classification_failed") from exc


class ModelClassificationError(RuntimeError):
    """已配置的模型未能生成有效的结构化意图。"""
