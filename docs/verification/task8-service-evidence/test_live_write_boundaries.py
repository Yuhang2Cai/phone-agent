"""Task8 真实双服务独立边界验收：隔离注册账号，读取 MySQL 行数核实准备/拒绝/提交的副作用。

依赖现有 8080、8091 与共享环境；不重置七夹具，不修改数据库事实来取得通过，不记录密码或 JWT。
测试创建的订单、付款、退款和投诉均通过公开业务接口完成；数据库只允许新增独占套餐及结束后下架它，旧记录只读。
"""

import os
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from urllib.parse import urlsplit

import httpx
import jwt
import pytest
from sqlalchemy import URL, MetaData, Table, create_engine, select, text


@pytest.fixture
def backend():
    """注册独立合成账号，并从共享环境连接业务 MySQL；凭据只在进程内传递。"""
    parsed = urlsplit(os.environ["DB_URL"].removeprefix("jdbc:"))
    engine = create_engine(URL.create("mysql+pymysql", username=os.environ["DB_USERNAME"], password=os.environ["DB_PASSWORD"],
                                     host=parsed.hostname, port=parsed.port or 3306, database=parsed.path.lstrip("/"),
                                     query={"charset": "utf8mb4"}), pool_pre_ping=True)
    with engine.connect() as connection:
        assert connection.execute(text("select database()")).scalar_one() == "fandazi_groupbuy_dev"
    with httpx.Client(base_url="http://127.0.0.1:8080", trust_env=False, timeout=120) as client:
        registered = client.post("/api/auth/register", json={"username": "ind8_" + uuid.uuid4().hex[:16], "password": uuid.uuid4().hex})
        assert registered.status_code == 201
        token = registered.json()["token"]
        client.headers["Authorization"] = "Bearer " + token
        # 解码只用于对本测试账号限定只读计数；真实 API 认证仍由 Spring 验证原始签名，不能以此冒充身份。
        user_id = int(jwt.decode(token, options={"verify_signature": False})["sub"])
        try:
            yield client, user_id, engine
        finally:
            engine.dispose()


def count_owned(engine, table, user_id):
    """只允许两张售后表，使用参数化用户 ID 统计合成账号的实际记录，不读取其他用户正文。"""
    assert table in {"refund_requests", "complaint_tickets"}
    with engine.connect() as connection:
        return connection.execute(text(f"select count(*) from {table} where user_id=:owner"), {"owner": user_id}).scalar_one()


@contextmanager
def isolated_deal(engine):
    """新增随机独占套餐并返回公开 ID；结束时仅下架新行，异常也不回滚业务历史。

    调用者须已校验开发数据库。来源只用于复制字段，不参与购买；原目录库存绝不能被测试消费。
    """
    table = Table('deal_packages', MetaData(), autoload_with=engine)
    public_id = str(uuid.uuid4())
    title = 'Task8隔离边界-' + public_id
    with engine.begin() as connection:
        # 反射真实列避免遗漏图片/价格字段；自增主键和可写身份必须重新生成，禁止复用来源身份。
        source = connection.execute(select(table).where(table.c.active.is_(True)).order_by(table.c.id).limit(1)).mappings().first()
        assert source is not None, '没有可复制的套餐来源，禁止改用公共目录购买'
        values = {key: value for key, value in source.items() if key != 'id'}
        values.update(public_id=public_id, title=title, inventory=2, active=True)
        connection.execute(table.insert().values(**values))
    try:
        yield public_id
    finally:
        # 保留新增行及真实订单/退款以供审计；仅按两项独占身份下架，不能清库或回填共享库存。
        with engine.begin() as connection:
            result = connection.execute(table.update().where(table.c.public_id == public_id, table.c.title == title).values(active=False))
            assert result.rowcount == 1, '独占套餐身份变化，禁止扩大更新范围'


def purchase_paid_voucher(client, deal_id):
    """只购买调用者刚创建的独占套餐；订单金额与身份取真实报价，不从公开目录挑选。"""
    session = str(uuid.uuid4())
    quote = client.post("/api/purchases/quotes", json={"dealId": deal_id, "quantity": 1, "sessionId": session})
    assert quote.status_code == 200
    facts = quote.json()
    assert facts['dealId'] == deal_id, '报价不能切换到非独占套餐'
    # 当前契约必须绑定报价 ID，不能靠省略字段的历史脚本验证新的购买流程。
    order = client.post("/api/group-orders", json={"quoteId": facts['quoteId'], "dealId": facts["dealId"], "quantity": 1, "sessionId": session,
                        "confirmationGrant": facts["confirmationGrant"], "idempotencyKey": str(uuid.uuid4())})
    assert order.status_code == 200
    paid = client.post(f"/api/group-orders/{order.json()['orderId']}/mock-pay", json={"idempotencyKey": str(uuid.uuid4())})
    assert paid.status_code == 200
    return paid.json()


def test_live_refund_validation_grant_binding_and_persisted_progress(backend):
    """无申请、准备、非法原因、错误会话和越权均不写；合法 500 字原因只写一次并可按券重读。"""
    client, owner, engine = backend
    with isolated_deal(engine) as deal_id:
        paid = purchase_paid_voucher(client, deal_id)
    voucher = paid["vouchers"][0]["publicId"]
    session = str(uuid.uuid4())
    assert count_owned(engine, "refund_requests", owner) == 0
    assert client.get(f"/api/refunds/by-voucher/{voucher}").status_code == 404
    prepared = client.post("/api/refunds/prepare", json={"sessionId": session, "voucherId": voucher})
    assert prepared.status_code == 200
    facts = prepared.json()
    assert facts["amountCents"] == paid["totalCents"]
    assert count_owned(engine, "refund_requests", owner) == 0
    payload = {"sessionId": session, "voucherId": voucher, "reason": "验" * 500, "confirmationGrant": facts["confirmationGrant"]}
    # 输入校验先于凭证消费：两次非法输入应都拒绝，随后原凭证仍能授权用户修正后的合法请求。
    for reason in ["   ", "验" * 501]:
        rejected = client.post("/api/refunds", json={**payload, "reason": reason})
        assert rejected.status_code == 400
        assert count_owned(engine, "refund_requests", owner) == 0
    assert client.post("/api/refunds", json={**payload, "sessionId": str(uuid.uuid4())}).status_code == 400
    assert count_owned(engine, "refund_requests", owner) == 0
    submitted = client.post("/api/refunds", json=payload)
    assert submitted.status_code == 200
    result = submitted.json()
    assert result["status"] == "REQUESTED" and result["amountCents"] == paid["totalCents"]
    assert result["reason"] == payload["reason"]
    assert datetime.fromisoformat(result["createdAt"].replace("Z", "+00:00")).tzinfo == timezone.utc
    assert count_owned(engine, "refund_requests", owner) == 1
    assert client.get(f"/api/refunds/by-voucher/{voucher}").json() == result
    assert client.get(f"/api/refunds/{result['requestId']}").json() == result
    assert client.post("/api/refunds", json=payload).status_code == 400
    assert count_owned(engine, "refund_requests", owner) == 1
    # 归属校验：第二账号只用于横向越权请求，不保存账号、令牌或资源完整响应。
    other = client.post("/api/auth/register", json={"username": "ind8_" + uuid.uuid4().hex[:16], "password": uuid.uuid4().hex})
    assert other.status_code == 201
    headers = {"Authorization": "Bearer " + other.json()["token"]}
    for path in [f"/api/refunds/by-voucher/{voucher}", f"/api/refunds/{result['requestId']}"]:
        assert client.get(path, headers=headers).status_code == 400
        assert client.get(path, headers={"Authorization": ""}).status_code == 401
    assert client.post("/api/refunds/prepare", json={"sessionId": str(uuid.uuid4()), "voucherId": voucher}, headers=headers).status_code == 400
    assert count_owned(engine, "refund_requests", owner) == 1


def test_live_complaint_validation_prepare_and_exactly_one_ticket(backend):
    """准备不生成工单；空白/超长/错会话拒绝，合法上限正文提交 OPEN 一次且重复不能再写。"""
    client, owner, engine = backend
    session = str(uuid.uuid4())
    assert count_owned(engine, "complaint_tickets", owner) == 0
    prepared = client.post("/api/complaints/prepare", json={"sessionId": session})
    assert prepared.status_code == 200
    assert count_owned(engine, "complaint_tickets", owner) == 0
    payload = {"sessionId": session, "subject": "验" * 255, "description": "验" * 2000,
               "confirmationGrant": prepared.json()["confirmationGrant"]}
    # 四个独立输入拒绝都保持零工单，不能消耗合法准备授权导致后续修正不可继续。
    for update in [{"subject": " "}, {"subject": "验" * 256}, {"description": " "}, {"description": "验" * 2001}]:
        assert client.post("/api/complaints", json={**payload, **update}).status_code == 400
        assert count_owned(engine, "complaint_tickets", owner) == 0
    assert client.post("/api/complaints", json={**payload, "sessionId": str(uuid.uuid4())}).status_code == 400
    assert count_owned(engine, "complaint_tickets", owner) == 0
    submitted = client.post("/api/complaints", json=payload)
    assert submitted.status_code == 200
    result = submitted.json()
    assert result["status"] == "OPEN"
    assert result["subject"] == payload["subject"]
    assert datetime.fromisoformat(result["createdAt"].replace("Z", "+00:00")).tzinfo == timezone.utc
    assert count_owned(engine, "complaint_tickets", owner) == 1
    assert client.post("/api/complaints", json=payload).status_code == 400
    assert count_owned(engine, "complaint_tickets", owner) == 1
