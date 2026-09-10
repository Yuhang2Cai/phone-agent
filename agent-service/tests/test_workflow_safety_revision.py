from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from fandazi_agent.config import Settings
from fandazi_agent.graph import FoodOrderingAgent
from fandazi_agent.models import AgentRunRequest, IntentOutput, ModelClassificationError, ToolResult
from fandazi_agent.spring_client import SpringBootToolClient


class FakeSpringBootToolClient(SpringBootToolClient):
    def __init__(self):
        self.requests = []

    def call(self, request):
        self.requests.append(request)
        data = {
            "search_merchants": {"merchants": [{"id": "merchant-1"}]},
            "create_cart_draft": {"cartId": f"cart-{len(self.requests)}", "total": 18},
            "validate_cart": {"valid": True, "total": 18},
            "create_order": {"orderId": f"order-{len(self.requests)}"},
        }
        return ToolResult(ok=True, data=data.get(request.tool_name, {}))


def request(
    text: str,
    *,
    user_id: str = "u-1",
    session_id: str = "s-1",
    trace_id: str = "trace-1",
    mode: str = "order",
) -> AgentRunRequest:
    return AgentRunRequest(
        userId=user_id,
        sessionId=session_id,
        text=text,
        mode=mode,
        traceId=trace_id,
    )


def test_negative_confirmation_cancels_paused_workflow_without_create_order():
    """Break caught: a negated confirmation creates an order."""
    client = FakeSpringBootToolClient()
    agent = FoodOrderingAgent(client)

    paused = agent.run(request("宫保鸡丁"))
    result = agent.run(request("不要下单", trace_id="trace-2"))

    assert paused.status == "awaiting_confirmation"
    assert result.next_action == "cancelled"
    assert "create_order" not in [item.tool_name for item in client.requests]


def test_llm_classification_cannot_authorize_initial_order():
    """Break caught: a probabilistic model boolean can skip explicit confirmation."""
    client = FakeSpringBootToolClient()
    agent = FoodOrderingAgent(client)
    agent.model.classify = lambda *_: IntentOutput(intent="order", needs_confirmation=True)

    result = agent.run(request("宫保鸡丁", trace_id="trace-llm"))

    assert result.status == "awaiting_confirmation"
    assert "create_order" not in [item.tool_name for item in client.requests]


def test_same_session_from_another_user_cannot_resume_first_users_cart():
    """Break caught: session-only lookup lets another user submit a foreign cart."""
    client = FakeSpringBootToolClient()
    agent = FoodOrderingAgent(client)
    agent.run(request("宫保鸡丁", user_id="u-1", session_id="shared"))

    result = agent.run(request("确认", user_id="u-2", session_id="shared", trace_id="trace-u2"))

    assert result.status == "needs_input"
    assert "create_order" not in [item.tool_name for item in client.requests]


def test_terminal_workflow_is_removed_so_same_session_starts_a_fresh_cart():
    """Break caught: a later request reuses completed operations from the old order."""
    client = FakeSpringBootToolClient()
    agent = FoodOrderingAgent(client)
    agent.run(request("宫保鸡丁", session_id="reuse"))
    agent.run(request("确认", session_id="reuse", trace_id="trace-confirm"))

    result = agent.run(request("鱼香肉丝", session_id="reuse", trace_id="trace-next"))

    assert result.status == "awaiting_confirmation"
    assert [item.tool_name for item in client.requests].count("create_cart_draft") == 2


def test_paused_request_executes_real_langgraph_checkpoint_and_interrupt():
    """Break caught: run bypasses LangGraph, leaving no checkpoint for the paused workflow."""
    client = FakeSpringBootToolClient()
    agent = FoodOrderingAgent(client)

    result = agent.run(request("宫保鸡丁", session_id="graph-checkpoint"))
    checkpoint = agent.langgraph.get_state(agent.workflow_config("u-1", "graph-checkpoint"))

    assert result.status == "awaiting_confirmation"
    assert checkpoint.values["agent_state"]["next_action"] == "wait_confirmation"
    assert checkpoint.next is not None


def test_strategy_step_limit_is_a_hard_failure_in_every_branch():
    """Break caught: strategy reports success after the graph step budget is exceeded."""
    client = FakeSpringBootToolClient()
    agent = FoodOrderingAgent(
        client,
        settings=Settings(None, None, None, "http://localhost:8080", 1, 8, 100),
    )

    result = agent.run(request("制定一周午餐策略", mode="strategy"))

    assert result.status == "failed"
    assert result.error == "图执行步数超过上限"


def test_effectful_operation_reuses_workflow_scoped_idempotency_key_after_restore():
    """Break caught: resumed create-order operation derives idempotency from a new trace ID."""
    client = FakeSpringBootToolClient()
    agent = FoodOrderingAgent(client)
    agent.run(request("宫保鸡丁", session_id="stable-key", trace_id="trace-a"))
    snapshot = agent.export_session("u-1", "stable-key")
    restored = FoodOrderingAgent(client)
    restored.restore_session("u-1", "stable-key", snapshot)

    restored.run(request("确认", session_id="stable-key", trace_id="trace-b"))

    create_order = next(item for item in client.requests if item.tool_name == "create_order")
    assert create_order.idempotency_key.startswith(snapshot["workflow_id"] + ":create_order")
    assert "trace-b" not in create_order.idempotency_key


def test_explicit_zero_order_limit_is_not_replaced_with_default_limit():
    """Break caught: explicit zero limit is treated as absent and permits an order."""
    client = FakeSpringBootToolClient()
    agent = FoodOrderingAgent(client, max_order_amount=0)

    result = agent.run(request("宫保鸡丁"))

    assert result.status == "failed"
    assert result.error == "金额超过单次限额（0）"


def test_restoring_snapshot_for_a_different_user_is_rejected():
    """Break caught: a serialized pause can be attached to another user's session."""
    client = FakeSpringBootToolClient()
    agent = FoodOrderingAgent(client)
    agent.run(request("宫保鸡丁", user_id="u-1", session_id="owned"))
    snapshot = agent.export_session("u-1", "owned")

    with pytest.raises(ValueError, match="owner"):
        FoodOrderingAgent(client).restore_session("u-2", "owned", snapshot)


def test_order_mode_fails_closed_and_records_event_when_configured_model_fails():
    """Break caught: an LLM outage silently guesses an order intent and reaches tools."""
    client = FakeSpringBootToolClient()
    agent = FoodOrderingAgent(client)

    def broken_model(*_):
        raise ModelClassificationError("llm_classification_failed")

    agent.model.classify = broken_model
    result = agent.run(request("宫保鸡丁", mode="order"))

    assert result.status == "failed"
    assert result.error == "模型意图识别失败"
    assert any(event.node == "parse_intent" and event.kind == "error" for event in result.events)
    assert not client.requests
