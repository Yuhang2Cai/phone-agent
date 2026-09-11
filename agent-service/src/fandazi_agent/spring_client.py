"""The sole HTTP boundary to the Spring Boot tool API."""

from __future__ import annotations

import httpx

from .models import ToolRequest, ToolResult
from .tools import is_allowed_tool


class SpringBootToolClient:
    def __init__(self, base_url: str = "http://localhost:8080", timeout_seconds: float = 10.0):
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds

    def call(self, request: ToolRequest) -> ToolResult:
        if not is_allowed_tool(request.tool_name):
            return ToolResult(ok=False, error="tool_not_allowed")
        try:
            response = httpx.post(
                f"{self.base_url}/internal/tools/{request.tool_name}",
                json=request.model_dump(by_alias=True, mode="json", exclude={"tool_name"}),
                timeout=self.timeout_seconds,
            )
        except httpx.RequestError as exc:
            # A lost response cannot establish whether Spring Boot committed the order.
            return ToolResult(ok=False, error=f"spring_tool_unavailable: {exc}", error_kind="uncertain")

        try:
            result = ToolResult.model_validate(response.json())
        except ValueError:
            result = None

        status = response.status_code
        error = result.error if result is not None and not result.ok else None
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
