from __future__ import annotations

import sys
import copy
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from fandazi_agent.config import Settings
from fandazi_agent.graph import FoodOrderingAgent
from fandazi_agent.models import AgentRunRequest, IntentOutput, ModelClassificationError, ToolResult
from fandazi_agent.spring_client import SpringBootToolClient
from fandazi_agent.state import AgentState


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


def test_restore_rejects_unsupported_version_and_tampered_workflow_id():
    """Break caught: arbitrary snapshot version or thread ID becomes a trusted graph workflow."""
    client = FakeSpringBootToolClient()
    agent = FoodOrderingAgent(client)
    agent.run(request("宫保鸡丁", session_id="trusted"))
    snapshot = agent.export_session("u-1", "trusted")

    unsupported = {**snapshot, "workflow_version": 999}
    tampered = {**snapshot, "workflow_id": "00000000-0000-0000-0000-000000000000"}

    with pytest.raises(ValueError, match="workflow_version"):
        FoodOrderingAgent(client).restore_session("u-1", "trusted", unsupported)
    with pytest.raises(ValueError, match="workflow"):
        FoodOrderingAgent(client).restore_session("u-1", "trusted", tampered)


def test_non_confirmation_reply_can_pause_again_then_confirm_without_graph_update_error():
    """Break caught: a second interrupt makes later confirmation crash LangGraph."""
    client = FakeSpringBootToolClient()
    agent = FoodOrderingAgent(client)
    agent.run(request("宫保鸡丁", session_id="multi-reply"))

    waiting_again = agent.run(request("我再想想", session_id="multi-reply", trace_id="trace-later"))
    completed = agent.run(request("确认", session_id="multi-reply", trace_id="trace-confirm"))

    assert waiting_again.status == "awaiting_confirmation"
    assert completed.status == "completed"
    assert [item.tool_name for item in client.requests].count("create_order") == 1


def test_order_creation_survives_payment_failure_and_retry_only_retries_payment():
    """Break caught: payment failure clears the workflow and repeats create_order on retry."""

    class PaymentFailsOnceClient(FakeSpringBootToolClient):
        def __init__(self):
            super().__init__()
            self.payment_attempts = 0

        def call(self, tool_request):
            if tool_request.tool_name == "pay_mock_order":
                self.requests.append(tool_request)
                self.payment_attempts += 1
                return ToolResult(ok=self.payment_attempts > 1, error="payment_pending")
            return super().call(tool_request)

    client = PaymentFailsOnceClient()
    agent = FoodOrderingAgent(client)

    first = agent.run(request("宫保鸡丁", mode="auto_order", session_id="payment-retry"))
    retry = agent.run(request("继续支付", mode="auto_order", session_id="payment-retry", trace_id="trace-retry"))

    create_orders = [item for item in client.requests if item.tool_name == "create_order"]
    payments = [item for item in client.requests if item.tool_name == "pay_mock_order"]
    assert first.status == "failed"
    assert first.next_action == "payment_retry"
    assert retry.status == "completed"
    assert len(create_orders) == 1
    assert len(payments) == 2


def test_snapshot_signature_rejects_user_session_delimiter_collision():
    """Break caught: delimiter-containing owner fields produce the same signed identity bytes."""
    client = FakeSpringBootToolClient()
    agent = FoodOrderingAgent(client)
    agent.run(request("宫保鸡丁", user_id="a|b", session_id="c"))
    snapshot = agent.export_session("a|b", "c")
    collided = {**snapshot, "user_id": "a", "session_id": "b|c"}

    with pytest.raises(ValueError, match="owner|workflow"):
        FoodOrderingAgent(client).restore_session("a", "b|c", collided)


@pytest.mark.parametrize(
    ("path", "replacement"),
    [
        (("candidate_cart", "cartId"), "tampered-cart"),
        (("authorization", "confirmed"), True),
        (("next_action",), "create_order"),
        (("completed_tools",), ["create_order"]),
    ],
)
def test_snapshot_signature_rejects_tampered_recoverable_business_state(path, replacement):
    """Break caught: signed workflow identity accepts a modified recovery decision or cart."""
    client = FakeSpringBootToolClient()
    agent = FoodOrderingAgent(client)
    agent.run(request("宫保鸡丁", session_id="tamper-state"))
    snapshot = copy.deepcopy(agent.export_session("u-1", "tamper-state"))
    target = snapshot
    for key in path[:-1]:
        target = target.setdefault(key, {})
    target[path[-1]] = replacement

    with pytest.raises(ValueError, match="workflow"):
        FoodOrderingAgent(client).restore_session("u-1", "tamper-state", snapshot)


def test_uncertain_create_order_keeps_workflow_and_reuses_same_operation_key():
    """Break caught: create-order timeout clears state and retries with a new logical operation."""

    class CreateUncertainOnceClient(FakeSpringBootToolClient):
        def __init__(self):
            super().__init__()
            self.create_attempts = 0

        def call(self, tool_request):
            if tool_request.tool_name == "create_order":
                self.requests.append(tool_request)
                self.create_attempts += 1
                if self.create_attempts == 1:
                    return ToolResult(ok=False, error="spring_tool_unavailable: timeout", error_kind="uncertain")
                return ToolResult(ok=True, data={"orderId": "reconciled-order"})
            return super().call(tool_request)

    client = CreateUncertainOnceClient()
    agent = FoodOrderingAgent(client)
    first = agent.run(request("宫保鸡丁", mode="auto_order", session_id="order-recovery"))
    retry = agent.run(request("继续", mode="auto_order", session_id="order-recovery", trace_id="trace-retry"))

    create_orders = [item for item in client.requests if item.tool_name == "create_order"]
    assert first.status == "failed"
    assert first.next_action == "order_recovery"
    assert retry.status == "completed"
    assert len(create_orders) == 2
    assert create_orders[0].idempotency_key == create_orders[1].idempotency_key


def test_confirmation_uses_current_request_trace_id_without_multi_channel_update():
    """Break caught: resumed create_order retains the initial request trace instead of the confirmation trace."""
    client = FakeSpringBootToolClient()
    agent = FoodOrderingAgent(client)
    agent.run(request("宫保鸡丁", session_id="trace-resume", trace_id="trace-a"))
    agent.run(request("确认", session_id="trace-resume", trace_id="trace-b"))

    create_order = next(item for item in client.requests if item.tool_name == "create_order")
    assert create_order.trace_id == "trace-b"


def test_confirmation_budget_is_reset_for_each_http_run():
    """Break caught: a valid confirmation inherits graph steps from the paused HTTP request."""
    client = FakeSpringBootToolClient()
    settings = Settings(None, None, None, "http://localhost:8080", 6, 8, 100)
    agent = FoodOrderingAgent(client, settings=settings)
    paused = agent.run(request("宫保鸡丁", session_id="budget-reset"))
    confirmed = agent.run(request("确认", session_id="budget-reset", trace_id="trace-confirm"))

    assert paused.status == "awaiting_confirmation"
    assert confirmed.status == "completed"


def test_restored_confirmation_rejects_candidate_cart_replacement_after_authorization():
    """Break caught: a signed recovery can swap the cart after the user saw another one."""
    client = FakeSpringBootToolClient()
    agent = FoodOrderingAgent(client)
    agent.run(request("宫保鸡丁", session_id="authorized-cart"))
    snapshot = copy.deepcopy(agent.export_session("u-1", "authorized-cart"))
    snapshot["candidate_cart"]["cartDraftId"] = "cart-tampered"
    tampered_state = AgentState.model_validate(snapshot)
    snapshot["workflow_signature"] = agent._sign_workflow(tampered_state)
    restored = FoodOrderingAgent(client)
    restored.restore_session("u-1", "authorized-cart", snapshot)

    result = restored.run(request("确认", session_id="authorized-cart", trace_id="trace-confirm"))

    assert result.status == "failed"
    assert result.next_action == "error"
    assert "授权" in (result.error or "")
    assert "create_order" not in [item.tool_name for item in client.requests]
