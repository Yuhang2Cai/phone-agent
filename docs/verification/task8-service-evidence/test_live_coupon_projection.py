"""Task8 优惠券运行环境独立验收：真实 MySQL 合成夹具、8080 查询和新 Agent 聊天卡片。

只向本模块刚注册的合成账号建立测试券，绝不改变七场景或真实用户的领取/日期/状态。
服务调用全部为只读查询，不测试领取、抵扣或核销；结束时按本模块生成的编号清理合成券行。
"""

import os
import uuid
from datetime import datetime, timedelta
from decimal import Decimal
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo

import httpx
import jwt
import pytest
from sqlalchemy import MetaData, Table, URL, and_, create_engine, delete, insert, select, text


# 首尾日按上海业务日含在有效期内；已使用即使定义过期/停用也保持历史终态。
CASES = (
    ("expired", -10, -1, True, "AVAILABLE", "EXPIRED"),
    ("future", 1, 10, True, "AVAILABLE", "NOT_YET_VALID"),
    ("disabled", -1, 1, False, "AVAILABLE", "DISABLED"),
    ("valid", -1, 1, True, "AVAILABLE", "AVAILABLE"),
    ("first_day", 0, 1, True, "AVAILABLE", "AVAILABLE"),
    ("last_day", -1, 0, True, "AVAILABLE", "AVAILABLE"),
    ("used", -10, -1, False, "USED", "USED"),
    ("persisted_expired", -1, 1, True, "EXPIRED", "EXPIRED"),
)


@pytest.fixture(scope="module")
def fixture():
    """生成模块独占账号与八组券定义；真实接口读取后清理合成行，不打印凭据或完整事件。"""
    parsed = urlsplit(os.environ["DB_URL"].removeprefix("jdbc:"))
    engine = create_engine(URL.create("mysql+pymysql", username=os.environ["DB_USERNAME"], password=os.environ["DB_PASSWORD"],
                                     host=parsed.hostname, port=parsed.port or 3306, database=parsed.path.lstrip("/"),
                                     query={"charset": "utf8mb4"}), pool_pre_ping=True)
    metadata = MetaData()
    definitions = Table("coupons", metadata, autoload_with=engine)
    claims = Table("user_coupons", metadata, autoload_with=engine)
    with engine.connect() as connection:
        assert connection.execute(text("select database()")).scalar_one() == "fandazi_groupbuy_dev"
    records = {}
    definition_ids = []
    claim_ids = []
    owner = None
    with httpx.Client(base_url="http://127.0.0.1:8080", trust_env=False, timeout=120) as client:
        response = client.post("/api/auth/register", json={"username": "coupon8_" + uuid.uuid4().hex[:16], "password": uuid.uuid4().hex})
        assert response.status_code == 201
        token = response.json()["token"]
        client.headers["Authorization"] = "Bearer " + token
        # 解码只限制本测试夹具与查询范围，真实接口仍使用后端验签后的身份，不以解码结果替代认证。
        owner = int(jwt.decode(token, options={"verify_signature": False})["sub"])
        today = datetime.now(ZoneInfo("Asia/Shanghai")).date()
        try:
            with engine.begin() as connection:
                for name, start, end, active, persisted, expected in CASES:
                    coupon_id, claim_id = str(uuid.uuid4()), str(uuid.uuid4())
                    valid_from, valid_to = today + timedelta(days=start), today + timedelta(days=end)
                    # 验收数据仅在新账号下构造，不通过修改已有券来伪造有效性；金额存储使用真实 DECIMAL 列。
                    result = connection.execute(insert(definitions).values(public_id=coupon_id, name="独立验收券 " + name,
                        threshold_amount=Decimal("50.00"), discount_amount=Decimal("5.00"), valid_from=valid_from,
                        valid_to=valid_to, scope_description="独立只读投影验收", active=active))
                    internal_id = result.inserted_primary_key[0]
                    connection.execute(insert(claims).values(public_id=claim_id, user_id=owner, coupon_id=internal_id,
                        status=persisted, claimed_at=datetime.now(ZoneInfo("Asia/Shanghai")).replace(tzinfo=None)))
                    definition_ids.append(coupon_id)
                    claim_ids.append(claim_id)
                    records[name] = dict(couponId=coupon_id, claimId=claim_id, expected=expected, persisted=persisted,
                                         validFrom=valid_from.isoformat(), validTo=valid_to.isoformat())
            yield client, owner, engine, claims, records
        finally:
            # 清理只允许本模块生成的随机券编号，且领取行同时限定新账号；不删除用户、业务订单或其他夹具。
            if owner is not None and claim_ids:
                with engine.begin() as connection:
                    connection.execute(delete(claims).where(and_(claims.c.user_id == owner, claims.c.public_id.in_(claim_ids))))
                    connection.execute(delete(definitions).where(definitions.c.public_id.in_(definition_ids)))
            engine.dispose()


@pytest.mark.parametrize("scenario", [case[0] for case in CASES])
def test_live_coupon_date_state_and_read_only_persistence(fixture, scenario):
    """真实 HTTP 返回确切展示状态，日期与金额保留，底层领取状态不得被只读投影回写。"""
    client, owner, engine, claims, records = fixture
    facts = records[scenario]
    response = client.get("/api/coupons")
    assert response.status_code == 200
    row = next(item for item in response.json() if item["couponId"] == facts["couponId"])
    assert row["status"] == facts["expected"]
    assert row["claimId"] == facts["claimId"]
    assert row["validFrom"] == facts["validFrom"] and row["validTo"] == facts["validTo"]
    assert row["thresholdCents"] == 5000 and row["discountCents"] == 500
    with engine.connect() as connection:
        persisted = connection.execute(select(claims.c.status).where(and_(claims.c.user_id == owner,
            claims.c.public_id == facts["claimId"]))).scalar_one()
    assert persisted == facts["persisted"]
    # 只输出合成场景名与状态，不记录完整资源编号、用户凭据或私人响应。
    print(f"LIVE_COUPON scenario={scenario} api={row['status']} persisted={persisted} dates_preserved=true")


def test_live_coupon_ownership_and_authentication(fixture):
    """另一独立账号的列表为空，伪造 userId 参数不能覆盖认证归属，未认证仍拒绝。"""
    client, owner, _, _, _ = fixture
    other = client.post("/api/auth/register", json={"username": "coupon8_" + uuid.uuid4().hex[:16], "password": uuid.uuid4().hex})
    assert other.status_code == 201
    headers = {"Authorization": "Bearer " + other.json()["token"]}
    response = client.get("/api/coupons", headers=headers, params={"userId": owner})
    assert response.status_code == 200 and response.json() == []
    assert client.get("/api/coupons", headers={"Authorization": ""}).status_code == 401


def test_live_new_agent_uses_actual_coupon_tool_and_projected_cards(fixture):
    """新 D 项目真实分类与 query_coupon 必须执行；最终卡片保留八种后端状态且不泄漏确认凭据。"""
    client, owner, engine, claims, records = fixture
    with httpx.Client(trust_env=False, timeout=5) as probe:
        health = probe.get("http://127.0.0.1:8091/internal/v1/health")
        assert health.status_code == 200
        source = health.json()
        assert source["sourceRoot"].replace("\\", "/").lower() == "d:/project/-demo-master"
        assert source["entryPoint"] == "service.app:create_app" and source["schemaVersion"] == "1.1"
    response = client.post(f"/api/agent/sessions/{uuid.uuid4()}/messages", json={"text": "查询我的优惠券", "mode": "CONFIRM"})
    assert response.status_code == 200
    result = response.json()
    assert result["status"] == "COMPLETED"
    decided = next(item for item in result["events"] if item["type"] == "intent_decided")
    assert decided["payload"]["intent"] == "coupon" and decided["payload"]["isComposite"] is False
    tools = {item["toolName"] for item in result["events"] if item["type"] == "tool_result"}
    assert tools == {"query_coupon"}
    cards = {item["couponId"]: item for item in result["cards"] if item["type"] == "coupon"}
    assert set(cards) == {facts["couponId"] for facts in records.values()}
    for facts in records.values():
        assert cards[facts["couponId"]]["status"] == facts["expected"]
    for forbidden in ("confirmationGrantId", "paymentConfirmationGrantId", "confirmationGrant"):
        assert forbidden not in response.text
    # 双服务工具与卡片富化都只读：查询结束后再次核对整个合成领取集仍保留原持久化状态。
    with engine.connect() as connection:
        stored = dict(connection.execute(select(claims.c.public_id, claims.c.status).where(claims.c.user_id == owner)).all())
    assert stored == {facts["claimId"]: facts["persisted"] for facts in records.values()}
    print("LIVE_COUPON_AGENT source=D-project intent=coupon tool=query_coupon cards=8 unchanged_claims=8")
