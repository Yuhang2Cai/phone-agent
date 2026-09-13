import json
from pathlib import Path

import pytest

from fandazi_agent.graph import FoodOrderingAgent
from fandazi_agent.models import AgentRunRequest, ToolResult
from fandazi_agent.spring_client import SpringBootToolClient


class ScenarioToolClient(SpringBootToolClient):
    def __init__(self, *, total=18, address_missing=False, last_order_available=True):
        self.requests = []
        self.total = total
        self.address_missing = address_missing
        self.last_order_available = last_order_available

    def call(self, tool_request):
        self.requests.append(tool_request)
        if tool_request.tool_name == "search_merchants":
            return ToolResult(ok=True, data={"merchants": [{"id": "m-1", "name": "清粥小菜"}]})
        if tool_request.tool_name == "get_last_order":
            if not self.last_order_available:
                return ToolResult(ok=False, error="上次订单菜品已下架", error_kind="rejected")
            return ToolResult(ok=True, data={"cartId": "last-cart", "dish": "南瓜小米粥"})
        if tool_request.tool_name == "create_cart_draft":
            return ToolResult(ok=True, data={"cartId": "cart-1", "merchantName": "清粥小菜"})
        if tool_request.tool_name == "validate_cart":
            if self.address_missing:
                return ToolResult(ok=False, error="需要选择地址", error_kind="rejected")
            return ToolResult(ok=True, data={"valid": True, "total": self.total, "addressId": "addr-default"})
        if tool_request.tool_name == "create_order":
            return ToolResult(ok=True, data={"orderId": "order-1"})
        if tool_request.tool_name == "pay_mock_order":
            return ToolResult(ok=True, data={"paid": True})
        return ToolResult(ok=False, error="unexpected_tool", error_kind="rejected")


SCENARIOS = json.loads(
    Path(__file__).resolve().parents[2].joinpath("data", "seed", "agent-scenarios.json").read_text(encoding="utf-8")
)


@pytest.mark.parametrize("scenario", SCENARIOS, ids=[item["id"] for item in SCENARIOS])
def test_seed_agent_scenarios_have_expected_tool_sequence_confirmation_and_result(scenario):
    client = ScenarioToolClient(
        total=88 if scenario["id"] == "over-budget" else 18,
        address_missing=scenario["id"] == "missing-address",
        last_order_available=scenario["id"] != "reorder-unlisted",
    )
    agent = FoodOrderingAgent(client, max_order_amount=50)
    result = agent.run(request_for(scenario))

    tool_sequence = [request.tool_name for request in client.requests]

    expected = {
        "spicy-under-30": (["search_merchants"], "completed", False),
        "reorder": (["get_last_order", "create_cart_draft", "validate_cart"], "awaiting_confirmation", True),
        "mild-cold": (["search_merchants"], "completed", False),
        "no-cilantro-default-address": (["search_merchants", "create_cart_draft", "validate_cart"], "awaiting_confirmation", True),
        "missing-address": (["search_merchants", "create_cart_draft", "validate_cart"], "failed", False),
        "price-change": (["search_merchants", "create_cart_draft", "validate_cart"], "awaiting_confirmation", True),
        "reorder-unlisted": (["get_last_order"], "failed", False),
        "over-budget": (["search_merchants", "create_cart_draft", "validate_cart"], "failed", False),
        "illegal-parameters": ([], "needs_input", False),
    }[scenario["id"]]
    expected_tools, expected_status, expects_confirmation = expected

    assert tool_sequence == expected_tools
    assert result.status == expected_status
    assert (result.status == "awaiting_confirmation") is expects_confirmation
    assert "medical" not in result.message.lower()


def request_for(scenario):
    mode = "recommend" if scenario["id"] in {"spicy-under-30", "mild-cold"} else "order"
    text = "帮我点个外卖" if scenario["id"] == "illegal-parameters" else scenario["prompt"]
    return AgentRunRequest(userId="u-1", sessionId=f"seed-{scenario['id']}", text=text, mode=mode, traceId=f"trace-{scenario['id']}")
