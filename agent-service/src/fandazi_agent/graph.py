"""Single-agent food ordering workflow.

LLM intent parsing is isolated in ``parse_intent``. All remaining nodes are
deterministic and delegate order authority to Spring Boot through the client.
"""

from __future__ import annotations

from typing import Any, Callable

from .config import Settings
from .models import AgentEvent, AgentResult, AgentRunRequest, LangChainModelAdapter, ToolRequest, ToolResult
from .spring_client import SpringBootToolClient
from .state import AgentState
from .tools import is_allowed_tool, langchain_tool_descriptions


class FoodOrderingAgent:
    def __init__(
        self,
        tool_client: SpringBootToolClient,
        *,
        settings: Settings | None = None,
        max_order_amount: int | None = None,
    ):
        self.settings = settings or Settings.from_env()
        self.tool_client = tool_client
        self.max_order_amount = max_order_amount or self.settings.max_order_amount
        self.model = LangChainModelAdapter(self.settings)
        self.tool_descriptions = langchain_tool_descriptions()
        self._sessions: dict[str, AgentState] = {}
        self.langgraph = build_langgraph()

    def export_session(self, session_id: str) -> dict[str, Any]:
        """Return a JSON-safe paused-state snapshot for a durable session store."""
        state = self._sessions.get(session_id)
        if state is None:
            raise KeyError(f"No paused session for {session_id}")
        return state.model_dump(mode="json")

    def restore_session(self, session_id: str, snapshot: dict[str, Any]) -> None:
        """Restore a verified snapshot without clearing completed-tool markers."""
        state = AgentState.model_validate(snapshot)
        if state.next_action != "wait_confirmation":
            raise ValueError("Only a wait_confirmation state may be restored")
        self._sessions[session_id] = state

    def run(self, request: AgentRunRequest) -> AgentResult:
        state = self._sessions.get(request.session_id, AgentState())
        state.messages.append(request.text)
        if state.next_action == "wait_confirmation":
            return self.wait_confirmation(state, request)

        self.parse_intent(state, request)
        if state.error:
            return self._failed(state)
        if state.intent == "strategy":
            self._step(state, "payment_followup")
            state.next_action = "strategy_ready"
            return self._completed(state, "已生成可按预算和口味调整的一般饮食策略。", "strategy_ready")
        if state.intent == "recommend":
            self.collect_missing_info(state, request)
            self.search_candidates(state, request)
            if state.error:
                return self._failed(state)
            message = "已按一般饮食偏好筛选商家。"
            if state.authorization.get("health_sensitive"):
                message += "这不是医疗建议；如症状持续或加重，请咨询专业医疗人员。"
            return self._completed(state, message, "recommendation_ready")
        return self._order_flow(state, request)

    def parse_intent(self, state: AgentState, request: AgentRunRequest) -> None:
        self._step(state, "parse_intent")
        intent = self.model.classify(request.text, request.mode)
        state.intent = intent.intent
        state.authorization["confirmed"] = intent.needs_confirmation
        state.authorization["auto_order"] = request.mode == "auto_order"
        state.authorization["health_sensitive"] = intent.health_sensitive
        self._event(state, "parse_intent", "info", "已识别请求意图。")

    def collect_missing_info(self, state: AgentState, request: AgentRunRequest) -> None:
        self._step(state, "collect_missing_info")
        if state.intent == "order" and not self._has_dish(request.text):
            state.next_action = "collect_missing_info"
            self._event(state, "collect_missing_info", "warning", "需要菜品或商家信息后才能继续。")

    def search_candidates(self, state: AgentState, request: AgentRunRequest) -> None:
        self._step(state, "search_candidates")
        result = self.invoke_tool("search_merchants", request, {"query": request.text}, state)
        if not result.ok:
            state.error = result.error or "商家检索失败"
            return
        state.candidate_cart = result.data

    def build_cart(self, state: AgentState, request: AgentRunRequest) -> None:
        self._step(state, "build_cart")
        if "create_cart_draft" in state.completed_tools:
            return
        result = self.invoke_tool("create_cart_draft", request, {"text": request.text}, state)
        if result.ok:
            state.candidate_cart.update(result.data)
        else:
            state.error = result.error or "购物车创建失败"

    def validate_order(self, state: AgentState, request: AgentRunRequest) -> None:
        self._step(state, "validate_order")
        if "validate_cart" in state.completed_tools:
            return
        result = self.invoke_tool("validate_cart", request, state.candidate_cart, state)
        if not result.ok or not result.data.get("valid", True):
            state.error = result.error or "购物车校验未通过"
            return
        state.candidate_cart.update(result.data)
        total = state.candidate_cart.get("total", 0)
        if total > self.max_order_amount:
            state.error = f"金额超过单次限额（{self.max_order_amount}）"
            self._event(state, "validate_order", "error", state.error)

    def wait_confirmation(self, state: AgentState, request: AgentRunRequest) -> AgentResult:
        self._step(state, "wait_confirmation")
        if "确认" not in request.text and "下单" not in request.text:
            self._sessions[request.session_id] = state
            return self._awaiting(state)
        state.authorization["confirmed"] = True
        state.next_action = None
        return self.create_order(state, request)

    def create_order(self, state: AgentState, request: AgentRunRequest) -> AgentResult:
        self._step(state, "create_order")
        if "create_order" not in state.completed_tools:
            result = self.invoke_tool("create_order", request, state.candidate_cart, state)
            if not result.ok:
                state.error = result.error or "创建订单失败"
                return self._failed(state)
            state.candidate_cart.update(result.data)
        return self.payment_followup(state, request)

    def payment_followup(self, state: AgentState, request: AgentRunRequest) -> AgentResult:
        self._step(state, "payment_followup")
        if state.authorization.get("auto_order") and "pay_mock_order" not in state.completed_tools:
            result = self.invoke_tool("pay_mock_order", request, state.candidate_cart, state)
            if not result.ok:
                state.error = result.error or "模拟支付失败"
                return self._failed(state)
            state.candidate_cart.update(result.data)
            return self._completed(state, "订单已创建并完成模拟支付。", "completed")
        return self._completed(state, "订单已创建，请继续支付。", "payment_followup")

    def invoke_tool(
        self,
        tool_name: str,
        request: AgentRunRequest,
        payload: dict[str, Any],
        state: AgentState | None = None,
    ) -> ToolResult:
        if not is_allowed_tool(tool_name):
            return ToolResult(ok=False, error="tool_not_allowed")
        if state and state.error:
            # A graph guard has already terminated this run; never perform a
            # backend side effect after that point.
            return ToolResult(ok=False, error=state.error)
        if state and state.tool_calls >= self.settings.max_tool_calls:
            state.error = "工具调用次数超过上限"
            self._event(state, "tool_guard", "error", state.error)
            return ToolResult(ok=False, error=state.error)
        index = (state.tool_calls if state else 0) + 1
        tool_request = ToolRequest(
            tool_name=tool_name,
            userId=request.user_id,
            sessionId=request.session_id,
            traceId=request.trace_id,
            idempotencyKey=f"{request.trace_id}:{request.session_id}:{tool_name}:{index}",
            payload=payload,
        )
        result = self.tool_client.call(tool_request)
        if state:
            state.tool_calls += 1
            if result.ok:
                state.completed_tools.append(tool_name)
            self._event(state, "tool", "tool" if result.ok else "error", "工具调用完成。" if result.ok else "工具调用失败。", tool_name)
        return result

    def _order_flow(self, state: AgentState, request: AgentRunRequest) -> AgentResult:
        self.collect_missing_info(state, request)
        if state.next_action == "collect_missing_info":
            return AgentResult(status="needs_input", message="请告诉我想吃的菜品或商家。", next_action="collect_missing_info", events=state.events)
        if state.intent == "reorder":
            last = self.invoke_tool("get_last_order", request, {}, state)
            if not last.ok:
                state.error = last.error or "无法复购上次订单"
                return self._failed(state)
            state.candidate_cart.update(last.data)
            state.authorization["confirmed"] = True
        self.build_cart(state, request)
        self.validate_order(state, request)
        if state.error:
            return self._failed(state)
        if not state.authorization.get("confirmed") and not state.authorization.get("auto_order"):
            state.next_action = "wait_confirmation"
            self._sessions[request.session_id] = state
            self._event(state, "wait_confirmation", "info", "购物车已校验，等待用户确认。")
            return self._awaiting(state)
        return self.create_order(state, request)

    def _step(self, state: AgentState, node: str) -> None:
        state.graph_steps += 1
        if state.graph_steps > self.settings.max_graph_steps:
            state.error = "图执行步数超过上限"
            self._event(state, node, "error", state.error)

    @staticmethod
    def _has_dish(text: str) -> bool:
        generic = {"帮我点个外卖", "帮我点外卖", "点外卖", "下单", "确认", "确认下单"}
        return text.strip() not in generic

    @staticmethod
    def _event(state: AgentState, node: str, kind: str, message: str, tool_name: str | None = None) -> None:
        state.events.append(AgentEvent(node=node, kind=kind, message=message, tool_name=tool_name))

    @staticmethod
    def _completed(state: AgentState, message: str, next_action: str) -> AgentResult:
        state.next_action = next_action
        return AgentResult(status="completed", message=message, next_action=next_action, candidate_cart=state.candidate_cart, events=state.events)

    @staticmethod
    def _awaiting(state: AgentState) -> AgentResult:
        return AgentResult(status="awaiting_confirmation", message="购物车已校验，请回复“确认”后下单。", next_action="wait_confirmation", candidate_cart=state.candidate_cart, events=state.events)

    @staticmethod
    def _failed(state: AgentState) -> AgentResult:
        return AgentResult(status="failed", message="请求未完成。", next_action="error", candidate_cart=state.candidate_cart, events=state.events, error=state.error)


def build_langgraph() -> Any | None:
    """Build the named LangGraph topology when the optional runtime is installed.

    Runtime effects remain in ``FoodOrderingAgent`` so a paused state can be
    serialized and resumed without replaying a completed backend tool call.
    """
    try:
        from langgraph.graph import END, START, StateGraph
    except ImportError:
        return None
    graph = StateGraph(dict)
    node_names = (
        "parse_intent", "collect_missing_info", "search_candidates", "build_cart",
        "validate_order", "wait_confirmation", "create_order", "payment_followup",
    )
    for name in node_names:
        graph.add_node(name, lambda state, _name=name: state)
    graph.add_edge(START, "parse_intent")
    graph.add_edge("parse_intent", "collect_missing_info")
    graph.add_edge("collect_missing_info", "search_candidates")
    graph.add_edge("search_candidates", "build_cart")
    graph.add_edge("build_cart", "validate_order")
    graph.add_edge("validate_order", "wait_confirmation")
    graph.add_edge("wait_confirmation", "create_order")
    graph.add_edge("create_order", "payment_followup")
    graph.add_edge("payment_followup", END)
    return graph.compile()
