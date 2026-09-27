"""验证该模块的正常流程和错误边界。"""

from __future__ import annotations

import sys
from pathlib import Path

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from fandazi_agent.models import ToolRequest
from fandazi_agent.config import Settings
from fandazi_agent.spring_client import SpringBootToolClient


"""验证 `order_request` 覆盖场景的预期行为与边界。"""
def order_request():
    return ToolRequest(tool_name="create_order", userId="u-1", sessionId="s-1", traceId="trace-current", idempotencyKey="workflow-1:create_order", payload={"cartId": "cart-1"})


@pytest.mark.parametrize(
    ("status", "body", "expected_kind"),
    [
        (200, {"ok": False, "error": "authorization_denied"}, None),
        (200, {"ok": False, "error": "insufficient_stock", "error_kind": "rejected"}, "rejected"),
        (200, {"ok": False, "error": "busy", "error_kind": "retryable"}, "retryable"),
        (200, {"ok": False, "error": "response_lost", "error_kind": "uncertain"}, "uncertain"),
        (400, {"ok": False, "error": "invalid_request"}, "rejected"),
        (401, {"message": "unauthorized"}, "rejected"),
        (403, {"ok": False, "error": "authorization_denied", "error_kind": "retryable"}, "rejected"),
        (409, {"ok": False, "error": "invalid_order_state"}, "rejected"),
        (422, {"ok": False, "error": "amount_exceeded"}, "rejected"),
        (408, {"message": "request timeout"}, "uncertain"),
        (408, {"ok": False, "error": "request_timeout"}, "uncertain"),
        (429, {"message": "busy"}, "retryable"),
        (429, {"ok": False, "error": "busy"}, "retryable"),
        (500, {"message": "internal error"}, "uncertain"),
        (500, {"ok": False, "error": "internal_error"}, "uncertain"),
        (502, {"message": "bad gateway"}, "uncertain"),
        (502, {"ok": False, "error": "response_lost"}, "uncertain"),
        (503, {"ok": False, "error": "authorization_denied", "error_kind": "rejected"}, "rejected"),
        (200, {"unexpected": "response"}, "uncertain"),
    ],
)
def test_spring_response_classification_distinguishes_rejection_from_uncertainty(monkeypatch, status, body, expected_kind):
    """防止回归：业务拒绝与传输故障必须保留不同语义。"""
    response = httpx.Response(status, json=body, request=httpx.Request("POST", "http://spring/internal/tools/create_order"))
    monkeypatch.setattr(httpx, "post", lambda *args, **kwargs: response)

    result = SpringBootToolClient("http://spring").call(order_request())

    assert result.ok is False
    assert result.error_kind == expected_kind
    if body.get("ok") is False:
        assert result.error == body["error"]


@pytest.mark.parametrize("exception_type", [httpx.ReadTimeout, httpx.ConnectError, httpx.RemoteProtocolError])
def test_spring_transport_errors_are_explicitly_uncertain(monkeypatch, exception_type):
    """防止回归：超时必须标记结果不确定，重试不能另建订单。"""
    """验证 `fail` 覆盖场景的预期行为与边界。"""
    def fail(*args, **kwargs):
        raise exception_type("connection lost")

    monkeypatch.setattr(httpx, "post", fail)
    result = SpringBootToolClient().call(order_request())
    assert result.ok is False
    assert result.error_kind == "uncertain"


@pytest.mark.parametrize(("status", "expected_kind"), [(200, "uncertain"), (403, "rejected"), (502, "uncertain")])
def test_malformed_response_uses_http_semantics_not_error_text(monkeypatch, status, expected_kind):
    """防止回归：无法解码的成功响应与权限拒绝必须正确分类。"""
    response = httpx.Response(status, content=b"<html>response unavailable</html>", request=httpx.Request("POST", "http://spring/internal/tools/create_order"))
    monkeypatch.setattr(httpx, "post", lambda *args, **kwargs: response)
    result = SpringBootToolClient().call(order_request())
    assert result.ok is False
    assert result.error_kind == expected_kind


def test_http_error_only_body_preserves_backend_business_message(monkeypatch):
    """防止回归：后端业务拒绝不得简化为通用状态码。"""
    response = httpx.Response(
        400,
        json={"error": "缺少明确菜品"},
        request=httpx.Request("POST", "http://spring/internal/tools/create_cart_draft"),
    )
    monkeypatch.setattr(httpx, "post", lambda *args, **kwargs: response)

    result = SpringBootToolClient("http://spring").call(order_request())

    assert result.ok is False
    assert result.error == "缺少明确菜品"
    assert result.error_kind == "rejected"


def test_spring_tool_client_sends_the_configured_internal_token(monkeypatch):
    """防止回归：缺少共享令牌时后端工具调用必须被拒绝。"""
    captured = {}
    response = httpx.Response(200, json={"ok": True, "data": {}}, request=httpx.Request("POST", "http://spring/internal/tools/create_order"))

    """验证 `post` 覆盖场景的预期行为与边界。"""
    def post(*args, **kwargs):
        captured.update(kwargs)
        return response

    monkeypatch.setattr(httpx, "post", post)
    settings = Settings(None, None, None, "http://spring", 16, 8, 100, agent_internal_token="contract-token")

    result = SpringBootToolClient(settings.spring_boot_base_url, settings=settings).call(order_request())

    assert result.ok is True
    assert captured["headers"] == {"X-Agent-Token": "contract-token"}


"""验证 `test_spring_tool_client_reads_internal_token_from_environment` 覆盖场景的预期行为与边界。"""
def test_spring_tool_client_reads_internal_token_from_environment(monkeypatch):
    captured = {}
    response = httpx.Response(200, json={"ok": True, "data": {}}, request=httpx.Request("POST", "http://spring/internal/tools/get_addresses"))

    """验证 `post` 覆盖场景的预期行为与边界。"""
    def post(*args, **kwargs):
        captured.update(kwargs)
        return response

    monkeypatch.setenv("AGENT_INTERNAL_TOKEN", "environment-token")
    monkeypatch.setattr(httpx, "post", post)

    assert SpringBootToolClient("http://spring").call(order_request()).ok is True
    assert captured["headers"] == {"X-Agent-Token": "environment-token"}


"""验证 `test_settings_reads_internal_token_from_environment` 覆盖场景的预期行为与边界。"""
def test_settings_reads_internal_token_from_environment(monkeypatch):
    monkeypatch.setenv("AGENT_INTERNAL_TOKEN", "settings-token")

    assert Settings.from_env().agent_internal_token == "settings-token"
