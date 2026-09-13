from __future__ import annotations

import copy
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from fandazi_agent.config import Settings
from fandazi_agent.graph import FoodOrderingAgent
from fandazi_agent.models import AgentRunRequest, ToolResult
from fandazi_agent.spring_client import SpringBootToolClient


class FakeWorkflowStore:
    """External-store contract fake: only serialized bytes outlive an agent."""

    def __init__(self, storage=None):
        self.storage = storage if storage is not None else {}

    def load(self, user_id, session_id):
        value = self.storage.get((user_id, session_id))
        return json.loads(value) if value is not None else None

    def save(self, user_id, session_id, snapshot):
        self.storage[(user_id, session_id)] = json.dumps(snapshot, ensure_ascii=False)

    def delete(self, user_id, session_id):
        self.storage.pop((user_id, session_id), None)


class SimulatedProcessExit(BaseException):
    pass


class RecoveryToolClient(SpringBootToolClient):
    def __init__(self, outcomes=()):
        self.outcomes = iter(outcomes)
        self.requests = []

    def call(self, request):
        self.requests.append(request)
        if request.tool_name == "create_order":
            outcome = next(self.outcomes, ToolResult(ok=True, data={"orderId": "order-reconciled"}))
            if isinstance(outcome, BaseException):
                raise outcome
            return outcome
        data = {
            "search_merchants": {"merchants": [{"id": "merchant-1"}]},
            "create_cart_draft": {"cartId": "cart-1", "total": 18},
            "validate_cart": {"valid": True, "total": 18},
            "pay_mock_order": {"paid": True},
        }
        return ToolResult(ok=True, data=data[request.tool_name])


def request(text="宫保鸡丁", *, user_id="u-1", session_id="recovery", trace_id="trace-first", mode="auto_order"):
    return AgentRunRequest(userId=user_id, sessionId=session_id, traceId=trace_id, text=text, mode=mode)


def make_agent(client, **kwargs):
    settings = Settings(None, None, None, "http://localhost:8080", 16, 8, 100, "r4-test-signing-key")
    return FoodOrderingAgent(client, settings=settings, **kwargs)


def uncertain_failure():
    return ToolResult(ok=False, error="response_lost", error_kind="uncertain")


def test_public_snapshot_roundtrip_recovers_order_with_exact_workflow_key():
    """Break caught: public export/restore cannot carry uncertainty across instances."""
    client = RecoveryToolClient([uncertain_failure()])
    agent = make_agent(client)
    first = agent.run(request())
    assert first.next_action == "order_recovery"
    snapshot = json.loads(json.dumps(agent.export_session("u-1", "recovery")))
    first_order = next(item for item in client.requests if item.tool_name == "create_order")

    restarted = make_agent(client)
    restarted.restore_session("u-1", "recovery", snapshot)
    # Export must also work before the restored agent has a LangGraph checkpoint.
    assert restarted.export_session("u-1", "recovery") == snapshot
    assert restarted.workflow_config("u-1", "recovery")["configurable"]["thread_id"] == snapshot["workflow_id"]
    result = restarted.run(request("继续", trace_id="trace-restart"))

    orders = [item for item in client.requests if item.tool_name == "create_order"]
    assert result.status == "completed"
    assert result.candidate_cart["orderId"] == "order-reconciled"
    assert [item.idempotency_key for item in orders] == [snapshot["workflow_id"] + ":create_order"] * 2
    assert snapshot["operation_keys"]["create_order"] == first_order.idempotency_key
    assert orders[1].trace_id == "trace-restart"
    assert orders[1].payload == orders[0].payload
    assert [item.tool_name for item in client.requests].count("create_cart_draft") == 1


def test_durable_store_reloads_workflow_automatically_and_clears_after_success():
    """Break caught: a fresh agent allocates a new workflow despite a saved recovery."""
    backing = {}
    client = RecoveryToolClient([uncertain_failure()])
    first_agent = make_agent(client, workflow_store=FakeWorkflowStore(backing))
    first_agent.run(request())
    saved = FakeWorkflowStore(backing).load("u-1", "recovery")

    restarted = make_agent(client, workflow_store=FakeWorkflowStore(backing))
    result = restarted.run(request("继续", trace_id="trace-restart"))

    assert result.status == "completed"
    orders = [item for item in client.requests if item.tool_name == "create_order"]
    assert len(orders) == 2
    assert orders[0].idempotency_key == orders[1].idempotency_key == saved["workflow_id"] + ":create_order"
    assert orders[1].payload == orders[0].payload
    assert FakeWorkflowStore(backing).load("u-1", "recovery") is None
    with pytest.raises(KeyError):
        restarted.workflow_config("u-1", "recovery")


def test_durable_snapshot_exists_before_create_order_can_lose_its_response():
    """Break caught: process exit between backend commit and run return loses the key."""
    store = FakeWorkflowStore()
    client = RecoveryToolClient([SimulatedProcessExit()])
    agent = make_agent(client, workflow_store=store)
    with pytest.raises(SimulatedProcessExit):
        agent.run(request())

    saved = store.load("u-1", "recovery")
    assert saved["next_action"] == "order_recovery"
    assert saved["authorization"]["auto_order"] is True
    assert "create_order" not in saved["completed_tools"]
    restarted = make_agent(client, workflow_store=FakeWorkflowStore(store.storage))
    result = restarted.run(request("继续", trace_id="trace-after-crash"))

    orders = [item for item in client.requests if item.tool_name == "create_order"]
    assert result.status == "completed"
    assert len(orders) == 2
    assert orders[0].idempotency_key == orders[1].idempotency_key == saved["operation_keys"]["create_order"]
    assert [item.tool_name for item in client.requests].count("create_cart_draft") == 1


def test_store_save_failure_prevents_create_order_dispatch():
    """Break caught: an unavailable durable store still allows an untracked side effect."""
    class BrokenStore(FakeWorkflowStore):
        def save(self, *args):
            raise OSError("store unavailable")

    client = RecoveryToolClient()
    agent = make_agent(client, workflow_store=BrokenStore())
    with pytest.raises(OSError, match="store unavailable"):
        agent.run(request())
    assert "create_order" not in [item.tool_name for item in client.requests]


def test_store_load_failure_does_not_start_a_new_workflow():
    """Break caught: storage read errors are treated as absent state and duplicate an order."""
    class BrokenStore(FakeWorkflowStore):
        def load(self, *args):
            raise OSError("store unavailable")

    client = RecoveryToolClient()
    agent = make_agent(client, workflow_store=BrokenStore())
    with pytest.raises(OSError, match="store unavailable"):
        agent.run(request())
    assert not client.requests


def test_recovery_can_still_be_cancelled_after_a_store_write_failure():
    """Break caught: failed persistence discards the resume record and replays create on cancel."""
    class TemporarilyUnavailableStore(FakeWorkflowStore):
        unavailable = False

        def save(self, *args):
            if self.unavailable:
                raise OSError("store unavailable")
            super().save(*args)

    store = TemporarilyUnavailableStore()
    client = RecoveryToolClient([uncertain_failure()])
    agent = make_agent(client, workflow_store=store)
    agent.run(request())
    store.unavailable = True
    with pytest.raises(OSError, match="store unavailable"):
        agent.run(request("继续"))
    store.unavailable = False
    before = len(client.requests)
    result = agent.run(request("取消"))
    assert result.next_action == "cancelled"
    assert len(client.requests) == before
    assert store.load("u-1", "recovery") is None


def test_known_create_success_survives_a_store_write_failure_in_the_same_agent():
    """Break caught: a failed success snapshot write replays an order already acknowledged."""
    class TemporarilyUnavailableStore(FakeWorkflowStore):
        unavailable = True

        def save(self, user_id, session_id, snapshot):
            if self.unavailable and snapshot["next_action"] == "payment_retry":
                raise OSError("store unavailable after create")
            super().save(user_id, session_id, snapshot)

    store = TemporarilyUnavailableStore()
    client = RecoveryToolClient()
    agent = make_agent(client, workflow_store=store)
    with pytest.raises(OSError, match="store unavailable after create"):
        agent.run(request())
    snapshot = agent.export_session("u-1", "recovery")
    assert snapshot["next_action"] == "payment_retry"
    assert "create_order" in snapshot["completed_tools"]
    store.unavailable = False
    before = len(client.requests)
    result = agent.run(request("继续支付"))
    assert result.status == "completed"
    assert [item.tool_name for item in client.requests[before:]] == ["pay_mock_order"]
    assert store.load("u-1", "recovery") is None


def test_direct_tool_entry_cannot_bypass_graph_or_overwrite_active_recovery():
    """Break caught: public tool dispatch creates an unauthorized order and replaces recovery."""
    store = FakeWorkflowStore()
    client = RecoveryToolClient([uncertain_failure()])
    agent = make_agent(client, workflow_store=store)
    agent.run(request())
    saved = store.load("u-1", "recovery")
    before = len(client.requests)

    result = agent.invoke_tool("create_order", request("确认"), {"cartId": "different-cart"})

    assert result.ok is False
    assert len(client.requests) == before
    assert store.load("u-1", "recovery") == saved


@pytest.mark.parametrize("error", ["authorization_denied", "insufficient_stock", "amount_exceeded", "invalid_order_state", "unknown_failure"])
@pytest.mark.parametrize("error_kind", [None, "rejected"])
def test_unclassified_or_business_rejection_ends_and_clears_workflow(error, error_kind):
    """Break caught: a definitive refusal traps the session in a repeat-order loop."""
    store = FakeWorkflowStore()
    client = RecoveryToolClient([ToolResult(ok=False, error=error, error_kind=error_kind)])
    agent = make_agent(client, workflow_store=store)
    result = agent.run(request())

    assert result.status == "failed"
    assert result.error == error
    assert result.next_action == "error"
    assert store.load("u-1", "recovery") is None
    with pytest.raises(KeyError):
        agent.workflow_config("u-1", "recovery")
    later = agent.run(request("鱼香肉丝", mode="order"))
    assert later.status == "awaiting_confirmation"
    assert [item.tool_name for item in client.requests].count("create_order") == 1
    assert [item.tool_name for item in client.requests].count("create_cart_draft") == 2


@pytest.mark.parametrize("error_kind", ["retryable", "uncertain"])
def test_only_explicit_retryable_or_uncertain_failures_keep_recovery(error_kind):
    """Break caught: recovery depends on error-message substrings instead of typed outcomes."""
    client = RecoveryToolClient([ToolResult(ok=False, error="opaque_backend_error", error_kind=error_kind)])
    agent = make_agent(client)
    assert agent.run(request()).next_action == "order_recovery"
    assert agent.run(request("继续")).status == "completed"
    orders = [item for item in client.requests if item.tool_name == "create_order"]
    assert len(orders) == 2
    assert orders[0].idempotency_key == orders[1].idempotency_key


def test_rejection_on_recovery_removes_memory_checkpoint_and_durable_snapshot():
    """Break caught: a retry's definitive refusal leaves the earlier uncertainty active."""
    store = FakeWorkflowStore()
    client = RecoveryToolClient([uncertain_failure(), ToolResult(ok=False, error="authorization_denied", error_kind="rejected")])
    agent = make_agent(client, workflow_store=store)
    agent.run(request())
    config = agent.workflow_config("u-1", "recovery")
    result = agent.run(request("继续"))

    assert result.status == "failed"
    assert result.next_action == "error"
    assert store.load("u-1", "recovery") is None
    assert not agent.langgraph.get_state(config).values
    with pytest.raises(KeyError):
        agent.workflow_config("u-1", "recovery")


def test_in_memory_recovery_cancel_does_not_reissue_create_order():
    """Break caught: existing same-instance recovery ignores explicit cancellation."""
    client = RecoveryToolClient([uncertain_failure()])
    agent = make_agent(client)
    agent.run(request())
    before = len(client.requests)
    result = agent.run(request("取消"))
    assert result.next_action == "cancelled"
    assert len(client.requests) == before


@pytest.mark.parametrize("restart", [False, True])
@pytest.mark.parametrize("text", ["取消", "不要下单", "取消本次重试"])
def test_recovery_cancellation_never_calls_create_order_and_clears_state(restart, text):
    """Break caught: cancel text is routed directly into another create_order attempt."""
    store = FakeWorkflowStore()
    client = RecoveryToolClient([uncertain_failure()])
    agent = make_agent(client, workflow_store=store)
    agent.run(request())
    config = agent.workflow_config("u-1", "recovery")
    if restart:
        agent = make_agent(client, workflow_store=FakeWorkflowStore(store.storage))
    before = len(client.requests)
    result = agent.run(request(text, trace_id="trace-cancel"))

    assert result.status == "completed"
    assert result.next_action == "cancelled"
    assert "重试" in result.message  # Stopping recovery cannot assert that a backend order was cancelled.
    assert len(client.requests) == before
    assert any(event.node == "parse_intent" for event in result.events)
    assert store.load("u-1", "recovery") is None
    assert not agent.langgraph.get_state(config).values
    with pytest.raises(KeyError):
        agent.workflow_config("u-1", "recovery")


@pytest.mark.parametrize("restart", [False, True])
@pytest.mark.parametrize("outcome", ["cancelled", "rejected"])
def test_terminal_cleanup_failure_never_replays_create_order(restart, outcome):
    """Break caught: failed snapshot deletion revives a cancelled or rejected order attempt."""
    class TemporarilyUnavailableStore(FakeWorkflowStore):
        unavailable = True

        def delete(self, *args):
            if self.unavailable:
                raise OSError("delete unavailable")
            super().delete(*args)

    store = TemporarilyUnavailableStore()
    client = RecoveryToolClient([
        uncertain_failure(),
        ToolResult(ok=False, error="insufficient_stock", error_kind="rejected"),
    ])
    agent = make_agent(client, workflow_store=store)
    agent.run(request())
    with pytest.raises(OSError, match="delete unavailable"):
        agent.run(request("取消" if outcome == "cancelled" else "继续"))

    store.unavailable = False
    if restart:
        agent = make_agent(client, workflow_store=store)
    before = len(client.requests)
    result = agent.run(request("继续", trace_id="trace-cleanup"))

    assert len(client.requests) == before
    assert result.next_action == ("cancelled" if outcome == "cancelled" else "error")
    assert result.error == (None if outcome == "cancelled" else "insufficient_stock")
    assert store.load("u-1", "recovery") is None
    with pytest.raises(KeyError):
        agent.workflow_config("u-1", "recovery")


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("candidate_cart", {"cartId": "foreign-cart", "total": 0}),
        ("authorization", {"confirmed": True, "auto_order": True}),
        ("operation_keys", {"create_order": "different:create_order"}),
        ("next_action", "payment_retry"),
        ("completed_tools", ["create_order"]),
        ("messages", ["forged"]),
        ("events", []),
        ("graph_steps", 0),
        ("tool_calls", 0),
        ("workflow_id", "different-workflow"),
        ("workflow_version", 999),
    ],
)
def test_order_recovery_snapshot_authenticates_all_restored_state(field, value):
    """Break caught: expanding recovery bypasses the existing full-state signature check."""
    client = RecoveryToolClient([uncertain_failure()])
    agent = make_agent(client)
    agent.run(request())
    snapshot = copy.deepcopy(agent.export_session("u-1", "recovery"))
    snapshot[field] = value
    with pytest.raises(ValueError, match="workflow"):
        make_agent(client).restore_session("u-1", "recovery", snapshot)


@pytest.mark.parametrize(("other_user", "other_session"), [("u-2", "recovery"), ("u-1", "other-session")])
def test_durable_recovery_is_isolated_by_both_user_and_session(other_user, other_session):
    """Break caught: a different user with the same session can retry another user's cart."""
    store = FakeWorkflowStore()
    client = RecoveryToolClient([uncertain_failure()])
    agent = make_agent(client, workflow_store=store)
    agent.run(request())
    saved = store.load("u-1", "recovery")
    restarted = make_agent(client, workflow_store=store)

    other = restarted.run(request("确认", user_id=other_user, session_id=other_session, mode="order"))
    assert other.status == "needs_input"
    assert store.load("u-1", "recovery") == saved
    with pytest.raises(ValueError, match="owner"):
        restarted.restore_session(other_user, other_session, saved)
    store.save(other_user, other_session, saved)
    before = len(client.requests)
    with pytest.raises(ValueError, match="owner"):
        restarted.run(request("继续", user_id=other_user, session_id=other_session))
    assert len(client.requests) == before


def test_persistent_store_requires_a_configured_signing_key(monkeypatch):
    """Break caught: durable snapshots are written with a key lost at process restart."""
    monkeypatch.delenv("AGENT_WORKFLOW_SIGNING_KEY", raising=False)
    with pytest.raises(ValueError, match="AGENT_WORKFLOW_SIGNING_KEY"):
        FoodOrderingAgent(RecoveryToolClient(), workflow_store=FakeWorkflowStore())


def test_http_app_uses_configured_file_store_after_app_restart(tmp_path, monkeypatch):
    """Break caught: the deployed HTTP entry point ignores durable-store configuration."""
    from fastapi.testclient import TestClient
    from fandazi_agent.main import create_app

    monkeypatch.setenv("AGENT_WORKFLOW_STORE_DIR", str(tmp_path))
    monkeypatch.setenv("AGENT_WORKFLOW_SIGNING_KEY", "r4-http-test-signing-key")
    monkeypatch.setenv("AGENT_INTERNAL_TOKEN", "r4-http-test-token")
    client = RecoveryToolClient([uncertain_failure()])
    monkeypatch.setattr("fandazi_agent.main.SpringBootToolClient", lambda *args: client)
    headers = {"X-Agent-Token": "r4-http-test-token"}
    first = TestClient(create_app()).post("/internal/agent/run", headers=headers, json=request().model_dump(by_alias=True))
    second = TestClient(create_app()).post("/internal/agent/run", headers=headers, json=request("继续", trace_id="trace-http-restart").model_dump(by_alias=True))

    assert first.status_code == second.status_code == 200
    assert first.json()["next_action"] == "order_recovery"
    assert second.json()["status"] == "completed"
    orders = [item for item in client.requests if item.tool_name == "create_order"]
    assert len(orders) == 2
    assert orders[0].idempotency_key == orders[1].idempotency_key
    assert [item.tool_name for item in client.requests].count("create_cart_draft") == 1


def test_restart_after_create_success_retries_only_payment():
    """Break caught: a crash at payment replays an already completed create_order."""
    class CrashAtPaymentClient(RecoveryToolClient):
        def call(self, tool_request):
            if tool_request.tool_name == "pay_mock_order":
                raise SimulatedProcessExit()
            return super().call(tool_request)

    store = FakeWorkflowStore()
    first_client = CrashAtPaymentClient()
    with pytest.raises(SimulatedProcessExit):
        make_agent(first_client, workflow_store=store).run(request())
    snapshot = store.load("u-1", "recovery")
    assert snapshot["next_action"] == "payment_retry"
    assert "create_order" in snapshot["completed_tools"]
    second_client = RecoveryToolClient()
    restarted = make_agent(second_client, workflow_store=store)
    result = restarted.run(request("继续支付"))
    assert result.status == "completed"
    assert [item.tool_name for item in second_client.requests] == ["pay_mock_order"]


def test_recovery_tool_budget_failure_preserves_original_uncertainty():
    """Break caught: a local retry limit erases an unresolved backend operation key."""
    store = FakeWorkflowStore()
    client = RecoveryToolClient([uncertain_failure()])
    make_agent(client, workflow_store=store).run(request())
    snapshot = store.load("u-1", "recovery")
    settings = Settings(None, None, None, "http://localhost:8080", 16, 0, 100, "r4-test-signing-key")
    result = FoodOrderingAgent(client, settings=settings, workflow_store=store).run(request("继续"))
    assert result.status == "failed"
    assert result.error == "工具调用次数超过上限"
    assert result.next_action == "order_recovery"
    assert make_agent(client, workflow_store=store).run(request("继续")).status == "completed"
    orders = [item for item in client.requests if item.tool_name == "create_order"]
    assert len(orders) == 2
    assert orders[0].idempotency_key == orders[1].idempotency_key == snapshot["workflow_id"] + ":create_order"


def test_confirmation_budget_failure_clears_terminal_state_from_durable_store():
    """Break caught: broadening snapshot persistence keeps a failed confirmation locked forever."""
    store = FakeWorkflowStore()
    client = RecoveryToolClient()
    assert make_agent(client, workflow_store=store).run(request(mode="order")).status == "awaiting_confirmation"
    settings = Settings(None, None, None, "http://localhost:8080", 0, 8, 100, "r4-test-signing-key")
    restarted = FoodOrderingAgent(client, settings=settings, workflow_store=store)
    result = restarted.run(request("确认", mode="order"))
    assert result.status == "failed"
    assert result.error == "图执行步数超过上限"
    assert store.load("u-1", "recovery") is None
    with pytest.raises(KeyError):
        restarted.workflow_config("u-1", "recovery")
    assert "create_order" not in [item.tool_name for item in client.requests]


@pytest.mark.parametrize("contents", ["null", "[]", "not-json", "{}"])
def test_corrupt_snapshot_file_fails_closed_without_starting_a_new_order(tmp_path, contents):
    """Break caught: a corrupt JSON null record is mistaken for an absent workflow."""
    from fandazi_agent.workflow_store import FileWorkflowStore

    store = FileWorkflowStore(tmp_path)
    client = RecoveryToolClient([uncertain_failure()])
    make_agent(client, workflow_store=store).run(request())
    next(tmp_path.glob("*.json")).write_text(contents, encoding="utf-8")
    before = len(client.requests)
    with pytest.raises(ValueError):
        make_agent(client, workflow_store=store).run(request("继续"))
    assert len(client.requests) == before


def test_file_store_recovers_in_a_separate_python_process(tmp_path):
    """Break caught: recovery passes only because two agents share process memory or a key."""
    from fandazi_agent.workflow_store import FileWorkflowStore

    store = FileWorkflowStore(tmp_path)
    client = RecoveryToolClient([uncertain_failure()])
    make_agent(client, workflow_store=store).run(request())
    original_key = next(item.idempotency_key for item in client.requests if item.tool_name == "create_order")
    child_code = """
import json, sys
from test_order_recovery_revision import RecoveryToolClient, make_agent, request
from fandazi_agent.workflow_store import FileWorkflowStore
client = RecoveryToolClient()
agent = make_agent(client, workflow_store=FileWorkflowStore(sys.argv[1]))
result = agent.run(request('继续', trace_id='trace-child'))
print(json.dumps({'result': result.model_dump(mode='json'), 'requests': [item.model_dump() for item in client.requests]}))
"""
    task_env = os.environ.copy()
    task_env["PYTHONPATH"] = os.pathsep.join([str(Path(__file__).parent), str(Path(__file__).parents[1] / "src")])
    child = subprocess.run([sys.executable, "-c", child_code, str(tmp_path)], env=task_env, capture_output=True, text=True, check=True, timeout=30)
    recovered = json.loads(child.stdout)

    assert recovered["result"]["status"] == "completed"
    assert [item["tool_name"] for item in recovered["requests"]] == ["create_order", "pay_mock_order"]
    assert recovered["requests"][0]["idempotency_key"] == original_key
    assert recovered["requests"][0]["trace_id"] == "trace-child"
    assert store.load("u-1", "recovery") is None


@pytest.mark.parametrize("text", ["继续", "取消"])
def test_file_store_recovers_after_abrupt_process_exit(tmp_path, text):
    """Break caught: abrupt exit loses the write-ahead key or cancellation dispatches an order."""
    from fandazi_agent.workflow_store import FileWorkflowStore

    child_code = """
import json, os, sys
from test_order_recovery_revision import RecoveryToolClient, make_agent, request
from fandazi_agent.workflow_store import FileWorkflowStore

class ExitAtCreateClient(RecoveryToolClient):
    def call(self, tool_request):
        if tool_request.tool_name == 'create_order':
            print(json.dumps(tool_request.model_dump(mode='json')), flush=True)
            os._exit(23)
        return super().call(tool_request)

client = ExitAtCreateClient() if sys.argv[2] == 'crash' else RecoveryToolClient()
agent = make_agent(client, workflow_store=FileWorkflowStore(sys.argv[1]))
result = agent.run(request(sys.argv[2], trace_id='trace-child'))
print(json.dumps({'result': result.model_dump(mode='json'), 'requests': [item.model_dump() for item in client.requests]}))
"""
    task_env = os.environ.copy()
    task_env["PYTHONPATH"] = os.pathsep.join([str(Path(__file__).parent), str(Path(__file__).parents[1] / "src")])
    child = subprocess.run([sys.executable, "-c", child_code, str(tmp_path), "crash"], env=task_env, capture_output=True, text=True, timeout=30)
    assert child.returncode == 23, child.stderr
    dispatched = json.loads(child.stdout)
    saved = FileWorkflowStore(tmp_path).load("u-1", "recovery")
    assert saved["next_action"] == "order_recovery"
    assert dispatched["idempotency_key"] == saved["workflow_id"] + ":create_order"

    restarted = subprocess.run([sys.executable, "-c", child_code, str(tmp_path), text], env=task_env, capture_output=True, text=True, check=True, timeout=30)
    recovered = json.loads(restarted.stdout)
    assert recovered["result"]["status"] == "completed"
    if text == "取消":
        assert recovered["result"]["next_action"] == "cancelled"
        assert recovered["requests"] == []
    else:
        assert [item["tool_name"] for item in recovered["requests"]] == ["create_order", "pay_mock_order"]
        assert recovered["requests"][0]["idempotency_key"] == dispatched["idempotency_key"]
        assert recovered["requests"][0]["payload"] == dispatched["payload"]
    assert FileWorkflowStore(tmp_path).load("u-1", "recovery") is None


def test_file_store_failed_replace_preserves_previous_recovery(tmp_path, monkeypatch):
    """Break caught: a failed snapshot update truncates the only recoverable operation key."""
    from fandazi_agent.workflow_store import FileWorkflowStore

    store = FileWorkflowStore(tmp_path)
    original = {"workflow_id": "original", "operation_keys": {"create_order": "original:create_order"}}
    store.save("u-1", "recovery", original)

    def fail_replace(*args):
        raise OSError("replace failed")

    monkeypatch.setattr(os, "replace", fail_replace)
    with pytest.raises(OSError, match="replace failed"):
        store.save("u-1", "recovery", {"workflow_id": "replacement"})
    assert FileWorkflowStore(tmp_path).load("u-1", "recovery") == original
    assert len(list(tmp_path.iterdir())) == 1


def test_file_store_owner_key_has_no_delimiter_collision_or_path_traversal(tmp_path):
    """Break caught: owner key concatenation overwrites another session or escapes the store."""
    from fandazi_agent.workflow_store import FileWorkflowStore

    store = FileWorkflowStore(tmp_path)
    store.save("a|b", "c", {"workflow_id": "first"})
    store.save("a", "b|c", {"workflow_id": "second"})
    store.save("../outside", "../escape", {"workflow_id": "third"})
    assert store.load("a|b", "c") == {"workflow_id": "first"}
    assert store.load("a", "b|c") == {"workflow_id": "second"}
    assert store.load("../outside", "../escape") == {"workflow_id": "third"}
    assert len(list(tmp_path.iterdir())) == 3
    store.delete("a|b", "c")
    assert store.load("a|b", "c") is None
    assert store.load("a", "b|c") == {"workflow_id": "second"}
