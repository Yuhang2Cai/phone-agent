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
            response.raise_for_status()
            body = response.json()
            return ToolResult.model_validate(body)
        except (httpx.HTTPError, ValueError) as exc:
            return ToolResult(ok=False, error=f"spring_tool_unavailable: {exc}")
