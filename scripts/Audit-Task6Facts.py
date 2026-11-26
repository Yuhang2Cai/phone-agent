"""Task6 收尾只读审计：核对隔离账号订单、价格恢复和签发期限，不创建或修改业务资源。

依赖已批准环境与真实业务库；完整登录凭据仍仅在忽略目录，公开输出只保留资源事实。
"""
import argparse
import base64
import hashlib
import importlib.util
import json
from decimal import Decimal

import httpx
# 复用既有真实连接与脱敏输出器，不另造数据库或模型替身；文件名带连字符用标准加载器加载。
spec = importlib.util.spec_from_file_location("task6_live", "C:/fdz-build/scripts/task6-live-purchase.py")
live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live)


def digest(facts):
    """对固定字段的规范 JSON 求摘要；仅覆盖价格/版本，不将正常库存扣减伪称恢复。"""
    return hashlib.sha256(json.dumps(facts, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def main():
    """只读核对当前账号全部订单和目录恢复，并标明恢复前摘要是从持久报价重新计算。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("label")
    label = parser.parse_args().label
    with httpx.Client(base_url="http://127.0.0.1:8080/api", trust_env=False, timeout=30) as client, live.database() as db:
        owner = live.account(client)
        # 登录只签发本人令牌，解析生命周期用于验证普通配置，令牌本身不输出。
        token = owner["token"].split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(token + "=" * (-len(token) % 4)))
        prepared = json.loads((live.ROOT / ".runtime/task6-expiry.json").read_text())
        with db.cursor() as cur:
            cur.execute("SELECT q.request_fingerprint,q.quantity,q.total_amount,d.public_id,d.price_amount,d.version FROM purchase_quotes q JOIN deal_packages d ON d.id=q.deal_id WHERE q.public_id=%s AND q.user_id=%s", (prepared["body"]["quoteId"], owner["userId"]))
            row = cur.fetchone()
            # 变价之前持久化的报价身份包括原版本与金额；不是当时已经现场采集的行哈希。
            deal_id, version, quantity, total = row["request_fingerprint"].split(":")
            original = {"dealId": deal_id, "priceCents": int(total) // int(quantity), "version": int(version)}
            current = {"dealId": row["public_id"], "priceCents": int(row["price_amount"] * 100), "version": row["version"]}
            assert original == current
            cur.execute("SELECT o.public_id,o.quantity,o.status,o.total_amount,o.paid_total_amount,COUNT(v.id) voucher_count,SUM(v.allocated_amount) voucher_paid FROM group_buy_orders o LEFT JOIN vouchers v ON v.order_id=o.id WHERE o.user_id=%s GROUP BY o.id ORDER BY o.id", (owner["userId"],))
            orders = cur.fetchall()
        # 本账号从头只进行正常模拟支付；所有行都必须一份一券且金额精确一致。
        for order in orders:
            assert order["status"] == "PAID" and order["quantity"] == order["voucher_count"]
            assert order["total_amount"] == order["paid_total_amount"] == order["voucher_paid"]
        live.save("facts-" + label + ".json", {"orders": orders, "orderCount": len(orders), "voucherCount": sum(o["voucher_count"] for o in orders), "jwtLifetimeSeconds": claims["exp"] - claims["iat"], "priceResourceIsTask6Isolated": False, "priceBeforeFacts": original, "priceAfterFacts": current, "priceBeforeSha256": digest(original), "priceAfterSha256": digest(current), "beforeHashMethod": "根据变价前已持久化报价重算，不是当时采集的完整行哈希", "priceAndVersionRestored": True})
        print(f"read-only audit {label}: {len(orders)} paid orders; price/version restored; token ttl={claims['exp'] - claims['iat']}")


if __name__ == "__main__":
    main()
