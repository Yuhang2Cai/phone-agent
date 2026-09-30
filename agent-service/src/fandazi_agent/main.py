"""内部 HTTP 入口；安卓客户端只能通过后端访问代理能力。"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from uuid import uuid4

from .config import Settings
from .graph import FoodOrderingAgent
from .models import AgentEventV1, AgentResult, AgentRunRequest, AgentRunRequestV1
from .spring_client import SpringBootToolClient
from .workflow_store import FileWorkflowStore


"""创建app。"""
def create_app(agent: FoodOrderingAgent | None = None, *, settings: Settings | None = None):
    try:
        from fastapi import FastAPI, Header, HTTPException
        from fastapi.responses import StreamingResponse
    except ImportError as exc:
        raise RuntimeError("Install agent-service dependencies to run the HTTP service") from exc

    settings = settings or (agent.settings if agent is not None else Settings.from_env())
    agent = agent or FoodOrderingAgent(
        SpringBootToolClient(settings.spring_boot_base_url), settings=settings,
        workflow_store=FileWorkflowStore(settings.workflow_store_dir) if settings.workflow_store_dir else None,
    )
    app = FastAPI(title="饭搭子 AI Agent Service", docs_url=None, redoc_url=None)

    def verify_internal_jwt(authorization: str | None) -> dict[str, object]:
        """验证内部 HS256 JWT，并返回不参与业务决策的声明。"""
        if not settings.agent_internal_token or not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(status_code=401, detail="缺少内部 Agent JWT")
        token = authorization[7:].strip()
        parts = token.split(".")
        if len(parts) != 3:
            raise HTTPException(status_code=401, detail="内部 Agent JWT 格式无效")
        try:
            encoded_header, encoded_payload, encoded_signature = parts

            def decode(value: str) -> dict[str, object]:
                """解码 JWT 的无填充 Base64URL JSON 片段。"""
                padding = "=" * (-len(value) % 4)
                return json.loads(base64.urlsafe_b64decode((value + padding).encode()).decode())

            header = decode(encoded_header)
            payload = decode(encoded_payload)
            if header.get("alg") != "HS256" or header.get("typ") != "JWT":
                raise ValueError("unsupported jwt header")
            expected = hmac.new(
                settings.agent_internal_token.encode(),
                f"{encoded_header}.{encoded_payload}".encode(),
                hashlib.sha256,
            ).digest()
            actual = base64.urlsafe_b64decode((encoded_signature + "=" * (-len(encoded_signature) % 4)).encode())
            if not hmac.compare_digest(expected, actual):
                raise ValueError("invalid jwt signature")
            if not isinstance(payload.get("exp"), (int, float)) or payload["exp"] <= time.time():
                raise ValueError("expired jwt")
            return payload
        except (ValueError, KeyError, TypeError, json.JSONDecodeError, UnicodeDecodeError, base64.binascii.Error) as exc:
            raise HTTPException(status_code=401, detail="内部 Agent JWT 无效或已过期") from exc

    def event_line(event: AgentEventV1) -> bytes:
        """将单个事件编码为一行稳定的 UTF-8 NDJSON。"""
        return (event.model_dump_json(by_alias=True, exclude_none=True) + "\n").encode("utf-8")

    def v1_events(request: AgentRunRequestV1):
        """按协议顺序生成运行开始、业务摘要和运行终态事件。"""
        run_id = request.run_id or f"run-{uuid4().hex}"
        sequence = 0

        def make(event_type: str, status: str, payload: dict[str, object]) -> bytes:
            """构造带链路标识和单调序号的事件。"""
            nonlocal sequence
            event = AgentEventV1(
                eventId=f"{run_id}-{sequence}", eventType=event_type,
                traceId=request.trace_id, runId=run_id, sequence=sequence,
                status=status, payload=payload,
            )
            sequence += 1
            return event_line(event)

        yield make("run_started", "running", {"message": "Agent 运行已开始"})
        try:
            result = agent.run(request.to_legacy_request(run_id))
            for item in result.events:
                # 只公开已有事件的摘要和工具名称，不把内部状态对象或模型原文直接出网。
                yield make("progress", "running", {
                    "message": item.summary,
                    "toolName": item.tool_name,
                    "status": item.status,
                })
            terminal_status = "waiting_confirmation" if result.status == "awaiting_confirmation" else "succeeded" if result.status == "completed" else "failed"
            yield make("run_completed", terminal_status, {
                "status": terminal_status,
                "message": result.message,
                "nextAction": result.next_action,
                "error": result.error,
                # 终态卡片跟随同一条版本化协议返回，保证客户端不会出现文本与卡片不一致。
                "cards": result.cards,
            })
        except Exception as exc:
            # 传输层仍保持合法终态，避免客户端只能依赖连接是否断开猜测结果。
            yield make("error", "failed", {"code": "INTERNAL_ERROR", "message": str(exc)})

    """运行助手。"""
    @app.post("/internal/agent/run", response_model=AgentResult)
    def run_agent(request: AgentRunRequest, x_agent_token: str | None = Header(default=None)) -> AgentResult:
        if not settings.agent_internal_token or not hmac.compare_digest(settings.agent_internal_token, x_agent_token or ""):
            raise HTTPException(status_code=403, detail="内部 Agent 凭据无效")
        return agent.run(request)

    @app.get("/internal/v1/health")
    def health() -> dict[str, str]:
        """提供不暴露密钥和模型配置的服务存活检查。"""
        return {"status": "ok", "schemaVersion": "1.0"}

    @app.post("/internal/v1/agent/runs", response_class=StreamingResponse)
    def run_v1(request: AgentRunRequestV1, authorization: str | None = Header(default=None)):
        """校验短期 JWT 后返回版本化 NDJSON 运行事件。"""
        verify_internal_jwt(authorization)
        return StreamingResponse(v1_events(request), media_type="application/x-ndjson")

    return app


try:
    app = create_app()
except RuntimeError:
    # 最小测试环境也应能够导入核心代码。
    app = None
