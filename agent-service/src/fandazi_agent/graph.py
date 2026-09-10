"""Single checkpointed LangGraph workflow; LLM intent never authorizes orders."""
from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha256
import hmac
import secrets
from typing import Any
from uuid import uuid4

from typing_extensions import TypedDict
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt

from .config import Settings
from .models import AgentEvent, AgentResult, AgentRunRequest, LangChainModelAdapter, ModelClassificationError, ToolRequest, ToolResult
from .spring_client import SpringBootToolClient
from .state import AgentState
from .tools import is_allowed_tool, langchain_tool_descriptions


class WorkflowInput(TypedDict, total=False):
    agent_state: dict[str, Any]
    request: dict[str, Any]
    resume_text: str | None


_PROCESS_WORKFLOW_SIGNING_KEY = secrets.token_bytes(32)


@dataclass
class WorkflowRecord:
    user_id: str
    session_id: str
    workflow_id: str
    restored_state: AgentState | None = None


class FoodOrderingAgent:
    def __init__(self, tool_client: SpringBootToolClient, *, settings: Settings | None = None, max_order_amount: int | None = None):
        self.settings = settings or Settings.from_env()
        self.tool_client = tool_client
        self.max_order_amount = self.settings.max_order_amount if max_order_amount is None else max_order_amount
        self._workflow_signing_key = (self.settings.workflow_signing_key or "").encode() or _PROCESS_WORKFLOW_SIGNING_KEY
        self.model = LangChainModelAdapter(self.settings)
        self.tool_descriptions = langchain_tool_descriptions()
        self._checkpointer = MemorySaver()
        self._sessions: dict[tuple[str, str], WorkflowRecord] = {}
        self.langgraph = self._build_langgraph()

    def workflow_config(self, user_id: str, session_id: str) -> dict[str, Any]:
        record = self._sessions.get((user_id, session_id))
        if record is None:
            raise KeyError("No active workflow for this user and session")
        return {"configurable": {"thread_id": record.workflow_id}}

    def export_session(self, user_id: str, session_id: str) -> dict[str, Any]:
        state = self._checkpoint_state(user_id, session_id)
        self._assert_trusted_owner(state, user_id, session_id)
        if state.next_action != "wait_confirmation":
            raise ValueError("Only a wait_confirmation state may be exported")
        return state.model_dump(mode="json")

    def restore_session(self, user_id: str, session_id: str, snapshot: dict[str, Any]) -> None:
        state = AgentState.model_validate(snapshot)
        self._assert_trusted_owner(state, user_id, session_id)
        if state.next_action != "wait_confirmation":
            raise ValueError("Only a wait_confirmation state may be restored")
        self._sessions[(user_id, session_id)] = WorkflowRecord(user_id, session_id, state.workflow_id, state)

    def run(self, request: AgentRunRequest) -> AgentResult:
        key = (request.user_id, request.session_id)
        record = self._sessions.get(key)
        if record is None:
            state = self._new_state(request)
            record = WorkflowRecord(request.user_id, request.session_id, state.workflow_id)
            self._sessions[key] = record
            output = self.langgraph.invoke({"agent_state": self._dump(state), "request": self._request_data(request)}, self.workflow_config(*key))
        elif record.restored_state is not None:
            state = record.restored_state
            self._reset_budget(state, request)
            record.restored_state = None
            output = self.langgraph.invoke({"agent_state": self._dump(state), "request": self._request_data(request), "resume_text": request.text}, self.workflow_config(*key))
        else:
            config = self.workflow_config(*key)
            output = self.langgraph.invoke(Command(resume={"text": request.text}), config)
        state = self._state_from_output(output, *key)
        result = self._result(state)
        if state.next_action in {"payment_retry", "order_recovery"}:
            record.restored_state = state
        elif result.status != "awaiting_confirmation":
            self._sessions.pop(key, None)
            self._checkpointer.delete_thread(record.workflow_id)
        return result

    # Real nodes. Each goes through _enter, the common hard budget guard.
    def parse_intent(self, values: WorkflowInput) -> dict[str, Any]:
        state, request = self._unpack(values)
        if state.next_action in {"wait_confirmation", "payment_retry", "order_recovery"}:
            return self._pack(state)
        if not self._enter(state, "parse_intent"):
            return self._pack(state)
        try:
            intent = self.model.classify(request.text, request.mode)
        except ModelClassificationError:
            self._event(state, "parse_intent", "error", "模型意图识别失败。")
            if request.mode in {"order", "auto_order"}:
                state.error = "模型意图识别失败"
                return self._pack(state)
            intent = self.model.fallback_classify(request.text, request.mode)
            self._event(state, "parse_intent", "warning", "模型不可用，已使用受限分类。")
        state.messages.append(request.text)
        state.intent = intent.intent
        state.authorization.update(auto_order=request.mode == "auto_order", health_sensitive=intent.health_sensitive, confirmed=False)
        self._event(state, "parse_intent", "info", "已识别请求意图。")
        return self._pack(state)

    def collect_missing_info(self, values: WorkflowInput) -> dict[str, Any]:
        state, request = self._unpack(values)
        if self._enter(state, "collect_missing_info") and state.intent == "order" and not self._has_dish(request.text):
            state.next_action = "collect_missing_info"
            self._event(state, "collect_missing_info", "warning", "需要菜品或商家信息后才能继续。")
        return self._pack(state)

    def search_candidates(self, values: WorkflowInput) -> dict[str, Any]:
        state, request = self._unpack(values)
        if not self._enter(state, "search_candidates"):
            return self._pack(state)
        if state.intent == "reorder":
            result = self._call_tool(state, request, "get_last_order", {})
        else:
            result = self._call_tool(state, request, "search_merchants", {"query": request.text})
        if not result.ok:
            state.error = result.error or ("无法复购上次订单" if state.intent == "reorder" else "商家检索失败")
        elif state.intent == "reorder":
            state.candidate_cart.update(result.data)
        else:
            state.candidate_cart["merchants"] = result.data.get("merchants", [])
        return self._pack(state)

    def build_cart(self, values: WorkflowInput) -> dict[str, Any]:
        state, request = self._unpack(values)
        if self._enter(state, "build_cart") and "create_cart_draft" not in state.completed_tools:
            result = self._call_tool(state, request, "create_cart_draft", {"text": request.text})
            if result.ok:
                state.candidate_cart.update(result.data)
            else:
                state.error = result.error or "购物车创建失败"
        return self._pack(state)

    def validate_order(self, values: WorkflowInput) -> dict[str, Any]:
        state, request = self._unpack(values)
        if not self._enter(state, "validate_order"):
            return self._pack(state)
        if "validate_cart" not in state.completed_tools:
            result = self._call_tool(state, request, "validate_cart", state.candidate_cart)
            if not result.ok or not result.data.get("valid", True):
                state.error = result.error or "购物车校验未通过"
                return self._pack(state)
            state.candidate_cart.update(result.data)
        if state.candidate_cart.get("total", 0) > self.max_order_amount:
            state.error = f"金额超过单次限额（{self.max_order_amount}）"
            self._event(state, "validate_order", "error", state.error)
        elif not state.authorization.get("auto_order"):
            state.next_action = "wait_confirmation"
            self._event(state, "wait_confirmation", "info", "购物车已校验，等待用户确认。")
        return self._pack(state)

    def wait_confirmation(self, values: WorkflowInput) -> dict[str, Any]:
        state, _ = self._unpack(values)
        if not self._enter(state, "wait_confirmation"):
            return self._pack(state)
        answer = values.get("resume_text")
        while True:
            if answer is None:
                answer = interrupt({"kind": "confirmation_required", "workflowId": state.workflow_id})
            if isinstance(answer, dict): answer = answer.get("text", "")
            text = str(answer).strip()
            if self._is_cancel(text):
                state.next_action = "cancelled"
                self._event(state, "wait_confirmation", "info", "用户取消了下单。")
                break
            if self._is_confirmation(text):
                state.authorization["confirmed"] = True
                state.next_action = "create_order"
                self._event(state, "wait_confirmation", "info", "已收到确定性确认。")
                break
            answer = None
        return self._pack(state)

    def create_order(self, values: WorkflowInput) -> dict[str, Any]:
        state, request = self._unpack(values)
        if not self._enter(state, "create_order"):
            return self._pack(state)
        if not state.authorization.get("confirmed") and not state.authorization.get("auto_order"):
            state.error = "订单尚未获得确认授权"
        elif "create_order" not in state.completed_tools:
            result = self._call_tool(state, request, "create_order", state.candidate_cart)
            if result.ok:
                state.candidate_cart.update(result.data)
            else:
                state.error = result.error or "创建订单失败"
        return self._pack(state)

    def payment_followup(self, values: WorkflowInput) -> dict[str, Any]:
        state, request = self._unpack(values)
        if not self._enter(state, "payment_followup"):
            return self._pack(state)
        if state.intent == "strategy":
            state.next_action, state.authorization["message"] = "strategy_ready", "已生成可按预算和口味调整的一般饮食策略。"
        elif state.authorization.get("auto_order") and "pay_mock_order" not in state.completed_tools:
            result = self._call_tool(state, request, "pay_mock_order", state.candidate_cart)
            if result.ok:
                state.candidate_cart.update(result.data)
                state.next_action, state.authorization["message"] = "completed", "订单已创建并完成模拟支付。"
            else:
                state.error = result.error or "模拟支付失败"
                state.next_action = "payment_retry"
                state.authorization["order_created"] = True
        else:
            state.next_action, state.authorization["message"] = "payment_followup", "订单已创建，请继续支付。"
        return self._pack(state)

    def _build_langgraph(self):
        graph = StateGraph(WorkflowInput)
        for name in ("parse_intent", "collect_missing_info", "search_candidates", "build_cart", "validate_order", "wait_confirmation", "create_order", "payment_followup"):
            graph.add_node(name, getattr(self, name))
        graph.add_edge(START, "parse_intent")
        graph.add_conditional_edges("parse_intent", self._after_parse)
        graph.add_conditional_edges("collect_missing_info", self._after_collect)
        graph.add_conditional_edges("search_candidates", self._after_search, {"next": "build_cart", "end": END})
        graph.add_conditional_edges("build_cart", self._after_standard, {"next": "validate_order", "end": END})
        graph.add_conditional_edges("validate_order", self._after_validate)
        graph.add_conditional_edges("wait_confirmation", self._after_confirmation)
        graph.add_conditional_edges("create_order", self._after_standard, {"next": "payment_followup", "end": END})
        graph.add_edge("payment_followup", END)
        return graph.compile(checkpointer=self._checkpointer)

    def _after_parse(self, values: WorkflowInput) -> str:
        state = self._route_state(values)
        if state.error: return END
        if state.next_action == "wait_confirmation": return "wait_confirmation"
        if state.next_action == "payment_retry": return "payment_followup"
        if state.next_action == "order_recovery": return "create_order"
        if state.intent == "strategy": return "payment_followup"
        return "search_candidates" if state.intent == "recommend" else "collect_missing_info"

    def _after_collect(self, values: WorkflowInput) -> str:
        state = self._route_state(values)
        return END if state.error or state.next_action == "collect_missing_info" else "search_candidates"

    def _after_standard(self, values: WorkflowInput) -> str:
        return "end" if self._route_state(values).error else "next"

    def _after_search(self, values: WorkflowInput) -> str:
        state = self._route_state(values)
        return "end" if state.error or state.intent == "recommend" else "next"

    def _after_validate(self, values: WorkflowInput) -> str:
        state = self._route_state(values)
        if state.error: return END
        return "create_order" if state.authorization.get("auto_order") else "wait_confirmation"

    def _after_confirmation(self, values: WorkflowInput) -> str:
        return "create_order" if self._route_state(values).authorization.get("confirmed") else END

    def _call_tool(self, state: AgentState, request: AgentRunRequest, tool_name: str, payload: dict[str, Any]) -> ToolResult:
        if not is_allowed_tool(tool_name): return ToolResult(ok=False, error="tool_not_allowed")
        if state.error: return ToolResult(ok=False, error=state.error)
        if state.tool_calls >= self.settings.max_tool_calls:
            state.error = "工具调用次数超过上限"; self._event(state, "tool_guard", "error", state.error)
            return ToolResult(ok=False, error=state.error)
        key = state.operation_keys.setdefault(tool_name, f"{state.workflow_id}:{tool_name}")
        result = self.tool_client.call(ToolRequest(tool_name=tool_name, userId=request.user_id, sessionId=request.session_id, traceId=request.trace_id, idempotencyKey=key, payload=payload))
        state.tool_calls += 1
        if result.ok: state.completed_tools.append(tool_name)
        self._event(state, "tool", "tool" if result.ok else "error", "工具调用完成。" if result.ok else "工具调用失败。", tool_name)
        return result

    def invoke_tool(self, tool_name: str, request: AgentRunRequest, payload: dict[str, Any]) -> ToolResult:
        if not is_allowed_tool(tool_name): return ToolResult(ok=False, error="tool_not_allowed")
        return self._call_tool(self._new_state(request), request, tool_name, payload)

    def _enter(self, state: AgentState, node: str) -> bool:
        state.graph_steps += 1
        if state.graph_steps > self.settings.max_graph_steps:
            state.error = "图执行步数超过上限"; self._event(state, node, "error", state.error)
            return False
        return True

    @staticmethod
    def _has_dish(text: str) -> bool:
        return text.strip() not in {"帮我点个外卖", "帮我点外卖", "点外卖", "下单", "确认", "确认下单"}
    @staticmethod
    def _is_cancel(text: str) -> bool:
        text = text.replace(" ", "")
        return any(word in text for word in ("不要", "不下单", "取消", "先别", "别下单", "不确认"))
    @classmethod
    def _is_confirmation(cls, text: str) -> bool:
        text = text.replace(" ", "")
        return not cls._is_cancel(text) and text in {"确认", "确认下单", "确定", "确定下单"}
    @staticmethod
    def _event(state: AgentState, node: str, kind: str, message: str, tool_name: str | None = None) -> None:
        state.events.append(AgentEvent(node=node, kind=kind, message=message, tool_name=tool_name))
    @staticmethod
    def _dump(state: AgentState) -> dict[str, Any]: return state.model_dump(mode="json")
    @staticmethod
    def _request_data(request: AgentRunRequest) -> dict[str, Any]: return request.model_dump(by_alias=True, mode="json")
    @staticmethod
    def _pack(state: AgentState) -> dict[str, Any]: return {"agent_state": state.model_dump(mode="json")}
    @staticmethod
    def _unpack(values: WorkflowInput) -> tuple[AgentState, AgentRunRequest]: return AgentState.model_validate(values["agent_state"]), AgentRunRequest.model_validate(values["request"])
    @staticmethod
    def _route_state(values: WorkflowInput) -> AgentState: return AgentState.model_validate(values["agent_state"])
    def _new_state(self, request: AgentRunRequest) -> AgentState:
        state = AgentState(user_id=request.user_id, session_id=request.session_id, workflow_id=str(uuid4()))
        state.workflow_signature = self._sign_workflow(state)
        return state
    def _checkpoint_state(self, user_id: str, session_id: str) -> AgentState: return AgentState.model_validate(self.langgraph.get_state(self.workflow_config(user_id, session_id)).values["agent_state"])
    def _state_from_output(self, output: dict[str, Any], user_id: str, session_id: str) -> AgentState: return AgentState.model_validate(output["agent_state"]) if "agent_state" in output else self._checkpoint_state(user_id, session_id)
    @staticmethod
    def _reset_budget(state: AgentState, request: AgentRunRequest) -> None: state.graph_steps = state.tool_calls = 0; state.error = None; state.messages.append(request.text)
    def _sign_workflow(self, state: AgentState) -> str:
        payload = f"{state.workflow_version}|{state.user_id}|{state.session_id}|{state.workflow_id}".encode()
        return hmac.new(self._workflow_signing_key, payload, sha256).hexdigest()

    def _assert_trusted_owner(self, state: AgentState, user_id: str, session_id: str) -> None:
        if state.workflow_version != 1:
            raise ValueError("Unsupported workflow_version")
        if state.user_id != user_id or state.session_id != session_id:
            raise ValueError("Snapshot owner does not match userId and sessionId")
        if not state.workflow_id or not hmac.compare_digest(state.workflow_signature, self._sign_workflow(state)):
            raise ValueError("Untrusted workflow identifier")

    @staticmethod
    def _result(state: AgentState) -> AgentResult:
        if state.error:
            action = state.next_action if state.next_action in {"payment_retry", "order_recovery"} else "error"
            return AgentResult(status="failed", message="请求未完成。", next_action=action, candidate_cart=state.candidate_cart, events=state.events, error=state.error)
        if state.next_action == "collect_missing_info": return AgentResult(status="needs_input", message="请告诉我想吃的菜品或商家。", next_action=state.next_action, candidate_cart=state.candidate_cart, events=state.events)
        if state.next_action == "wait_confirmation": return AgentResult(status="awaiting_confirmation", message="购物车已校验，请回复“确认”后下单。", next_action=state.next_action, candidate_cart=state.candidate_cart, events=state.events)
        if state.next_action == "cancelled": return AgentResult(status="completed", message="已取消本次下单。", next_action="cancelled", candidate_cart=state.candidate_cart, events=state.events)
        if state.next_action == "strategy_ready": return AgentResult(status="completed", message=state.authorization.get("message", "已生成一般饮食策略。"), next_action=state.next_action, candidate_cart=state.candidate_cart, events=state.events)
        if state.intent == "recommend":
            message = "已按一般饮食偏好筛选商家。" + ("这不是医疗建议；如症状持续或加重，请咨询专业医疗人员。" if state.authorization.get("health_sensitive") else "")
            return AgentResult(status="completed", message=message, next_action="recommendation_ready", candidate_cart=state.candidate_cart, events=state.events)
        return AgentResult(status="completed", message=state.authorization.get("message", "订单已创建，请继续支付。"), next_action=state.next_action or "payment_followup", candidate_cart=state.candidate_cart, events=state.events)
