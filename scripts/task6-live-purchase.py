"""Task6 真实购买证据：只使用随机本任务账户和公开购买接口，数据库仅核对事实及受控价格变更。

不创建夹具、订单种子、假券或核销记录；凭据和完整请求只保存于忽略的 .runtime。
"""
import argparse
import concurrent.futures
import json
import os
import secrets
import time
import uuid
from decimal import Decimal
from pathlib import Path
from urllib.parse import urlparse

import httpx
import pymysql

ROOT = Path("C:/fdz-build")
EVIDENCE = ROOT / "docs/verification/task6-evidence"
PRIVATE = ROOT / ".runtime/task6-account.json"


def database():
    """读取已批准开发库环境，不输出连接密码，不访问 Agent 库或测试夹具清单。"""
    url = urlparse(os.environ["DB_URL"].removeprefix("jdbc:"))
    assert url.path.strip("/") == "fandazi_groupbuy_dev"
    return pymysql.connect(host=url.hostname, port=url.port or 3306, user=os.environ["DB_USERNAME"],
                           password=os.environ["DB_PASSWORD"], database=url.path.strip("/"), charset="utf8mb4",
                           autocommit=True, cursorclass=pymysql.cursors.DictCursor)


def account(client):
    """真实注册随机隔离账户，后续登录复用本人；认证信息不进入公开报告。"""
    if PRIVATE.exists():
        data = json.loads(PRIVATE.read_text(encoding="utf-8-sig"))
        assert data["username"].startswith("task6_")
        auth = client.post("/auth/login", json={k: data[k] for k in ("username", "password")}).raise_for_status().json()
    else:
        data = dict(username="task6_" + uuid.uuid4().hex[:14], password=secrets.token_urlsafe(22))
        auth = client.post("/auth/register", json={**data, "email": data["username"] + "@example.invalid"}).raise_for_status().json()
    data.update(token=auth["token"], userId=auth["user"]["id"])
    PRIVATE.write_text(json.dumps(data), encoding="utf-8")
    client.headers["Authorization"] = "Bearer " + data["token"]
    return data


def save(name, value):
    """保存白名单公开指标或本任务公开资源，不保存认证头和确认凭证。"""
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    (EVIDENCE / name).write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding="utf-8")


def quote(client, deal, quantity):
    """准备本人独立会话的实际报价；不提交客户端金额。"""
    session = str(uuid.uuid4())
    q = client.post("/purchases/quotes", json={"dealId": deal["publicId"], "quantity": quantity, "sessionId": session}).raise_for_status().json()
    return q, {"quoteId": q["quoteId"], "sessionId": session, "confirmationGrant": q["confirmationGrant"], "idempotencyKey": "task6-" + uuid.uuid4().hex}


def verify_order(connection, owner, result):
    """亲自核对数据库订单、付款快照和一份一券，金额 DECIMAL 合计必须精确相等。"""
    with connection.cursor() as cur:
        cur.execute("SELECT o.public_id,o.quantity,o.status,o.total_amount,o.paid_total_amount,o.paid_at,COUNT(v.id) voucher_count,SUM(v.allocated_amount) voucher_paid FROM group_buy_orders o LEFT JOIN vouchers v ON v.order_id=o.id WHERE o.public_id=%s AND o.user_id=%s GROUP BY o.id", (result["orderId"], owner))
        row = cur.fetchone()
    assert row and row["status"] == "PAID" and row["voucher_count"] == row["quantity"]
    assert row["paid_total_amount"] == row["total_amount"] == row["voucher_paid"] == Decimal(result["totalCents"]) / 100
    assert row["paid_at"] is not None
    return row


def api_tests(client, connection, data):
    """真实不同套餐、数量、并发重复点击、价格变化和自然过期准备，不消费其他任务资源。"""
    stores = client.get("/stores").raise_for_status().json()
    deals = []
    for store in stores:
        deals.extend(client.get(f"/stores/{store['publicId']}/deals").raise_for_status().json())
    deals = [d for d in deals if d["active"] and d["inventory"] >= 8 and d["purchaseLimit"] >= 2]
    assert len(deals) >= 2
    # 先准备自然到期的独立报价，后续验证必须等待真实五分钟而不是改时间或启用故障开关。
    exp, exp_body = quote(client, deals[0], 1)
    (ROOT / ".runtime/task6-expiry.json").write_text(json.dumps({"body": exp_body, "expiresAt": exp["expiresAt"], "createdEpoch": time.time()}), encoding="utf-8")
    save("expiry-prepared.json", {"quoteId": exp["quoteId"], "expiresAt": exp["expiresAt"], "method": "自然等待五分钟，未修改期限"})
    rows = []
    for quantity, deal in zip((1, 2), deals[:2]):
        before = client.get("/group-orders").raise_for_status().json()
        q, body = quote(client, deal, quantity)
        assert client.get("/group-orders").raise_for_status().json() == before
        assert q["subtotalCents"] - q["discountCents"] == q["totalCents"]
        # 同一真实请求并发八次，所有响应必须是同一个订单且不存在第二次库存扣减。
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda _: client.post("/group-orders", json=body), range(8)))
        assert all(r.status_code == 200 for r in results), [r.status_code for r in results]
        orders = [r.json() for r in results]
        assert len({r["orderId"] for r in orders}) == 1
        order = orders[0]
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            paid = list(pool.map(lambda _: client.post(f"/group-orders/{order['orderId']}/mock-pay", json={"idempotencyKey": "task6-pay-" + order["orderId"]}), range(8)))
        assert all(r.status_code == 200 for r in paid)
        result = paid[0].json()
        assert result["totalCents"] == q["totalCents"] and result["quantity"] == quantity
        rows.append({"dealId": deal["publicId"], "quantity": quantity, "quoteTotalCents": q["totalCents"], "createResponses": 8,
                     "distinctOrders": 1, "payResponses": 8, "database": verify_order(connection, data["userId"], result)})
        save("real-api-purchase.json", rows)
        print(json.dumps({"scenario": "purchase", "quantity": quantity, "orderId": result["orderId"], "duplicates": 8}), flush=True)
    # 真正改变已读取套餐的一分钱价格及版本，仅在独占环境内验证旧报价拒绝，finally 精确恢复原价格。
    deal = deals[0]
    old_quote, old_body = quote(client, deal, 1)
    with connection.cursor() as cur:
        cur.execute("SELECT price_amount,version FROM deal_packages WHERE public_id=%s", (deal["publicId"],))
        original = cur.fetchone()
        cur.execute("UPDATE deal_packages SET price_amount=price_amount+0.01,version=version+1 WHERE public_id=%s AND price_amount=%s AND version=%s", (deal["publicId"], original["price_amount"], original["version"]))
        assert cur.rowcount == 1
    try:
        rejected = client.post("/group-orders", json=old_body)
        assert rejected.status_code in (400, 409) and "变化" in rejected.text, rejected.status_code
        fresh, body = quote(client, deal, 1)
        assert fresh["quoteId"] != old_quote["quoteId"] and fresh["totalCents"] == old_quote["totalCents"] + 1
        order = client.post("/group-orders", json=body).raise_for_status().json()
        result = client.post(f"/group-orders/{order['orderId']}/mock-pay", json={"idempotencyKey": "task6-price-pay"}).raise_for_status().json()
        save("price-change.json", {"oldQuoteRejectedHttp": rejected.status_code, "oldTotalCents": old_quote["totalCents"],
             "newTotalCents": fresh["totalCents"], "newQuoteRequired": True, "database": verify_order(connection, data["userId"], result)})
    finally:
        with connection.cursor() as cur:
            cur.execute("UPDATE deal_packages SET price_amount=%s,version=%s WHERE public_id=%s AND price_amount=%s AND version=%s", (original["price_amount"], original["version"], deal["publicId"], original["price_amount"] + Decimal("0.01"), original["version"] + 1))
            assert cur.rowcount == 1
        save("price-restored.json", {"dealId": deal["publicId"], "priceRestored": True, "versionRestored": True})
    print("price change rejected; fresh confirmation paid; original price restored", flush=True)


def expiry_test(client):
    """只在实际期限后提交原报价，服务端必须拒绝且本人订单集合完全不变。"""
    prepared = json.loads((ROOT / ".runtime/task6-expiry.json").read_text())
    assert time.time() - prepared["createdEpoch"] > 301, "自然期限尚未到达，请继续其他验证"
    before = client.get("/group-orders").raise_for_status().json()
    result = client.post("/group-orders", json=prepared["body"])
    assert result.status_code in (400, 409, 410) and "过期" in result.text
    assert client.get("/group-orders").raise_for_status().json() == before
    save("natural-expiry.json", {"http": result.status_code, "elapsedSeconds": round(time.time() - prepared["createdEpoch"]), "noOrderCreated": True})
    print("natural expiry rejected with no order created", flush=True)


def agent_tests(client):
    """亲自经 Spring 请求在线模型与新 Agent，准备暂停、取消、创建及支付分次确认。"""
    deal = client.get("/stores").raise_for_status().json()[0]
    d = client.get(f"/stores/{deal['publicId']}/deals").raise_for_status().json()[0]
    rows = []

    def send(session, label, text):
        """请求公开网关，保存脱敏事件身份和动作，不输出内部授权或完整模型内容。"""
        response = client.post(f"/agent/sessions/{session}/messages", json={"text": text, "mode": "CONFIRM"}).raise_for_status().json()
        (ROOT / (".runtime/task6-agent-" + label + ".json")).write_text(json.dumps(response, ensure_ascii=False), encoding="utf-8")
        originals = [e["payload"] for e in response.get("events", []) if e.get("payload", {}).get("eventId")]
        rows.append({"scenario": label, "status": response["status"], "runId": originals[0]["runId"] if originals else None,
                     "traceId": originals[0]["traceId"] if originals else None, "summary": {k: response.get("candidateCart", {}).get(k) for k in ("action", "resourceId", "amountCents", "quantity", "ruleVersion", "expiresAt")},
                     "tools": [e.get("payload", {}).get("toolName") or e.get("toolName") for e in response.get("events", []) if e.get("toolName") or e.get("payload", {}).get("toolName")]})
        save("agent-live.json", rows)
        print(json.dumps({"scenario": label, "status": response["status"]}), flush=True)
        return response

    session = str(uuid.uuid4())
    first = send(session, "cancel-prepare", f"buy group buying deal {d['publicId']} quantity 1")
    assert first["status"] == "AWAITING_CONFIRMATION"
    cancelled = send(session, "cancel", "取消")
    assert cancelled.get("nextAction") == "cancelled"
    assert rows[0]["runId"] == rows[1]["runId"]
    session = str(uuid.uuid4())
    first = send(session, "purchase-prepare", f"buy group buying deal {d['publicId']} quantity 2")
    assert first["status"] == "AWAITING_CONFIRMATION"
    created = send(session, "purchase-confirm", "确认")
    assert created["status"] == "AWAITING_CONFIRMATION" and created["candidateCart"]["action"] == "pay_mock_order"
    paid = send(session, "pay-confirm", "确认支付")
    assert paid["status"] == "COMPLETED"
    assert len({r["runId"] for r in rows[2:]}) == 1
    assert not any(t in ("create_group_buy_order", "pay_mock_order") for r in (rows[0], rows[1], rows[2]) for t in r["tools"])


def main():
    """分阶段执行，允许自然过期等待期间运行设备验证；所有 HTTP 明确禁代理。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=("api", "expiry", "agent", "agent-prepare-expiry", "agent-expiry", "agent-price", "contract"))
    phase = parser.parse_args().phase
    with httpx.Client(base_url="http://127.0.0.1:8080/api", trust_env=False, timeout=125) as client, database() as connection:
        data = account(client)
        if phase == "api": api_tests(client, connection, data)
        elif phase == "expiry": expiry_test(client)
        elif phase == "agent": agent_tests(client)
        elif phase == "contract": contract_test(client, connection, data)
        else: agent_boundaries(client, connection, data, phase)


def agent_boundaries(client, connection, data, phase):
    """在线 Agent 的自然凭证过期和价格变化拒绝，状态与订单事实都经真实服务核对。"""
    def send(session, text):
        """保留私有原响应，仅返回实际网关数据供本函数核验，不公开短期授权。"""
        response = client.post(f"/agent/sessions/{session}/messages", json={"text": text, "mode": "CONFIRM"}).raise_for_status().json()
        (ROOT / (".runtime/task6-boundary-" + phase + ".json")).write_text(json.dumps(response, ensure_ascii=False), encoding="utf-8")
        return response

    if phase == "agent-expiry":
        prepared = json.loads((ROOT / ".runtime/task6-agent-expiry.json").read_text())
        assert time.time() - prepared["epoch"] > 301
        before = client.get("/group-orders").raise_for_status().json()
        result = send(prepared["session"], "确认")
        assert result["status"] == "FAILED" and "过期" in result["message"]
        assert client.get("/group-orders").raise_for_status().json() == before
        save("agent-natural-expiry.json", {"status": result["status"], "noOrderCreated": True, "elapsedSeconds": round(time.time() - prepared["epoch"]), "sessionId": prepared["session"]})
        print("online Agent expired confirmation rejected without creating order", flush=True)
        return
    store = client.get("/stores").raise_for_status().json()[0]
    deal = client.get(f"/stores/{store['publicId']}/deals").raise_for_status().json()[0]
    session = str(uuid.uuid4())
    result = send(session, f"buy group buying deal {deal['publicId']} quantity 1")
    assert result["status"] == "AWAITING_CONFIRMATION"
    if phase == "agent-prepare-expiry":
        (ROOT / ".runtime/task6-agent-expiry.json").write_text(json.dumps({"session": session, "epoch": time.time()}), encoding="utf-8")
        save("agent-expiry-prepared.json", {"sessionId": session, "expiresAt": result["candidateCart"]["expiresAt"], "naturalWait": True})
        print("online Agent confirmation prepared for natural expiry", flush=True)
        return
    before = client.get("/group-orders").raise_for_status().json()
    with connection.cursor() as cur:
        cur.execute("SELECT price_amount,version FROM deal_packages WHERE public_id=%s", (deal["publicId"],))
        old = cur.fetchone()
        cur.execute("UPDATE deal_packages SET price_amount=price_amount+0.01,version=version+1 WHERE public_id=%s AND price_amount=%s AND version=%s", (deal["publicId"], old["price_amount"], old["version"]))
        assert cur.rowcount == 1
    try:
        result = send(session, "确认")
        assert result["status"] == "FAILED" and "变化" in result["message"]
        assert client.get("/group-orders").raise_for_status().json() == before
        save("agent-price-change.json", {"status": result["status"], "noOrderCreated": True, "sessionId": session, "requiresNewConfirmation": True})
    finally:
        with connection.cursor() as cur:
            cur.execute("UPDATE deal_packages SET price_amount=%s,version=%s WHERE public_id=%s AND price_amount=%s AND version=%s", (old["price_amount"], old["version"], deal["publicId"], old["price_amount"] + Decimal("0.01"), old["version"] + 1))
            assert cur.rowcount == 1
    print("online Agent price change rejected with no order; original price restored", flush=True)


def contract_test(client, connection, data):
    """只复验最终报价必填入口：旧字段不能写订单，正常报价及其重放仍是同一支付事实。"""
    store = client.get("/stores").raise_for_status().json()[0]
    deal = client.get(f"/stores/{store['publicId']}/deals").raise_for_status().json()[1]
    q, body = quote(client, deal, 1)
    before = client.get("/group-orders").raise_for_status().json()
    legacy = {k: v for k, v in body.items() if k != "quoteId"}
    legacy.update(dealId=deal["publicId"], quantity=1)
    rejected = client.post("/group-orders", json=legacy)
    assert rejected.status_code == 400
    assert client.get("/group-orders").raise_for_status().json() == before
    # 拒绝缺报价后原有效授权仍可正常使用；重复操作不能增加订单或券数量。
    order = client.post("/group-orders", json=body).raise_for_status().json()
    replay = client.post("/group-orders", json=body).raise_for_status().json()
    assert replay["orderId"] == order["orderId"]
    pay = {"idempotencyKey": "task6-final-pay-" + order["orderId"]}
    paid = client.post(f"/group-orders/{order['orderId']}/mock-pay", json=pay).raise_for_status().json()
    repeated = client.post(f"/group-orders/{order['orderId']}/mock-pay", json=pay).raise_for_status().json()
    assert repeated["orderId"] == paid["orderId"] and repeated["totalCents"] == q["totalCents"]
    # HTTP 读取实体已离开读取事务，写事务仍必须实际持久化报价消费状态，不能只改脱管对象。
    with connection.cursor() as cur:
        cur.execute("SELECT status FROM purchase_quotes WHERE public_id=%s AND user_id=%s", (q["quoteId"], data["userId"]))
        quote_status = cur.fetchone()["status"]
    assert quote_status == "CONSUMED"
    save("final-http-contract.json", {"missingQuoteHttp": rejected.status_code, "legacyDidNotCreate": True, "sameQuoteCreateReplayed": True, "samePaymentReplayed": True, "quoteStatus": quote_status, "database": verify_order(connection, data["userId"], paid)})
    print("final HTTP quote-only boundary and normal purchase replay passed")


if __name__ == "__main__":
    main()
