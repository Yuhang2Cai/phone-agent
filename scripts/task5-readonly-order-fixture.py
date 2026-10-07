"""Task5 订单卡隔离夹具：只新增随机本任务用户的待支付订单/未付款券，事实随后必须经 Spring 读取。"""
import json
import os
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from urllib.parse import urlparse
from zoneinfo import ZoneInfo

import httpx
import pymysql


def main():
    """读取忽略凭据和真实目录，在单一事务创建隔离事实；不付款、不核销、不消耗库存或 Task8 资源。"""
    root = Path("C:/fdz-build")
    account = json.loads((root / ".runtime/task5-account.json").read_text(encoding="utf-8-sig"))
    if not account["username"].startswith("task5_"):
        raise RuntimeError("账号不属于本任务，拒绝创建夹具")
    with httpx.Client(base_url="http://127.0.0.1:8080/api", trust_env=False, timeout=20) as client:
        # 真实名称/套餐/价格只从 Spring 目录读取，客户端与夹具脚本均不设置业务样例。
        stores = client.get("/stores").raise_for_status().json()
        deals = client.get(f"/stores/{stores[0]['publicId']}/deals").raise_for_status().json()
        deal = next(value for value in deals if value["active"] and value["inventory"] > 0)
    address = urlparse(os.environ["DB_URL"].removeprefix("jdbc:"))
    if address.path.strip("/") != "fandazi_groupbuy_dev":
        raise RuntimeError("目标业务库不是已核实开发库")
    connection = pymysql.connect(host=address.hostname, port=address.port or 3306,
        user=os.environ["DB_USERNAME"], password=os.environ["DB_PASSWORD"], database=address.path.strip("/"), charset="utf8mb4")
    fixture_file = root / ".runtime/task5-order-fixture.json"
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT id FROM app_users WHERE username=%s", (account["username"],))
            user = cursor.fetchone()
            if not user:
                raise RuntimeError("本任务用户不存在")
            # 已建立夹具时幂等读取，不创建重复订单或伪造购买闭环证据。
            if fixture_file.exists():
                fixture = json.loads(fixture_file.read_text(encoding="utf-8"))
                cursor.execute("SELECT public_id FROM group_buy_orders WHERE user_id=%s AND public_id=%s", (user[0], fixture["orderId"]))
                if not cursor.fetchone():
                    raise RuntimeError("隔离订单夹具与数据库不一致")
                return
            cursor.execute("SELECT id,store_id FROM deal_packages WHERE public_id=%s", (deal["publicId"],))
            deal_row = cursor.fetchone()
            order_id, voucher_id = str(uuid.uuid4()), str(uuid.uuid4())
            # 领域数据库使用无时区上海业务时间，不能把主机 UTC 墙钟写成上海时间导致展示偏移。
            now = datetime.now(ZoneInfo("Asia/Shanghai")).replace(tzinfo=None)
            expiry = now + timedelta(days=7)
            # 待支付事实没有 paid_at/paid_total_amount/allocated_amount，不把准备夹具描述为实际购买或付款。
            cursor.execute("INSERT INTO group_buy_orders(public_id,user_id,store_id,deal_id,idempotency_key,quantity,total_amount,status,created_at,expires_at,request_fingerprint) VALUES(%s,%s,%s,%s,%s,1,%s,'PENDING_PAYMENT',%s,%s,%s)",
                (order_id, user[0], deal_row[1], deal_row[0], "task5-fixture-" + order_id, deal["priceCents"] / 100, now, expiry, "task5-readonly-display-fixture"))
            internal_order = cursor.lastrowid
            cursor.execute("INSERT INTO vouchers(public_id,order_id,user_id,deal_id,status,qr_token,expires_at,created_at) VALUES(%s,%s,%s,%s,'UNUSED',%s,%s,%s)",
                (voucher_id, internal_order, user[0], deal_row[0], "qr-" + voucher_id, expiry, now))
            connection.commit()
            fixture = dict(orderId=order_id, voucherId=voucher_id, dealId=deal["publicId"], quantity=1,
                totalCents=deal["priceCents"], status="PENDING_PAYMENT", source="本任务随机用户的真实数据库隔离展示夹具；非购买/支付验收", createdAt=now.isoformat())
            fixture_file.write_text(json.dumps(fixture, ensure_ascii=False, indent=2), encoding="utf-8")
            # 公开来源不包含账户、密码或二维码令牌；资源 ID 供独立验收通过 Spring 核对。
            (root / "docs/verification/task5-evidence/order-fixture-provenance.json").write_text(json.dumps(fixture, ensure_ascii=False, indent=2), encoding="utf-8")
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
