from __future__ import annotations

import sys
import warnings
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from fandazi_agent.graph import FoodOrderingAgent
from fandazi_agent.config import Settings
from fandazi_agent.main import create_app
from fandazi_agent.models import AgentRunRequest, ToolResult
from fandazi_agent.spring_client import SpringBootToolClient
from fandazi_agent.tools import langchain_tool_descriptions


class FakeSpringBootToolClient(SpringBootToolClient):
    """Contract-level fake: the agent never owns backend ordering logic."""

    def __init__(self, responses: dict[str, ToolResult] | None = None):
        self.responses = responses or {}
        self.requests = []

    def call(self, request):
        self.requests.append(request)
        return self.responses.get(request.tool_name, ToolResult(ok=True, data={}))


def request(text: str, *, mode: str = "recommend", session_id: str = "s-1") -> AgentRunRequest:
    return AgentRunRequest(
        userId="u-1", sessionId=session_id, text=text, mode=mode, traceId="trace-1"
    )


def test_recommendation_searches_candidates_and_returns_structured_result():
    """Break caught: recommendation path no longer uses the approved search tool."""
    client = FakeSpringBootToolClient(
        {"search_merchants": ToolResult(ok=True, data={"merchants": [{"id": "m-1"}]})}
    )

    result = FoodOrderingAgent(client).run(request("推荐一份清淡午餐"))

    assert result.status == "completed"
    assert result.next_action == "recommendation_ready"
    assert result.candidate_cart == {"merchants": [{"id": "m-1"}]}
    assert [item.tool_name for item in client.requests] == ["search_merchants"]


def test_missing_information_returns_question_without_order_tool():
    """Break caught: vague ordering request creates an order before collecting required data."""
    client = FakeSpringBootToolClient()

    result = FoodOrderingAgent(client).run(request("帮我点个外卖", mode="order"))

    assert result.status == "needs_input"
    assert result.next_action == "collect_missing_info"
    assert not client.requests


def test_confirmation_creates_order_once_then_waits_for_payment():
    """Break caught: confirmed order skips validation or omits the create-order boundary call."""
    client = FakeSpringBootToolClient(
        {
            "create_cart_draft": ToolResult(ok=True, data={"cartId": "c-1", "total": 28}),
            "validate_cart": ToolResult(ok=True, data={"valid": True, "total": 28}),
            "create_order": ToolResult(ok=True, data={"orderId": "o-1", "total": 28}),
        }
    )

    agent = FoodOrderingAgent(client)
    paused = agent.run(request("宫保鸡丁", mode="order"))
    result = agent.run(request("确认", mode="order"))

    assert paused.status == "awaiting_confirmation"
    assert result.status == "completed"
    assert result.next_action == "payment_followup"
    assert [item.tool_name for item in client.requests] == [
        "search_merchants",
        "create_cart_draft",
        "validate_cart",
        "create_order",
    ]


def test_auto_order_pays_once_after_order_creation():
    """Break caught: auto mode makes payment without a backend-created order."""
    client = FakeSpringBootToolClient(
        {
            "create_cart_draft": ToolResult(ok=True, data={"cartId": "c-1", "total": 28}),
            "validate_cart": ToolResult(ok=True, data={"valid": True, "total": 28}),
            "create_order": ToolResult(ok=True, data={"orderId": "o-1", "total": 28}),
            "pay_mock_order": ToolResult(ok=True, data={"paid": True}),
        }
    )

    result = FoodOrderingAgent(client).run(request("宫保鸡丁", mode="auto_order"))

    assert result.status == "completed"
    assert result.next_action == "completed"
    assert [item.tool_name for item in client.requests][-2:] == ["create_order", "pay_mock_order"]


def test_long_term_strategy_never_calls_ordering_tools():
    """Break caught: strategy advice mutates a cart or creates an order."""
    client = FakeSpringBootToolClient()

    result = FoodOrderingAgent(client).run(request("帮我制定一周工作日午餐策略", mode="strategy"))

    assert result.status == "completed"
    assert result.next_action == "strategy_ready"
    assert not client.requests


def test_exact_reorder_calls_last_order_then_creates_new_order():
    """Break caught: exact reorder does not fetch its source order through the backend boundary."""
    client = FakeSpringBootToolClient(
        {
            "get_last_order": ToolResult(ok=True, data={"items": [{"dishId": "d-1"}]}),
            "create_cart_draft": ToolResult(ok=True, data={"cartId": "c-1", "total": 18}),
            "validate_cart": ToolResult(ok=True, data={"valid": True, "total": 18}),
            "create_order": ToolResult(ok=True, data={"orderId": "o-1"}),
        }
    )

    agent = FoodOrderingAgent(client)
    paused = agent.run(request("按上次一样下单", mode="order"))
    result = agent.run(request("确认", mode="order"))

    assert paused.status == "awaiting_confirmation"
    assert result.status == "completed"
    assert [item.tool_name for item in client.requests] == [
        "get_last_order",
        "create_cart_draft",
        "validate_cart",
        "create_order",
    ]


def test_reorder_stops_when_last_order_is_unavailable():
    """Break caught: unavailable reorder still reaches cart/order tools."""
    client = FakeSpringBootToolClient(
        {"get_last_order": ToolResult(ok=False, error="上次菜品已下架")}
    )

    result = FoodOrderingAgent(client).run(request("按上次一样下单", mode="order"))

    assert result.status == "failed"
    assert result.error == "上次菜品已下架"
    assert [item.tool_name for item in client.requests] == ["get_last_order"]


def test_amount_limit_stops_before_create_order():
    """Break caught: cart above the configured limit is submitted as an order."""
    client = FakeSpringBootToolClient(
        {
            "create_cart_draft": ToolResult(ok=True, data={"cartId": "c-1", "total": 88}),
            "validate_cart": ToolResult(ok=True, data={"valid": True, "total": 88}),
        }
    )
    agent = FoodOrderingAgent(client, max_order_amount=50)

    result = agent.run(request("确认下单，豪华套餐", mode="order"))

    assert result.status == "failed"
    assert "金额超过单次限额" in (result.error or "")
    assert [item.tool_name for item in client.requests] == ["search_merchants", "create_cart_draft", "validate_cart"]


def test_illegal_tool_is_rejected_without_network_call():
    """Break caught: caller can invoke a tool that is not in the fixed allowlist."""
    client = FakeSpringBootToolClient()

    result = FoodOrderingAgent(client).invoke_tool("drop_database", request("x"), {})

    assert result.ok is False
    assert result.error == "tool_not_allowed"
    assert not client.requests


def test_wait_confirmation_state_resumes_without_repeating_cart_creation():
    """Break caught: restoring a paused graph repeats completed tool side effects."""
    client = FakeSpringBootToolClient(
        {
            "create_cart_draft": ToolResult(ok=True, data={"cartId": "c-1", "total": 28}),
            "validate_cart": ToolResult(ok=True, data={"valid": True, "total": 28}),
            "create_order": ToolResult(ok=True, data={"orderId": "o-1"}),
        }
    )
    agent = FoodOrderingAgent(client)

    paused = agent.run(request("宫保鸡丁", mode="order", session_id="s-resume"))
    resumed = agent.run(request("确认", mode="order", session_id="s-resume"))

    assert paused.status == "awaiting_confirmation"
    assert resumed.status == "completed"
    assert [item.tool_name for item in client.requests] == [
        "search_merchants",
        "create_cart_draft",
        "validate_cart",
        "create_order",
    ]


def test_serialized_confirmation_state_can_be_restored_without_replaying_tools():
    """Break caught: a persisted pause loses completed-tool markers after process restart."""
    client = FakeSpringBootToolClient(
        {
            "create_cart_draft": ToolResult(ok=True, data={"cartId": "c-1", "total": 28}),
            "validate_cart": ToolResult(ok=True, data={"valid": True, "total": 28}),
            "create_order": ToolResult(ok=True, data={"orderId": "o-1"}),
        }
    )
    first_agent = FoodOrderingAgent(client)
    first_agent.run(request("宫保鸡丁", mode="order", session_id="s-snapshot"))

    snapshot = first_agent.export_session("u-1", "s-snapshot")
    restarted_agent = FoodOrderingAgent(client)
    restarted_agent.restore_session("u-1", "s-snapshot", snapshot)
    result = restarted_agent.run(request("确认", mode="order", session_id="s-snapshot"))

    assert result.status == "completed"
    assert [item.tool_name for item in client.requests] == [
        "search_merchants",
        "create_cart_draft",
        "validate_cart",
        "create_order",
    ]


def test_health_related_text_is_general_food_filter_not_medical_advice():
    """Break caught: illness wording causes a diagnosis, prescription, or treatment claim."""
    client = FakeSpringBootToolClient(
        {"search_merchants": ToolResult(ok=True, data={"merchants": []})}
    )

    result = FoodOrderingAgent(client).run(request("我感冒了，推荐吃什么", mode="recommend"))

    assert result.status == "completed"
    assert "医疗建议" in result.message
    assert "治疗" not in result.message
    assert [item.tool_name for item in client.requests] == ["search_merchants"]


def test_tool_request_always_contains_identity_trace_and_idempotency_key():
    """Break caught: backend tool request omits a required audit/idempotency field."""
    client = FakeSpringBootToolClient(
        {"search_merchants": ToolResult(ok=True, data={"merchants": []})}
    )

    FoodOrderingAgent(client).run(request("推荐午餐"))

    sent = client.requests[0]
    assert sent.user_id == "u-1"
    assert sent.session_id == "s-1"
    assert sent.trace_id == "trace-1"
    assert sent.idempotency_key


def test_graph_step_limit_returns_explainable_error_before_a_tool_call():
    """Break caught: a graph over its step budget continues into a backend tool call."""
    client = FakeSpringBootToolClient()
    settings = Settings(
        llm_base_url=None,
        llm_api_key=None,
        llm_model=None,
        spring_boot_base_url="http://localhost:8080",
        max_graph_steps=1,
        max_tool_calls=8,
        max_order_amount=100,
    )

    result = FoodOrderingAgent(client, settings=settings).run(request("推荐午餐"))

    assert result.status == "failed"
    assert result.error == "图执行步数超过上限"
    assert not client.requests
    assert result.events[-1].kind == "error"


def test_tool_loop_limit_returns_an_event_before_second_backend_call():
    """Break caught: a tool loop past its configured budget still calls Spring Boot."""
    client = FakeSpringBootToolClient(
        {"create_cart_draft": ToolResult(ok=True, data={"cartId": "c-1", "total": 18})}
    )
    settings = Settings(
        llm_base_url=None,
        llm_api_key=None,
        llm_model=None,
        spring_boot_base_url="http://localhost:8080",
        max_graph_steps=16,
        max_tool_calls=1,
        max_order_amount=100,
    )

    result = FoodOrderingAgent(client, settings=settings).run(request("确认下单，宫保鸡丁", mode="order"))

    assert result.status == "failed"
    assert result.error == "工具调用次数超过上限"
    assert [item.tool_name for item in client.requests] == ["search_merchants"]
    assert any(event.node == "tool_guard" for event in result.events)


def test_internal_fastapi_route_returns_agent_result_contract():
    """Break caught: the declared internal route does not parse or return the public contract."""
    from fastapi.testclient import TestClient

    client = FakeSpringBootToolClient(
        {"search_merchants": ToolResult(ok=True, data={"merchants": []})}
    )
    app = create_app(agent=FoodOrderingAgent(client))

    response = TestClient(app).post(
        "/internal/agent/run",
        json={
            "userId": "u-1",
            "sessionId": "s-http",
            "text": "推荐午餐",
            "mode": "recommend",
            "traceId": "trace-http",
        },
    )

    assert response.status_code == 200
    assert response.json()["next_action"] == "recommendation_ready"


def test_langchain_tool_descriptions_use_a_real_payload_schema_without_runtime_warning():
    """Break caught: tool schema generation uses an invalid variadic parameter."""
    with warnings.catch_warnings(record=True) as recorded:
        warnings.simplefilter("always")
        descriptions = langchain_tool_descriptions()

    assert len(descriptions) == 8
    assert not [item for item in recorded if "fields may not start" in str(item.message)]
