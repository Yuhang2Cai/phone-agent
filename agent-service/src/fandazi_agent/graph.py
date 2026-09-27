"""使用检查点管理单一工作流；模型意图不能直接授权订单。"""
from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha256
import hmac
import json
import re
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
from .workflow_store import WorkflowStore


class WorkflowInput(TypedDict, total=False):
    agent_state: dict[str, Any]
    request: dict[str, Any]
    resume_text: str | None


_PROCESS_WORKFLOW_SIGNING_KEY = secrets.token_bytes(32)
_RESUMABLE_ACTIONS = frozenset({"wait_confirmation", "order_recovery", "payment_retry"})
# 仅在删除成功前保留；该状态不能再次用于下单。
_TERMINAL_ACTIONS = frozenset({"cancelled", "error"})


@dataclass
class WorkflowRecord:
    user_id: str
    session_id: str
    workflow_id: str
    restored_state: AgentState | None = None


class FoodOrderingAgent:
    def __init__(
        self, tool_client: SpringBootToolClient, *, settings: Settings | None = None,
        max_order_amount: int | None = None, workflow_store: WorkflowStore | None = None,
    ):
        self.settings = settings or Settings.from_env()
        if workflow_store is not None and not self.settings.workflow_signing_key:
            raise ValueError("AGENT_WORKFLOW_SIGNING_KEY is required with a durable workflow store")
        self.tool_client = tool_client
        self.workflow_store = workflow_store
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
        """导出已认证的恢复状态，或等待清理的终态结果。"""
        state = self._checkpoint_state(user_id, session_id)
        self._assert_trusted_owner(state, user_id, session_id)
        if state.next_action not in _RESUMABLE_ACTIONS | _TERMINAL_ACTIONS:
            raise ValueError("Only a resumable or pending-cleanup workflow may be exported")
        return state.model_dump(mode="json")

    def restore_session(self, user_id: str, session_id: str, snapshot: dict[str, Any]) -> None:
        state = AgentState.model_validate(snapshot)
        self._assert_trusted_owner(state, user_id, session_id)
        if state.next_action not in _RESUMABLE_ACTIONS | _TERMINAL_ACTIONS:
            raise ValueError("Only a resumable or pending-cleanup workflow may be restored")
        self._sessions[(user_id, session_id)] = WorkflowRecord(user_id, session_id, state.workflow_id, state)

    def run(self, request: AgentRunRequest) -> AgentResult:
        key = (request.user_id, request.session_id)
        record = self._sessions.get(key)
        if record is None and self.workflow_store is not None:
            snapshot = self.workflow_store.load(*key)
            if snapshot is not None:
                self.restore_session(*key, snapshot)
                record = self._sessions[key]
        if record is None:
            state = self._new_state(request)
            record = WorkflowRecord(request.user_id, request.session_id, state.workflow_id)
            self._sessions[key] = record
            output = self.langgraph.invoke({"agent_state": self._dump(state), "request": self._request_data(request)}, self.workflow_config(*key))
        elif record.restored_state is not None:
            state = record.restored_state.model_copy(deep=True)
            self._reset_budget(state, request)
            output = self.langgraph.invoke({"agent_state": self._dump(state), "request": self._request_data(request), "resume_text": request.text}, self.workflow_config(*key))
        else:
            config = self.workflow_config(*key)
            output = self.langgraph.invoke(Command(resume={"text": request.text, "request": self._request_data(request)}), config)
        state = self._state_from_output(output, *key)
        result = self._result(state)
        if state.next_action in {"payment_retry", "order_recovery"}:
            record.restored_state = state
            self._persist_state(state)
        elif result.status == "awaiting_confirmation":
            record.restored_state = None
            self._persist_state(state)
        else:
            if result.next_action in _TERMINAL_ACTIONS:
                state.next_action = result.next_action
                self._persist_state(state)
            if self.workflow_store is not None:
                self.workflow_store.delete(*key)
            self._sessions.pop(key, None)
            self._checkpointer.delete_thread(record.workflow_id)
        return result

    # 所有实际节点都经过统一的步数预算校验。
    def parse_intent(self, values: WorkflowInput) -> dict[str, Any]:
        state, request = self._unpack(values)
        if state.next_action in _TERMINAL_ACTIONS:
            # 上次运行已经停止，此处只允许重试持久状态清理。
            return self._pack(state)
        if state.next_action == "order_recovery":
            if self._enter(state, "parse_intent") and self._is_cancel(request.text):
                state.next_action = "cancelled"
                state.authorization["confirmed"] = False
                message = "已停止本次下单重试；订单是否已创建请在订单页核实。"
                state.authorization["message"] = message
                self._event(state, "parse_intent", "info", message)
                self._persist_state(state)
            return self._pack(state)
        if state.next_action in {"wait_confirmation", "payment_retry"}:
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
        requested_limit = self._requested_amount_limit(request.text)
        state.authorization.update(auto_order=request.mode == "auto_order", health_sensitive=intent.health_sensitive, confirmed=False)
        if requested_limit is not None:
            state.authorization["requested_amount_limit"] = requested_limit
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
            result = self._call_tool(state, request, "create_cart_draft", self._cart_draft_payload(state, request))
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
        # 公开网关必须在用户确认前展示最终生效的金额上限。
        max_amount = self._effective_order_limit(state)
        state.candidate_cart["maxAmount"] = max_amount
        state.candidate_cart["workflowId"] = state.workflow_id
        if self._amount_value(state.candidate_cart.get("total", 0)) > max_amount:
            state.error = f"金额超过单次限额（{max_amount:g}）"
            self._event(state, "validate_order", "error", state.error)
        else:
            self._capture_authorized_cart(state)
            if not state.authorization.get("auto_order"):
                state.next_action = "wait_confirmation"
                self._event(state, "wait_confirmation", "info", "购物车已校验，等待用户确认。")
        return self._pack(state)

    def wait_confirmation(self, values: WorkflowInput) -> dict[str, Any]:
        state, _ = self._unpack(values)
        current_request = values["request"]
        answer = values.get("resume_text")
        while True:
            if answer is None:
                answer = interrupt({"kind": "confirmation_required", "workflowId": state.workflow_id})
            if isinstance(answer, dict):
                if "request" in answer:
                    current_request = answer["request"]
                    state.graph_steps = state.tool_calls = 0
                    state.error = None
                answer = answer.get("text", "")
            if not self._enter(state, "wait_confirmation"):
                return self._pack(state, current_request)
            text = str(answer).strip()
            if self._is_cancel(text):
                state.next_action = "cancelled"
                self._event(state, "wait_confirmation", "info", "用户取消了下单。")
                self._persist_state(state)
                break
            if self._is_confirmation(text):
                state.authorization["confirmed"] = True
                state.next_action = "create_order"
                self._event(state, "wait_confirmation", "info", "已收到确定性确认。")
                break
            answer = None
        return self._pack(state, current_request)

    def create_order(self, values: WorkflowInput) -> dict[str, Any]:
        state, request = self._unpack(values)
        if not self._enter(state, "create_order"):
            return self._pack(state)
        if not state.authorization.get("confirmed") and not state.authorization.get("auto_order"):
            state.error = "订单尚未获得确认授权"
            state.next_action = "error"
        elif "create_order" not in state.completed_tools:
            recovering = state.next_action == "order_recovery"
            calls_before = state.tool_calls
            payload = self._authorized_order_payload(state)
            if payload is None:
                state.error = "授权购物车已变更"
                state.next_action = "error"
                self._persist_state(state)
                return self._pack(state)
            result = self._call_tool(state, request, "create_order", payload)
            if result.ok:
                self._merge_order_result(state, result.data)
                # 先持久化已创建订单，再进入可能失败的支付步骤。
                state.next_action = "payment_retry"
                self._persist_state(state)
            else:
                state.error = result.error or "创建订单失败"
                unresolved = result.error_kind in {"retryable", "uncertain"}
                # 本地预算检查无法判定先前后端调用是否已提交。
                unresolved = unresolved or (recovering and state.tool_calls == calls_before)
                state.next_action = "order_recovery" if unresolved else "error"
        if state.next_action == "error":
            self._persist_state(state)
        return self._pack(state)

    def payment_followup(self, values: WorkflowInput) -> dict[str, Any]:
        state, request = self._unpack(values)
        if not self._enter(state, "payment_followup"):
            return self._pack(state)
        if state.intent == "strategy":
            state.next_action, state.authorization["message"] = "strategy_ready", "已生成可按预算和口味调整的一般饮食策略。"
        elif state.authorization.get("auto_order") and "pay_mock_order" not in state.completed_tools:
            result = self._call_tool(state, request, "pay_mock_order", self._payment_payload(state))
            if result.ok:
                self._merge_order_result(state, result.data)
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
        if state.error or state.next_action in _TERMINAL_ACTIONS: return END
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
        if tool_name == "create_order":
            # 在 HTTP 副作用前预写操作键、购物车和授权，以便崩溃后核对。
            state.next_action = "order_recovery"
            self._persist_state(state)
        result = self.tool_client.call(ToolRequest(tool_name=tool_name, userId=request.user_id, sessionId=request.session_id, traceId=request.trace_id, idempotencyKey=key, workflowId=state.workflow_id, payload=payload))
        state.tool_calls += 1
        if result.ok: state.completed_tools.append(tool_name)
        self._event(state, "tool", "tool" if result.ok else "error", "工具调用完成。" if result.ok else "工具调用失败。", tool_name)
        return result

    @staticmethod
    def _requested_amount_limit(text: str) -> float | None:
        match = re.search(r"(\d+(?:\.\d+)?)\s*元\s*(?:以内|以下|之内|内)", text)
        if match is None:
            return None
        value = float(match.group(1))
        return value if value > 0 else None

    def _effective_order_limit(self, state: AgentState) -> float:
        requested = state.authorization.get("requested_amount_limit")
        if isinstance(requested, (int, float)):
            return min(float(self.max_order_amount), float(requested))
        return float(self.max_order_amount)

    @staticmethod
    def _amount_value(value: Any) -> float:
        if isinstance(value, (int, float)):
            return float(value)
        if isinstance(value, str):
            cleaned = re.sub(r"[^0-9.]", "", value)
            if cleaned:
                return float(cleaned)
        return 0.0

    @staticmethod
    def _cart_draft_payload(state: AgentState, request: AgentRunRequest) -> dict[str, Any]:
        payload: dict[str, Any] = {"text": request.text}
        for key in ("merchantId", "items"):
            if key in state.candidate_cart:
                payload[key] = state.candidate_cart[key]
        merchants = state.candidate_cart.get("merchants")
        if "merchantId" not in payload and isinstance(merchants, list):
            available = [merchant for merchant in merchants if isinstance(merchant, dict) and merchant.get("id") is not None]
            named = [
                merchant for merchant in available
                if (name := str(merchant.get("name", "")).strip()) and name in request.text
            ]
            selected = named[0] if len(named) == 1 else None
            merchant_id = selected.get("id") if selected is not None else None
            if merchant_id is not None:
                payload["merchantId"] = merchant_id
        return payload

    def invoke_tool(self, tool_name: str, request: AgentRunRequest, payload: dict[str, Any]) -> ToolResult:
        if not is_allowed_tool(tool_name): return ToolResult(ok=False, error="tool_not_allowed")
        return ToolResult(ok=False, error="tools_require_workflow", error_kind="rejected")

    @staticmethod
    def _cart_id(cart: dict[str, Any]) -> Any:
        return cart.get("cartDraftId", cart.get("cartId"))

    def _capture_authorized_cart(self, state: AgentState) -> None:
        cart_id = self._cart_id(state.candidate_cart)
        address_id = state.candidate_cart.get("addressId")
        total = state.candidate_cart.get("total")
        state.authorization["authorized_cart"] = {
            "workflowId": state.workflow_id,
            "cartDraftId": cart_id,
            "addressId": address_id,
            "total": total,
        }
        if cart_id is not None:
            state.candidate_cart["cartDraftId"] = cart_id

    def _authorized_order_payload(self, state: AgentState) -> dict[str, Any] | None:
        snapshot = state.authorization.get("authorized_cart")
        if not isinstance(snapshot, dict):
            return None
        if snapshot.get("workflowId") != state.workflow_id:
            return None
        current = {
            "cartDraftId": self._cart_id(state.candidate_cart),
            "addressId": state.candidate_cart.get("addressId"),
            "total": state.candidate_cart.get("total"),
        }
        expected = {
            "cartDraftId": snapshot.get("cartDraftId"),
            "addressId": snapshot.get("addressId"),
            "total": snapshot.get("total"),
        }
        if current != expected:
            return None
        payload = dict(expected)
        payload["cartId"] = expected["cartDraftId"]
        return payload

    @staticmethod
    def _merge_order_result(state: AgentState, data: dict[str, Any]) -> None:
        state.candidate_cart.update(data)
        order = data.get("order")
        if isinstance(order, dict) and order.get("id") is not None:
            state.candidate_cart["orderId"] = order["id"]

    @staticmethod
    def _payment_payload(state: AgentState) -> dict[str, Any]:
        order_id = state.candidate_cart.get("orderId")
        return {"orderId": order_id} if order_id is not None else {}

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
    def _pack(self, state: AgentState, request: dict[str, Any] | None = None) -> dict[str, Any]:
        state.workflow_signature = self._sign_workflow(state)
        output: dict[str, Any] = {"agent_state": state.model_dump(mode="json")}
        if request is not None: output["request"] = request
        return output
    @staticmethod
    def _unpack(values: WorkflowInput) -> tuple[AgentState, AgentRunRequest]: return AgentState.model_validate(values["agent_state"]), AgentRunRequest.model_validate(values["request"])
    @staticmethod
    def _route_state(values: WorkflowInput) -> AgentState: return AgentState.model_validate(values["agent_state"])
    def _new_state(self, request: AgentRunRequest) -> AgentState:
        state = AgentState(user_id=request.user_id, session_id=request.session_id, workflow_id=str(uuid4()))
        state.workflow_signature = self._sign_workflow(state)
        return state
    def _checkpoint_state(self, user_id: str, session_id: str) -> AgentState:
        config = self.workflow_config(user_id, session_id)
        restored = self._sessions[(user_id, session_id)].restored_state
        if restored is not None:
            return restored.model_copy(deep=True)
        return AgentState.model_validate(self.langgraph.get_state(config).values["agent_state"])

    def _persist_state(self, state: AgentState) -> None:
        snapshot = self._pack(state)["agent_state"]
        record = self._sessions.get((state.user_id, state.session_id))
        if (
            record is not None and record.workflow_id == state.workflow_id
            and state.next_action in {"order_recovery", "payment_retry"} | _TERMINAL_ACTIONS
        ):
            # 即使持久化失败也保留最新结果；后续请求必须重新进入取消或恢复路径。
            record.restored_state = state.model_copy(deep=True)
        if self.workflow_store is not None:
            self.workflow_store.save(state.user_id, state.session_id, snapshot)

    def _state_from_output(self, output: dict[str, Any], user_id: str, session_id: str) -> AgentState: return AgentState.model_validate(output["agent_state"]) if "agent_state" in output else self._checkpoint_state(user_id, session_id)
    @staticmethod
    def _reset_budget(state: AgentState, request: AgentRunRequest) -> None:
        state.graph_steps = state.tool_calls = 0
        if state.next_action not in _TERMINAL_ACTIONS:
            state.error = None
        state.messages.append(request.text)
    def _sign_workflow(self, state: AgentState) -> str:
        payload = state.model_dump(mode="json", exclude={"workflow_signature"})
        encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        return hmac.new(self._workflow_signing_key, encoded, sha256).hexdigest()

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
        if state.next_action == "cancelled": return AgentResult(status="completed", message=state.authorization.get("message", "已取消本次下单。"), next_action="cancelled", candidate_cart=state.candidate_cart, events=state.events)
        if state.next_action == "strategy_ready": return AgentResult(status="completed", message=state.authorization.get("message", "已生成一般饮食策略。"), next_action=state.next_action, candidate_cart=state.candidate_cart, events=state.events)
        if state.intent == "recommend":
            message = "已按一般饮食偏好筛选商家。" + ("这不是医疗建议；如症状持续或加重，请咨询专业医疗人员。" if state.authorization.get("health_sensitive") else "")
            return AgentResult(status="completed", message=message, next_action="recommendation_ready", candidate_cart=state.candidate_cart, events=state.events)
        return AgentResult(status="completed", message=state.authorization.get("message", "订单已创建，请继续支付。"), next_action=state.next_action or "payment_followup", candidate_cart=state.candidate_cart, events=state.events)
