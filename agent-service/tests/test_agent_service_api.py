"""验证 Agent 服务 V1 HTTP 协议、内部鉴权和 NDJSON 事件顺序。"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import sys
import time
from pathlib import Path

from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from fandazi_agent.config import Settings
from fandazi_agent.main import create_app
from fandazi_agent.models import AgentResult


class FakeAgent:
    """只返回固定终态的测试替身，隔离 HTTP 协议测试与真实模型、工具。"""

    def run(self, request):
        """返回可被协议适配层转换的旧版结果。"""
        return AgentResult(
            status="completed",
            message="已完成推荐",
            next_action="recommendation_ready",
            events=[],
        )


def _token(secret: str, *, expired: bool = False) -> str:
    """生成测试用 HS256 JWT，覆盖服务端必须检查的过期时间字段。"""
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {"sub": "u-1", "exp": int(time.time()) - 1 if expired else int(time.time()) + 300}

    def encode(value: dict[str, object]) -> str:
        """将 JSON 对象编码为 JWT 的无填充 Base64URL 片段。"""
        raw = json.dumps(value, separators=(",", ":"), ensure_ascii=False).encode()
        return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()

    signing_input = f"{encode(header)}.{encode(payload)}"
    signature = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    return f"{signing_input}.{base64.urlsafe_b64encode(signature).rstrip(b'=').decode()}"


def _settings() -> Settings:
    """构造不依赖环境变量的协议测试配置。"""
    return Settings(
        llm_base_url=None,
        llm_api_key=None,
        llm_model=None,
        spring_boot_base_url="http://127.0.0.1:8080",
        max_graph_steps=16,
        max_tool_calls=8,
        max_order_amount=100,
        agent_internal_token="test-secret",
    )


def _request(text: str = "你好") -> dict[str, object]:
    """构造符合 Agent 协议 V1 的最小请求。"""
    return {
        "schemaVersion": "1.0",
        "userId": "u-1",
        "sessionId": "s-1",
        "traceId": "trace-1",
        "runId": "run-1",
        "query": text,
        "context": {"cityCode": "demo"},
    }


def test_run_rejects_missing_internal_jwt():
    """防止没有内部 JWT 的外部请求进入独立 Agent 服务。"""
    client = TestClient(create_app(agent=FakeAgent(), settings=_settings()))

    response = client.post("/internal/v1/agent/runs", json=_request())

    assert response.status_code == 401


def test_run_rejects_expired_internal_jwt():
    """防止过期令牌继续调用 Agent，避免长期凭据扩大泄露影响。"""
    settings = _settings()
    client = TestClient(create_app(agent=FakeAgent(), settings=settings))

    response = client.post(
        "/internal/v1/agent/runs",
        headers={"Authorization": f"Bearer {_token(settings.agent_internal_token, expired=True)}"},
        json=_request(),
    )

    assert response.status_code == 401


def test_run_streams_started_and_completed_events():
    """防止客户端收到非版本化 JSON，且验证流事件具有可重放的首尾顺序。"""
    settings = _settings()
    client = TestClient(create_app(agent=FakeAgent(), settings=settings))

    response = client.post(
        "/internal/v1/agent/runs",
        headers={"Authorization": f"Bearer {_token(settings.agent_internal_token)}"},
        json=_request(),
    )

    events = [json.loads(line) for line in response.text.splitlines()]
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/x-ndjson")
    assert events[0]["eventType"] == "run_started"
    assert events[-1]["eventType"] == "run_completed"
    assert [event["sequence"] for event in events] == list(range(len(events)))
    assert all(event["traceId"] == "trace-1" for event in events)
    assert all(event["runId"] == "run-1" for event in events)
