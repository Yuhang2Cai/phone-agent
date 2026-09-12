"""Calls the real Spring internal-tool endpoint through the production Python client."""

from __future__ import annotations

import json
import sys

from fandazi_agent.models import ToolRequest
from fandazi_agent.spring_client import SpringBootToolClient


base_url, session_id, token = sys.argv[1:]
client = SpringBootToolClient(base_url, agent_internal_token=token)
result = client.call(
    ToolRequest(
        tool_name="get_addresses",
        userId="model-supplied-user-is-ignored",
        sessionId=session_id,
        traceId="model-supplied-trace-is-ignored",
        idempotencyKey="python-http-contract",
        payload={},
    )
)
print(json.dumps(result.model_dump(mode="json"), ensure_ascii=False))
raise SystemExit(0 if result.ok else 1)
