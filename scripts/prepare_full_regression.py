"""Task12集成全链夹具：仅写独立集成库新增身份，购买与售后由真实API/页面执行。"""
import argparse
import json
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
import httpx
import pymysql


def insert(cursor, table, values):
    """插入受控表的参数化新行，移除旧主键；不更新或删除任何既有业务事实。"""
    allowed = {"stores", "deal_packages", "group_buy_orders", "vouchers"}
    if table not in allowed:
        raise ValueError("夹具表不受支持")
    values = {k: v for k, v in values.items() if k != "id"}
    cursor.execute(f"INSERT INTO `{table}` ("+",".join(f"`{k}`" for k in values)+") VALUES ("+",".join("%s" for _ in values)+")", tuple(values.values()))
    return cursor.lastrowid


def account(client, scenario):
    """注册随机合成用户；密码只返回私有清单，不写公共日志或Git。"""
    value = dict(scenario=scenario, username="t12_"+uuid.uuid4().hex, password=uuid.uuid4().hex)
    reply = client.post("/api/auth/register", json={k: value[k] for k in ("username", "password")}).raise_for_status().json()
    value["userId"] = reply["user"]["id"]
    return value


def main():
    """新清单必须不存在且位于.runtime；目标库固定13307，不能指向原3306业务库。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--secret-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists() or ".runtime" not in args.output.resolve().parts:
        raise ValueError("禁止覆盖清单或公开凭据")
    private = json.loads(args.secret_file.read_text(encoding="utf-8-sig"))
    with httpx.Client(base_url="http://127.0.0.1:18080", trust_env=False, timeout=30) as client, pymysql.connect(
        host="127.0.0.1", port=13307, user="fandazi", password=private["db"], database="fandazi_groupbuy_integration",
        charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor) as db:
        entries = [account(client, name) for name in ("purchase", "reviewable", "no_orders")]
        with db.cursor() as cursor:
            cursor.execute("SELECT DATABASE() AS name")
            if cursor.fetchone()["name"] != "fandazi_groupbuy_integration":
                raise RuntimeError("目标不是独立集成库")
            # 购买只允许刚克隆的独占门店/套餐；菜单依赖仍引用真实目录而非端上演示数据。
            cursor.execute("SELECT s.* FROM stores s JOIN deal_packages d ON d.store_id=s.id WHERE d.active=1 ORDER BY s.id LIMIT 1")
            store = cursor.fetchone()
            if store is None:
                raise RuntimeError("没有真实目录，禁止伪造代替")
            old_store = store["id"]
            store.update(public_id=str(uuid.uuid4()), name=store["name"]+"-集成验收"+uuid.uuid4().hex[:6])
            store_id = insert(cursor, "stores", store)
            cursor.execute("SELECT * FROM deal_packages WHERE store_id=%s AND active=1 ORDER BY id LIMIT 1", (old_store,))
            deal = cursor.fetchone()
            deal.update(public_id=str(uuid.uuid4()), store_id=store_id, inventory=4, active=1)
            insert(cursor, "deal_packages", deal)
            entries[0].update(storeId=store["public_id"], dealId=deal["public_id"], priceCents=deal["price_cents"], quantity=2)
            # 评价初始核销事实仅为开发夹具，不调用或开放延期核销接口；源行和资格仍只读。
            # 已核销订单可合法推进到USED/PARTIALLY_USED；只查PAID会漏掉真实资格，不能伪造核销替代。
            cursor.execute("SELECT o.* FROM group_buy_orders o JOIN vouchers v ON v.order_id=o.id WHERE o.status IN ('PAID','PARTIALLY_USED','USED') AND o.paid_total_amount IS NOT NULL AND v.status='REDEEMED' ORDER BY o.id LIMIT 1")
            order = cursor.fetchone()
            if order is None:
                raise RuntimeError("没有既有可信评价夹具来源")
            source_id = order["id"]
            identity = str(uuid.uuid4())
            now = datetime.now(timezone.utc).replace(tzinfo=None)
            order.update(public_id=identity, user_id=entries[1]["userId"], idempotency_key=uuid.uuid4().hex,
                         created_at=now, expires_at=now+timedelta(days=30))
            new_order = insert(cursor, "group_buy_orders", order)
            cursor.execute("SELECT * FROM vouchers WHERE order_id=%s", (source_id,))
            vouchers = cursor.fetchall()
            voucher_ids = []
            for voucher in vouchers:
                voucher.update(public_id=str(uuid.uuid4()), order_id=new_order, user_id=entries[1]["userId"],
                               qr_token=uuid.uuid4().hex, created_at=now, expires_at=now+timedelta(days=30))
                insert(cursor, "vouchers", voucher)
                voucher_ids.append(voucher["public_id"])
            entries[1].update(orderId=identity, voucherIds=voucher_ids)
        db.commit()
        # 提交后以本人真实API复核资格；不是只相信SQL插入成功或前端布尔值。
        review = entries[1]
        token = client.post("/api/auth/login", json={k: review[k] for k in ("username", "password")}).raise_for_status().json()["token"]
        details = client.get("/api/group-orders/"+review["orderId"], headers={"Authorization": "Bearer "+token}).raise_for_status().json()
        if not details["canReview"]:
            raise RuntimeError("新增评价夹具资格未通过后端核验")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({"schemaVersion": "task12-integration-1", "scenarios": entries}, ensure_ascii=False, indent=2), encoding="utf-8")
    print("新增3个独立集成账号、独占购买套餐及真实评价资格；没有修改源库事实")


if __name__ == "__main__":
    main()
