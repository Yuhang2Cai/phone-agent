"""Task7独立收尾模块：核对成功页新增事实、轮换本轮凭据并检查公开证据无秘密。

依赖冻结验证器、httpx、pymysql、bcrypt；只允许本轮三个账号与套餐，原业务事实不修改。
原始凭据只在忽略目录，公开结果只有状态与计数；任意断言异常直接停止，保留已有证据。
"""
import importlib.util
import json
import os
import re
import secrets
import sys
from pathlib import Path

import bcrypt
import httpx
import pymysql

# 单独结果子目录保留首次读取证据，不能以收尾结果覆盖第一次运行。
ROOT = Path("C:/fdz-build")
PRIVATE = ROOT / ".runtime/task7-independent"
PUBLIC = ROOT / "docs/verification/task7-independent-evidence"
spec = importlib.util.spec_from_file_location("independent", PUBLIC / "Verify-Independent.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
verifier = module.verifier


def scan(secrets_to_check):
    """输入内存秘密列表；扫描公开文本并保存计数，泄漏时仅抛出文件路径，不输出原值。"""
    checked = 0
    for path in PUBLIC.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in (".json", ".xml", ".md", ".ps1", ".py", ".txt"):
            continue
        body = path.read_text(encoding="utf-8-sig", errors="replace")
        # 同时检查已知本轮口令、批准环境密钥和JWT形态，避免只扫一种字段名遗漏秘密。
        assert not any(value and len(value) >= 8 and value in body for value in secrets_to_check), str(path)
        assert not re.search(r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}", body), str(path)
        checked += 1
    verifier.save(PUBLIC / "public-secret-scan.json", {"checkedTextFiles": checked, "secretMatches": 0, "jwtMatches": 0})


def rotate(connection, client, accounts):
    """输入本轮账号对象；按ID与随机用户名双限定轮换密码，验证旧拒绝/新成功，保存私有新值。"""
    results = []
    for role, account in accounts.items():
        assert account["username"].startswith("task7_"), "非本轮新账号"
        old_password = account["password"]
        new_password = secrets.token_urlsafe(24)
        password_hash = bcrypt.hashpw(new_password.encode(), bcrypt.gensalt(rounds=10)).decode()
        # 只更新本轮新账号一行；不读取或轮换旧任务账号，更不改变原订单/券/售后资格。
        with connection.cursor() as cursor:
            cursor.execute("UPDATE app_users SET password_hash=%s WHERE id=%s AND username=%s", (password_hash, account["userId"], account["username"]))
            assert cursor.rowcount == 1, "本轮账号轮换行数不符"
        old_status = client.post("/auth/login", json={"username": account["username"], "password": old_password}).status_code
        account["password"] = new_password
        # 更新私有文件后再验证，保留重试所需新口令，公开结果绝不包含任一口令或JWT。
        verifier.save(PRIVATE / (role + ".json"), account)
        new_status = client.post("/auth/login", json={"username": account["username"], "password": new_password}).status_code
        # 原AuthController把错误账号口令映射为400，与受保护页面JWT过期401不是同一协议。
        assert old_status == 400 and new_status == 200, "凭据轮换未生效"
        results.append({"role": role, "userId": account["userId"], "oldLoginStatus": old_status, "newLoginStatus": new_status})
    verifier.save(PUBLIC / "own-credentials-rotated.json", results)


def main():
    """命令snapshot或rotate；真实HTTP禁代理，数据库配置取批准环境，异常不吞掉。"""
    phase = sys.argv[1]
    accounts = {role: json.loads((PRIVATE / (role + ".json")).read_text(encoding="utf-8")) for role in ("account", "outsider", "empty")}
    secret_values = [a["password"] for a in accounts.values()] + [value for key, value in os.environ.items() if any(word in key.upper() for word in ("PASSWORD", "SECRET", "API_KEY"))]
    scan(secret_values)
    with pymysql.connect(host="127.0.0.1", user=os.environ["DB_USERNAME"], password=os.environ["DB_PASSWORD"], database="fandazi_groupbuy_dev", charset="utf8mb4", autocommit=True, cursorclass=pymysql.cursors.DictCursor) as connection, httpx.Client(base_url="http://127.0.0.1:8080/api", trust_env=False, timeout=120) as client:
        if phase == "snapshot":
            assert not (PUBLIC / "api-final/api-checks.json").exists(), "禁止覆盖收尾首次结果"
            verifier.PUBLIC = PUBLIC / "api-final"
            verifier.login(client, accounts["account"])
            verifier.reads(client, connection, accounts["account"])
            # 成功页依赖只应新建一单一券；与最初三单四券对比，而不是沿用开发的资源。
            orders = client.get("/group-orders").raise_for_status().json()
            vouchers = client.get("/vouchers").raise_for_status().json()
            initial = json.loads((PRIVATE / "orders.json").read_text(encoding="utf-8"))
            initial_ids = {o["orderId"] for o in initial}
            extra = [o for o in orders if o["orderId"] not in initial_ids]
            verifier.check("成功页依赖只新增本轮一单一券", len(orders) == 4 and len(vouchers) == 5 and len(extra) == 1 and extra[0]["quantity"] == 1 and extra[0]["voucherCount"] == 1 and extra[0]["paidTotalCents"] == 9647)
            verifier.save(verifier.PUBLIC / "success-dependency-order.json", extra)
        elif phase == "rotate":
            assert not (PUBLIC / "own-credentials-rotated.json").exists(), "禁止重复轮换"
            rotate(connection, client, accounts)
            scan(secret_values + [a["password"] for a in accounts.values()])
        else:
            raise ValueError("未知收尾阶段")
    print("独立收尾 " + phase + " 完成；敏感内容未输出")


if __name__ == "__main__":
    main()
