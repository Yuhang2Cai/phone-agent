"""Task12正式设备回归控制端：真实API提供资源，私有夹具提供身份，不把业务事实写入APK。"""
import argparse
import hashlib
import json
import os
import subprocess
import uuid
from decimal import Decimal
from pathlib import Path
import httpx


def select_browse_store(client):
    """从当前公开目录选择可完整浏览的门店及套餐；无合格资源时失败，不读历史证据。"""
    # 主链会显式切换“快餐小吃”分类，因此候选也必须由相同后端筛选接口提供。
    # 合成购买门店不一定有菜单/评价，不能只取列表第一项或相信某个旧UUID仍可用。
    candidates = client.get("/api/stores", params={"category": "快餐小吃"}).raise_for_status().json()
    for candidate in candidates:
        public_id = candidate["publicId"]
        deals = client.get(f"/api/stores/{public_id}/deals").raise_for_status().json()
        if len({deal["publicId"] for deal in deals}) < 2:
            continue
        # 详情切换、菜品、评价均是独立验收步骤；数据缺失不能在Android填入样例以通过。
        if not client.get(f"/api/stores/{public_id}/dishes").raise_for_status().json():
            continue
        if not client.get(f"/api/stores/{public_id}/reviews").raise_for_status().json():
            continue
        store = client.get(f"/api/stores/{public_id}").raise_for_status().json()
        return store, deals
    raise RuntimeError("当前目录缺少两种套餐、菜品及评价齐全的浏览门店，不能使用历史文件或样例兜底")


def validate_purchase_fixture(purchase, store, deal, orders):
    """核对新账号、独占公开ID及当前资格；已有单据即拒绝，不能重购或回填库存。"""
    if (not purchase["username"].startswith("t12_") or orders or "集成验收" not in store["name"]
        or store["publicId"] != purchase["storeId"] or deal["publicId"] != purchase["dealId"]
        or deal["storePublicId"] != purchase["storeId"] or not deal["active"]
        or deal["inventory"] < purchase["quantity"] or deal["purchaseLimit"] < purchase["quantity"]):
        raise ValueError("夹具非独占、已消费或资格变化；必须新建批次，禁止重复购买")


def main():
    """安装指定新APK并顺序执行主链；任何失败立即非零退出，首次日志保留忽略目录。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--backend", default="http://127.0.0.1:18080")
    parser.add_argument("--flow", default="group-buying-99-full-regression.yaml")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if ".runtime" not in args.evidence.resolve().parts or args.evidence.exists():
        raise ValueError("设备原始证据只能保存到新的私有.runtime目录")
    args.evidence.mkdir(parents=True)
    entries = json.loads(args.fixtures.read_text(encoding="utf-8-sig"))["scenarios"]
    users = {entry["scenario"]: entry for entry in entries}
    purchase = users["purchase"]
    with httpx.Client(base_url=args.backend, timeout=120, trust_env=False) as client:
        # 浏览资源完全从本轮API确定；验收脚本与交付源不依赖工作区未提交的历史截图清单。
        store, deals = select_browse_store(client)
        selected = store["publicId"]
        drink = client.get("/api/stores", params={"category": "奶茶咖啡"}).raise_for_status().json()[0]
        hotpot = client.get("/api/stores", params={"category": "火锅"}).raise_for_status().json()[0]
        token = client.post("/api/auth/login", json={key: purchase[key] for key in ("username", "password")}).raise_for_status().json()["token"]
        headers = {"Authorization": "Bearer "+token}
        owned = client.get("/api/group-orders", headers=headers).raise_for_status().json()
        own_store = client.get("/api/stores/"+purchase["storeId"]).raise_for_status().json()
        own_deals = client.get("/api/stores/"+purchase["storeId"]+"/deals").raise_for_status().json()
        own_deal = next(d for d in own_deals if d["publicId"] == purchase["dealId"])
        validate_purchase_fixture(purchase, own_store, own_deal, owned)
        # 断言金额取本轮后端接口，而非夹具创建时SQL快照；资格变化必须重新确认。
        purchase["priceCents"] = own_deal["priceCents"]
        # 记忆通过本人公开聊天授权保存；仅允许当前新账号，不能拿原用户记忆做破坏性回归。
        saved = client.post("/api/agent/sessions/"+str(uuid.uuid4())+"/messages", headers=headers,
                            json={"text": "记住我喜欢川菜", "mode": "CONFIRM"}).raise_for_status().json()
        memories = client.get("/api/agent/memories", headers=headers).raise_for_status().json()
        if saved["status"] != "COMPLETED" or not any(m["status"] == "confirmed" and m["value"] == "川菜" for m in memories["items"]):
            raise RuntimeError("真实记忆前置未建立，不能用端上演示数据继续")
    # 所有端上资源断言使用当前公开ID与真实整数分；密码只传私有子进程，不打印命令。
    values = dict(CITY_CODE=store["cityCode"], STORE_ID=selected, STORE_NAME=store["name"],
                  DEAL_A=deals[0]["publicId"], DEAL_B=deals[1]["publicId"], PRICE_A=deals[0]["priceCents"],
                  PRICE_B=deals[1]["priceCents"], DRINK_ID=drink["publicId"], HOTPOT_ID=hotpot["publicId"],
                  MAESTRO_USERNAME=purchase["username"], MAESTRO_PASSWORD=purchase["password"],
                  REVIEW_USERNAME=users["reviewable"]["username"], REVIEW_PASSWORD=users["reviewable"]["password"],
                  EMPTY_USERNAME=users["no_orders"]["username"], EMPTY_PASSWORD=users["no_orders"]["password"],
                  # 名称来自同一当前门店详情，仅供真实搜索；点选仍由独占公开ID校验。
                  TASK6_STORE_NAME=own_store["name"], TASK6_STORE_ID=purchase["storeId"],
                  TASK6_DEAL_ID=purchase["dealId"], TASK6_QUANTITY=purchase["quantity"],
                  TASK6_TOTAL=".*"+str(Decimal(purchase["priceCents"]*purchase["quantity"])/100).replace(".", r"\.")+".*",
                  TASK6_VOUCHER_TARGET="com.fandazi.app:id/group_order_show_vouchers", MAESTRO_VOUCHER_INDEX=0,
                  MAESTRO_CITY_CODE=store["cityCode"],
                  REGRESSION_REGISTER_USERNAME="t12_ui_"+uuid.uuid4().hex, REGRESSION_REGISTER_PASSWORD=uuid.uuid4().hex,
                  # 注册昵称为本轮随机合成输入；APK只保存后端回传身份，不预置样例昵称。
                  REGRESSION_REGISTER_NICKNAME="测试食客"+uuid.uuid4().hex[:6],
                  AGENT_TEST_USERNAME=purchase["username"], AGENT_TEST_PASSWORD=purchase["password"], AGENT_TEST_QUERY="hello")
    env = os.environ.copy()
    env.update(JAVA_HOME="C:/Users/caid0/.jdks/ms-17.0.17", JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8")
    adb = "C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe"
    # 每轮先清除旧安装而非复用旧APK；服务URL由构建参数设置，非端上业务数据。
    with (args.evidence/"install.log").open("w", encoding="utf-8") as log:
        subprocess.run([adb, "-s", "emulator-5554", "uninstall", "com.fandazi.app"], stdout=log, stderr=log)
        subprocess.run([adb, "-s", "emulator-5554", "uninstall", "com.dirror.music"], stdout=log, stderr=log)
        subprocess.run([adb, "-s", "emulator-5554", "install", str(args.apk.resolve())], stdout=log, stderr=log, check=True)
    arguments = ["C:/Users/caid0/.local/maestro/bin/maestro.bat", "--device", "emulator-5554", "test",
                 "--test-output-dir", str(args.evidence), "--debug-output", str(args.evidence)]
    for key, value in values.items():
        arguments += ["-e", f"{key}={value}"]
    arguments.append(str(root/"maestro"/args.flow))
    with (args.evidence/"maestro.log").open("w", encoding="utf-8") as log:
        result = subprocess.run(arguments, env=env, cwd=args.evidence.resolve(), stdout=log, stderr=log)
    # 公开摘要不包含用户名、密码或原始事件；失败也记录真实退出码及指定APK摘要。
    (args.evidence/"result.json").write_text(json.dumps({"flow": args.flow, "exitCode": result.returncode,
        "apkSha256": hashlib.sha256(args.apk.read_bytes()).hexdigest()}, indent=2), encoding="utf-8")
    print(f"Maestro flow={args.flow} exit={result.returncode}；原始凭据日志仅保留私有目录")
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
