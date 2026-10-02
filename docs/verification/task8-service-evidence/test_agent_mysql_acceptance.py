"""Task8 新 Agent 独立验收：真实 dianping_intent 保存检查点，模型与工具只在本测试显式隔离。

本模块不调用 8080、不重启 8091、不读取手机场景凭据。仅生成独立 runId，并在测试结束时清理自身任务行。
它验证持久化、输入校验、恢复和不确定写保护，不将合成分类结果算作在线识别准确率。
"""

import os
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, delete, text

from dag.models import DAGTask
from service.app import create_app
from service.engine import RunEngine
from service.models import ToolFailure
from service.providers import service_database_url
from service.state import MySqlRunStore
from tests.test_after_sale_workflow import AfterSaleTools
from tests.test_agent_service_api import Extractor, build, events, send
from tests.test_agent_service_dag import CompositeClassifier, Planner


class AcceptanceStore(MySqlRunStore):
    """沿用真实 MySQL 存取，仅记录本测试写入的任务标识供精确清理，不改业务实现。"""

    def __init__(self, engine):
        """接收共享环境创建的 MySQL 引擎；生产类负责数据库类型校验与真实落库。"""
        super().__init__(engine)
        self.owned_runs = set()

    def save(self, state):
        """记录独立任务编号后执行原保存方法，禁止以内存字典替代持久化证据。"""
        self.owned_runs.add(state["runId"])
        super().save(state)


@pytest.fixture
def store():
    """只连接独立 Agent 库，清理条件严格限制为本夹具产生的随机 runId，不影响其他服务。"""
    engine = create_engine(service_database_url(os.environ["DB_URL"], os.environ["DB_USERNAME"], os.environ["DB_PASSWORD"]),
                           pool_pre_ping=True, connect_args={"connect_timeout": 3})
    with engine.connect() as connection:
        assert connection.execute(text("select database()")).scalar_one() == "dianping_intent"
    result = AcceptanceStore(engine)
    try:
        yield result
    finally:
        # 只删除本测试持久化的合成检查点，绝不扫描、重置或清空现有任务和审计表。
        if result.owned_runs:
            with engine.begin() as connection:
                connection.execute(delete(result.runs).where(result.runs.c.run_id.in_(result.owned_runs)))
        engine.dispose()


def roundtrip(client, secret, run_id, query, **options):
    """同一随机任务跨轮使用原 runId；请求内容与内部令牌不输出到报告。"""
    return events(send(client, secret, query, run=run_id, **options))


def prepared_refund(store, tools=None):
    """经过真实 HTTP 状态机选券与原因收集，停在准备确认处；合成工具不写真实业务订单。"""
    client, secret, _, tools, _ = build(store=store, tools=tools or AfterSaleTools())
    run_id = "task8-independent-" + uuid.uuid4().hex
    roundtrip(client, secret, run_id, "申请退款")
    roundtrip(client, secret, run_id, "选择团购券 fixture-voucher-1")
    output = roundtrip(client, secret, run_id, "隔离验收原因")
    assert output[-1]["eventType"] == "confirmation_required"
    assert not any(call[0] == "request_refund" for call in tools.calls)
    return client, secret, tools, run_id


@pytest.mark.parametrize("invalid", ["   ", "验" * 501], ids=["blank", "over_limit"])
def test_invalid_reason_is_not_persisted(store, invalid):
    """非法补充留在等待状态，重建引擎后可改正；防止错误槽位污染 MySQL 检查点。"""
    client, secret, _, tools, _ = build(store=store)
    run_id = "task8-independent-" + uuid.uuid4().hex
    roundtrip(client, secret, run_id, "申请退款")
    roundtrip(client, secret, run_id, "选择团购券 fixture-voucher-1")
    result = roundtrip(client, secret, run_id, invalid)
    assert result[-1]["status"] == "waiting_input"
    assert "reason" not in store.load(run_id)["slots"]
    restored_store = MySqlRunStore(store.engine)
    restored, next_secret, _, _, classifier = build(store=restored_store, tools=tools)
    corrected = roundtrip(restored, next_secret, run_id, "行程调整")
    assert corrected[-1]["eventType"] == "confirmation_required"
    assert not classifier.calls


def test_restored_checkpoint_rejects_another_owner(store):
    """全新仓库实例读取真实落库状态；跨用户拒绝须发生在候选与私有凭证公开之前。"""
    _, _, tools, run_id = prepared_refund(store)
    restored, secret, _, _, classifier = build(store=MySqlRunStore(store.engine), tools=tools)
    assert send(restored, secret, "确认退款", run=run_id, user="different-owner").status_code == 403
    before = len(tools.calls)
    pending = roundtrip(restored, secret, run_id, "确认")
    assert pending[-1]["eventType"] == "confirmation_required"
    assert len(tools.calls) == before and not classifier.calls
    completed = roundtrip(restored, secret, run_id, "确认退款", confirmed=True)
    assert completed[-1]["status"] == "succeeded"
    assert sum(call[0] == "request_refund" for call in tools.calls) == 1


@pytest.mark.parametrize("phase", ["voucher", "reason", "confirmation"])
def test_cancellation_never_writes(store, phase):
    """选券、填原因或等待确认时都可取消；MySQL 必须保存 cancelled 且无退款写调用。"""
    client, secret, _, tools, _ = build(store=store)
    run_id = "task8-independent-" + uuid.uuid4().hex
    roundtrip(client, secret, run_id, "退款")
    if phase in {"reason", "confirmation"}:
        roundtrip(client, secret, run_id, "选择团购券 fixture-voucher-1")
    if phase == "confirmation":
        roundtrip(client, secret, run_id, "行程变更")
    result = roundtrip(client, secret, run_id, "取消退款")
    assert result[-1]["status"] == "cancelled"
    assert store.load(run_id)["status"] == "cancelled"
    assert not any(call[0] == "request_refund" for call in tools.calls)


def test_expired_confirmation_is_rejected_after_restore(store):
    """本测试专属合成准备期限过期后，重建引擎不得复活凭证或执行退款写入。"""
    _, _, tools, run_id = prepared_refund(store)
    state = store.load(run_id)
    state["prepared"]["confirmationExpiresAt"] = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()
    store.save(state)
    restored, secret, *_ = build(store=MySqlRunStore(store.engine), tools=tools)
    result = roundtrip(restored, secret, run_id, "确认退款", confirmed=True)
    assert result[-1]["payload"]["code"] == "EXPIRED"
    assert not any(call[0] == "request_refund" for call in tools.calls)


@pytest.mark.parametrize("uncertain", [True, False], ids=["uncertain", "definitively_rejected"])
def test_write_outcome_is_preserved_and_never_replayed(store, uncertain):
    """未知响应与明确拒绝的语义分别保存；重建引擎后两者均不能重放退款请求。"""
    failure = ToolFailure("DEPENDENCY_UNAVAILABLE" if uncertain else "CONFLICT", "隔离写入边界", uncertain=uncertain)
    tools = AfterSaleTools(("request_refund", failure))
    client, secret, _, run_id = prepared_refund(store, tools)
    result = roundtrip(client, secret, run_id, "确认退款", confirmed=True)
    assert result[-1]["eventType"] == "error"
    state = store.load(run_id)
    assert state["uncertain"] is uncertain
    assert ("inFlightWrite" in state) is uncertain
    before = len(tools.calls)
    restored, next_secret, *_ = build(store=MySqlRunStore(store.engine), tools=tools)
    repeated = roundtrip(restored, next_secret, run_id, "确认退款", confirmed=True)
    assert len(tools.calls) == before
    assert ("核查" in repeated[-1]["payload"]["message"]) is uncertain


def test_dag_failed_branch_keeps_independent_answer_and_skips_dependants(store):
    """真实 MySQL 检查点保留 FAQ 成功支路；失败套餐下游不执行，也不能展示未完成写入。"""
    tasks = (DAGTask("faq", "faq", "query_faq", {"question": "退款"}),
             DAGTask("deals", "deal_recommendation", "list_deal_packages", {"storeId": "fixture-store"}),
             DAGTask("detail", "store_info", "get_store_detail", {"storeId": "fixture-store"}, ("deals",)))
    tools = AfterSaleTools(("list_deal_packages", ToolFailure("DEPENDENCY_UNAVAILABLE", "隔离套餐分支失败", True)))
    engine = RunEngine(CompositeClassifier(), Extractor(), tools, store, Planner(tasks))
    secret = uuid.uuid4().hex + uuid.uuid4().hex
    client = TestClient(create_app(engine=engine, internal_secret=secret))
    run_id = "task8-independent-" + uuid.uuid4().hex
    result = roundtrip(client, secret, run_id, "查退款规则并推荐火锅套餐")
    assert result[-1]["eventType"] == "error"
    assert any(item["eventType"] == "text_delta" and "隔离规则答案" in item["payload"]["delta"] for item in result)
    assert {call[0] for call in tools.calls} == {"query_faq", "list_deal_packages"}
    assert "faq" in store.load(run_id)["completedTasks"]
