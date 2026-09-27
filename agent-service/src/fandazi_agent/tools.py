"""定义固定的后端工具白名单及工具说明。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from pydantic import BaseModel, Field


TOOL_ALLOWLIST = frozenset(
    {
        "search_merchants",
        "list_dishes",
        "get_last_order",
        "create_cart_draft",
        "validate_cart",
        "get_addresses",
        "create_order",
        "pay_mock_order",
    }
)


@dataclass(frozen=True)
class ToolDescription:
    name: str
    description: str


TOOL_DESCRIPTIONS = (
    ToolDescription("search_merchants", "Find merchants matching general food preferences."),
    ToolDescription("list_dishes", "List dishes from one merchant."),
    ToolDescription("get_last_order", "Fetch the user's latest completed order for exact reorder."),
    ToolDescription("create_cart_draft", "Ask Spring Boot to create a cart draft."),
    ToolDescription("validate_cart", "Ask Spring Boot to validate stock, address, and total."),
    ToolDescription("get_addresses", "Fetch delivery addresses available to the user."),
    ToolDescription("create_order", "Ask Spring Boot to create an authorized order."),
    ToolDescription("pay_mock_order", "Ask Spring Boot to perform mock payment for an existing order."),
)


def is_allowed_tool(name: str) -> bool:
    return name in TOOL_ALLOWLIST


def langchain_tool_descriptions() -> list[Any]:
    """安装工具库后提供结构化工具对象。

    此处不执行实际调用；只有后端工具客户端可以跨越代理与后端的边界。
    """
    try:
        from langchain_core.tools import StructuredTool
    except ImportError:
        return list(TOOL_DESCRIPTIONS)

    class ToolPayload(BaseModel):
        payload: dict[str, Any] = Field(default_factory=dict)

    def unavailable_tool(payload: dict[str, Any]) -> dict[str, str]:
        raise RuntimeError("Tools must be invoked through SpringBootToolClient")

    return [
        StructuredTool.from_function(
            func=unavailable_tool,
            name=item.name,
            description=item.description,
            args_schema=ToolPayload,
        )
        for item in TOOL_DESCRIPTIONS
    ]
