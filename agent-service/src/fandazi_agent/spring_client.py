"""The sole HTTP boundary to the Spring Boot tool API."""

from __future__ import annotations

import httpx
import os
import logging

from .config import Settings
from .models import ToolRequest, ToolResult
from .tools import is_allowed_tool

log = logging.getLogger(__name__)


class SpringBootToolClient:
    def __init__(
        self,
        base_url: str = "http://localhost:8080",
        timeout_seconds: float = 10.0,
        *,
        settings: Settings | None = None,
        agent_internal_token: str | None = None,
    ):
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds
        self.agent_internal_token = agent_internal_token if agent_internal_token is not None else (
            settings.agent_internal_token if settings is not None else os.getenv("AGENT_INTERNAL_TOKEN")
        )

    def call(self, request: ToolRequest) -> ToolResult:
        if not is_allowed_tool(request.tool_name):
            return ToolResult(ok=False, error="tool_not_allowed")
        try:
            log.info("spring tool start: base_url=%s tool=%s session=%s", self.base_url, request.tool_name, request.session_id)
            response = httpx.post(
                f"{self.base_url}/internal/tools/{request.tool_name}",
                json=request.model_dump(by_alias=True, mode="json", exclude={"tool_name"}),
                timeout=self.timeout_seconds,
                headers={"X-Agent-Token": self.agent_internal_token} if self.agent_internal_token else {},
                trust_env=False,
            )
        except httpx.RequestError as exc:
            log.exception("spring tool transport failure: tool=%s session=%s", request.tool_name, request.session_id)
            # A lost response cannot establish whether Spring Boot committed the order.
            return ToolResult(ok=False, error=f"spring_tool_unavailable: {exc}", error_kind="uncertain")

        raw_body: object | None = None
        try:
            raw_body = response.json()
            result = ToolResult.model_validate(raw_body)
        except ValueError:
            result = None

        status = response.status_code
        log.info("spring tool response: tool=%s session=%s status=%s body_keys=%s", request.tool_name, request.session_id, status, list(result.model_fields_set) if result is not None else None)
        error = result.error if result is not None and not result.ok else None
        if error is None and isinstance(raw_body, dict):
            raw_error = raw_body.get("error") or raw_body.get("message")
            if isinstance(raw_error, str) and raw_error.strip():
                error = raw_error.strip()
        if 400 <= status < 500 and status not in {408, 429}:
            # HTTP permission/validation/state refusals remain terminal even if an
            # inconsistent body claims they are retryable. Preserve the backend error.
            return ToolResult(ok=False, error=error or f"spring_tool_rejected: HTTP {status}", error_kind="rejected")
        if result is not None and not result.ok and result.error_kind is not None:
            return result
        if 200 <= status < 300 and result is not None:
            return result
        if status == 429:
            return ToolResult(ok=False, error=error or "spring_tool_unavailable: HTTP 429", error_kind="retryable")
        if status == 408 or status >= 500 or 200 <= status < 300:
            return ToolResult(ok=False, error=error or f"spring_tool_unavailable: invalid or missing result (HTTP {status})", error_kind="uncertain")
        return ToolResult(ok=False, error=f"spring_tool_rejected: HTTP {status}", error_kind="rejected")
