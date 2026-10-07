"""Task8 Android 券夹具准备入口；只维护独占随机账号和忽略目录中的恢复清单。

本模块不修改产品源码，不调用领取、抵扣或核销接口；合成券保留到独立 Android 验收结束。
首次运行注册两个随机账号，仅其中一个持有八类券；再次核验使用 --verify-only，不重复创建。
凭据、完整编号、逐券预期和核验详情仅写固定的忽略清单，异常只输出类型，不暴露请求或 SQL。
"""

import argparse
import hashlib
import json
import os
import subprocess
import uuid
from datetime import date, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo

import httpx
from sqlalchemy import MetaData, Table, URL, create_engine, insert, or_, select, text

from test_live_coupon_projection import CASES


# 固定路径与数据库限制避免错误地在其他项目、正式库或可提交目录创建测试账号。
ROOT = Path("C:/fdz-build")
MANIFEST = ROOT / ".runtime/task8-coupon-ui-fixture.json"
DATABASE = "fandazi_groupbuy_dev"
BASE_URL = "http://127.0.0.1:8080"
PURPOSE = "task8-coupon-ui-independent"
SHANGHAI = ZoneInfo("Asia/Shanghai")
PROTECTED_FILES = (".runtime/task2-fixtures.json", ".runtime/task8-review-retry-fixtures.json")


def require(condition, code):
    """条件不成立时只抛安全原因码；禁止把凭据、SQL 参数或完整业务响应拼入异常。"""
    if not condition:
        raise ValueError(code)


def validate_records(manifest):
    """校验清单的用途、账号与八组随机编号；非法范围必须拒绝，不能进入数据库操作。"""
    # 输入校验先锁定唯一验收用途及数据库，不能用本脚本加载其他夹具实施删除。
    require(manifest["schemaVersion"] == 1 and manifest["purpose"] == PURPOSE, "wrong_manifest_purpose")
    require(manifest["database"] == DATABASE and manifest["baseUrl"] == BASE_URL, "wrong_environment")
    fixture_id = uuid.UUID(manifest["fixtureId"])
    require(fixture_id.version == 4 and str(fixture_id) == manifest["fixtureId"], "invalid_fixture_id")
    owner, observer = manifest["owner"], manifest["observer"]
    require(owner["username"].startswith("coupon8ui_") and observer["username"].startswith("coupon8probe_"),
            "wrong_account_namespace")
    require(type(owner["userId"]) is int and type(observer["userId"]) is int
            and owner["userId"] > 0 and observer["userId"] > 0 and owner["userId"] != observer["userId"],
            "invalid_account_scope")
    records = manifest["records"]
    require(len(records) == len(CASES), "wrong_record_count")
    expected_cases = {case[0]: case for case in CASES}
    require({row["scenario"] for row in records} == set(expected_cases), "wrong_scenarios")
    # 规范化编号只用于验证，不修改清单；重复、非随机 UUID 或未知状态都应停在数据库访问之前。
    identifiers = [row[key] for row in records for key in ("couponId", "claimId")]
    require(len(set(identifiers)) == len(CASES) * 2, "duplicate_resource_id")
    for value in identifiers:
        parsed = uuid.UUID(value)
        require(parsed.version == 4 and str(parsed) == value, "invalid_resource_id")
    for row in records:
        case = expected_cases[row["scenario"]]
        require((row["active"], row["persisted"], row["expected"]) == case[3:], "changed_case_contract")
        require(row["thresholdCents"] == 5000 and row["discountCents"] == 500, "changed_amount_contract")
        require(date.fromisoformat(row["validFrom"]) <= date.fromisoformat(row["validTo"]), "invalid_date_range")


def validate_snapshot(manifest, claim_rows, definition_rows):
    """校验数据库快照的精确归属与一对一关联；共享券或变化后的事实不得被清理。"""
    validate_records(manifest)
    records = manifest["records"]
    # 范围先匹配完整公开编号集合；额外引用行也被读入，任何共享或丢失都不能进入清理。
    require(len(claim_rows) == len(records) and len(definition_rows) == len(records), "changed_row_count")
    claims = {row["public_id"]: row for row in claim_rows}
    definitions = {row["public_id"]: row for row in definition_rows}
    require(set(claims) == {row["claimId"] for row in records}, "changed_claim_scope")
    require(set(definitions) == {row["couponId"] for row in records}, "changed_definition_scope")
    for record in records:
        claim, definition = claims[record["claimId"]], definitions[record["couponId"]]
        # 归属和关联同时检查；只有随机 ID 与本人 owner 都匹配才视为本测试独占行。
        require(claim["user_id"] == manifest["owner"]["userId"], "foreign_owner")
        require(claim["coupon_id"] == definition["id"], "changed_coupon_reference")
        require(claim["status"] == record["persisted"] and claim["order_id"] is None
                and claim["used_at"] is None, "changed_claim_fact")
        require(definition["name"] == "独立界面验收券 " + record["scenario"]
                and definition["scope_description"] == "独立券界面夹具 " + manifest["fixtureId"][:8],
                "changed_definition_marker")
        require(Decimal(str(definition["threshold_amount"])) * 100 == record["thresholdCents"]
                and Decimal(str(definition["discount_amount"])) * 100 == record["discountCents"], "changed_amount")
        require(str(definition["valid_from"]) == record["validFrom"]
                and str(definition["valid_to"]) == record["validTo"]
                and bool(definition["active"]) == record["active"], "changed_definition_fact")


def protected_hashes():
    """只对两份已有七账号清单计算字节摘要；不解析其中任何用户数据，也不改写文件。"""
    return {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in PROTECTED_FILES}


def check_manifest_location():
    """确认本脚本来自指定 worktree 且凭据路径被 Git 忽略；不满足时在注册前停止。"""
    require(Path(__file__).resolve().parents[3] == ROOT.resolve(), "wrong_script_root")
    require(MANIFEST.parent.is_dir(), "runtime_missing")
    result = subprocess.run(["git", "check-ignore", "--quiet", ".runtime/task8-coupon-ui-fixture.json"],
                            cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    require(result.returncode == 0, "manifest_not_ignored")


def save_manifest(manifest, first=False):
    """将恢复信息原子写入唯一忽略清单；首写独占创建，更新用同目录临时文件替换。"""
    # 首写在任何注册之前完成；失败恢复时已有随机编号可定位，不自动删除验收证据。
    if first:
        with MANIFEST.open("x", encoding="utf-8") as stream:
            json.dump(manifest, stream, ensure_ascii=False, indent=2)
        return
    temporary = MANIFEST.with_suffix(".json.tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(manifest, stream, ensure_ascii=False, indent=2)
    temporary.replace(MANIFEST)


def load_manifest():
    """读取固定忽略清单并校验删除范围；不返回或打印 JWT，清单中也不保存 JWT。"""
    check_manifest_location()
    with MANIFEST.open(encoding="utf-8") as stream:
        manifest = json.load(stream)
    validate_records(manifest)
    return manifest


def open_database():
    """从共享环境建立开发库连接并反射三张表；数据库名和回环地址不符时拒绝操作。"""
    parsed = urlsplit(os.environ["DB_URL"].removeprefix("jdbc:"))
    require(parsed.hostname in ("127.0.0.1", "localhost") and parsed.path == "/" + DATABASE,
            "unsafe_database_target")
    engine = create_engine(URL.create("mysql+pymysql", username=os.environ["DB_USERNAME"],
        password=os.environ["DB_PASSWORD"], host=parsed.hostname, port=parsed.port or 3306,
        database=DATABASE, query={"charset": "utf8mb4"}), pool_pre_ping=True)
    try:
        with engine.connect() as connection:
            require(connection.execute(text("select database()")).scalar_one() == DATABASE, "wrong_database")
        metadata = MetaData()
        tables = {name: Table(name, metadata, autoload_with=engine)
                  for name in ("app_users", "coupons", "user_coupons")}
        return engine, tables
    except Exception:
        engine.dispose()
        raise


def read_snapshot(connection, tables, manifest, lock=False):
    """读取本人行、指定公开编号以及任何共享引用；可在清理事务中加行锁，返回精确快照。"""
    users, definitions, claims = (tables[key] for key in ("app_users", "coupons", "user_coupons"))
    # 账号核对是只读，不更新账号；注册身份与数据库 owner 不一致就停止。
    for account in (manifest["owner"], manifest["observer"]):
        username = connection.execute(select(users.c.username).where(users.c.id == account["userId"])).scalar_one()
        require(username == account["username"], "account_identity_changed")
    definition_query = select(definitions).where(definitions.c.public_id.in_(
        [row["couponId"] for row in manifest["records"]]))
    if lock:
        definition_query = definition_query.with_for_update()
    definition_rows = list(connection.execute(definition_query).mappings())
    # 不只查 owner：把同定义下的他人领取也读出，避免删除共享定义；本人额外券同样触发拒绝。
    claim_query = select(claims).where(or_(claims.c.user_id == manifest["owner"]["userId"],
        claims.c.public_id.in_([row["claimId"] for row in manifest["records"]]),
        claims.c.coupon_id.in_([row["id"] for row in definition_rows])))
    if lock:
        claim_query = claim_query.with_for_update()
    claim_rows = list(connection.execute(claim_query).mappings())
    validate_snapshot(manifest, claim_rows, definition_rows)
    return claim_rows, definition_rows


def new_manifest():
    """生成两组登录凭据与八组独立随机编号；逐券预期只保存在忽略清单中供后续 UI 使用。"""
    today = datetime.now(SHANGHAI).date()
    manifest = dict(schemaVersion=1, purpose=PURPOSE, fixtureId=str(uuid.uuid4()), state="PREPARED",
                    database=DATABASE, baseUrl=BASE_URL, businessDate=today.isoformat(),
                    createdAt=datetime.now(SHANGHAI).isoformat(), protectedFiles=protected_hashes(), records=[])
    for role, prefix in (("owner", "coupon8ui_"), ("observer", "coupon8probe_")):
        manifest[role] = dict(username=prefix + uuid.uuid4().hex[:16], password=uuid.uuid4().hex, userId=None)
    # 复用原八场景，不修改已有断言；日期采用同一个上海业务日，首末日边界不会因插入顺序漂移。
    for scenario, start, end, active, persisted, expected in CASES:
        manifest["records"].append(dict(scenario=scenario, couponId=str(uuid.uuid4()), claimId=str(uuid.uuid4()),
            persisted=persisted, expected=expected, active=active,
            validFrom=(today + timedelta(days=start)).isoformat(), validTo=(today + timedelta(days=end)).isoformat(),
            thresholdCents=5000, discountCents=500))
    return manifest


def login(client, account):
    """用清单凭据走真实登录并读取 /users/me；返回内存中的认证头，绝不保存或输出 token。"""
    response = client.post("/api/auth/login", json={key: account[key] for key in ("username", "password")})
    require(response.status_code == 200, "login_failed")
    body = response.json()
    require(body["user"]["id"] == account["userId"]
            and body["user"]["username"] == account["username"], "login_identity_mismatch")
    headers = {"Authorization": "Bearer " + body["token"]}
    me = client.get("/api/users/me", headers=headers)
    require(me.status_code == 200 and me.json()["id"] == account["userId"], "authenticated_owner_mismatch")
    return headers


def create_fixture(client, engine, tables):
    """首次注册独占账号并在事务中插入八组券；不覆盖清单，不调用任何领取或使用入口。"""
    require(not MANIFEST.exists(), "fixture_already_exists_use_verify_only")
    manifest = new_manifest()
    save_manifest(manifest, first=True)
    for role in ("owner", "observer"):
        account = manifest[role]
        response = client.post("/api/auth/register", json={key: account[key] for key in ("username", "password")})
        require(response.status_code == 201, "random_account_registration_failed")
        body = response.json()
        require(body["user"]["username"] == account["username"], "registered_identity_mismatch")
        account["userId"] = body["user"]["id"]
        save_manifest(manifest)
        headers = login(client, account)
        empty = client.get("/api/coupons", headers=headers)
        require(empty.status_code == 200 and empty.json() == [], "fresh_account_not_empty")
    manifest["state"] = "ACCOUNTS_READY"
    validate_records(manifest)
    save_manifest(manifest)
    definitions, claims = tables["coupons"], tables["user_coupons"]
    # 测试数据直接写入独占合成定义与领取表；这是隔离夹具装配，不是开启新的业务领取入口。
    with engine.begin() as connection:
        for record in manifest["records"]:
            result = connection.execute(insert(definitions).values(public_id=record["couponId"],
                name="独立界面验收券 " + record["scenario"],
                threshold_amount=Decimal(record["thresholdCents"]) / 100,
                discount_amount=Decimal(record["discountCents"]) / 100,
                valid_from=date.fromisoformat(record["validFrom"]), valid_to=date.fromisoformat(record["validTo"]),
                scope_description="独立券界面夹具 " + manifest["fixtureId"][:8], active=record["active"]))
            connection.execute(insert(claims).values(public_id=record["claimId"],
                user_id=manifest["owner"]["userId"], coupon_id=result.inserted_primary_key[0],
                status=record["persisted"], claimed_at=datetime.now(SHANGHAI).replace(tzinfo=None)))
    manifest["state"] = "INSERTED"
    save_manifest(manifest)
    return manifest


def verify_fixture(client, engine, tables, manifest):
    """真实核对八券状态、金额、日期、归属和持久化只读性；成功后保留合成行供 Android 登录。"""
    validate_records(manifest)
    require(manifest["state"] != "CLEANED", "fixture_already_cleaned")
    require(datetime.now(SHANGHAI).date().isoformat() == manifest["businessDate"], "business_date_changed")
    owner_headers = login(client, manifest["owner"])
    response = client.get("/api/coupons", headers=owner_headers)
    require(response.status_code == 200, "coupon_query_failed")
    rows = response.json()
    require(len(rows) == len(CASES), "api_coupon_count_mismatch")
    indexed = {row["couponId"]: row for row in rows}
    require(set(indexed) == {record["couponId"] for record in manifest["records"]}, "api_coupon_scope_mismatch")
    for record in manifest["records"]:
        row = indexed[record["couponId"]]
        # 每组严格检查既有文本投影、两个金额和原始领取编号；不得用前端猜测替代真实输出。
        require(row["status"] == record["expected"], "api_status_mismatch")
        require(row["thresholdCents"] == record["thresholdCents"]
                and row["discountCents"] == record["discountCents"], "api_amount_mismatch")
        require(row["claimId"] == record["claimId"], "api_claim_mismatch")
        require(row["validFrom"] == record["validFrom"] and row["validTo"] == record["validTo"],
                "api_date_mismatch")
    observer_headers = login(client, manifest["observer"])
    other = client.get("/api/coupons", headers=observer_headers, params={"userId": manifest["owner"]["userId"]})
    require(other.status_code == 200 and other.json() == [], "foreign_account_can_read_owner")
    require(client.get("/api/coupons").status_code == 401, "anonymous_can_read_coupons")
    # 在所有真实 GET 之后核对 DB：投影不能将 AVAILABLE 回写为其他状态，也不能改变其他业务事实。
    with engine.connect() as connection:
        read_snapshot(connection, tables, manifest)
    require(protected_hashes() == manifest["protectedFiles"], "protected_fixture_changed")
    manifest["state"] = "VERIFIED"
    manifest["verification"] = dict(checkedAt=datetime.now(SHANGHAI).isoformat(), statusChecks=8,
        amountChecks=8, ownershipChecks=8, dateChecks=8, loginChecks=2, negativeOwnershipChecks=2,
        persistedClaimsUnchanged=8, protectedFilesUnchanged=2, retainedDefinitions=8, retainedClaims=8)
    save_manifest(manifest)
    print("COUPON_UI_FIXTURE login=2 status=8 amount=8 ownership=8 dates=8 negative=2 retained_definitions=8 retained_claims=8 protected_files_unchanged=2")


def main():
    """执行首次创建或只读复核；错误仅打印类型，既有夹具和合成失败证据均不自动清理。"""
    parser = argparse.ArgumentParser(description="准备或核验仅本测试独占的 Android 优惠券夹具")
    parser.add_argument("--verify-only", action="store_true", help="只复核固定清单，不注册账号或插入数据")
    arguments = parser.parse_args()
    engine = None
    try:
        check_manifest_location()
        # 同一天重复运行必须显式选择复核，避免覆盖 Android 正在消费的账号与随机编号。
        require(arguments.verify_only or not MANIFEST.exists(), "fixture_already_exists_use_verify_only")
        engine, tables = open_database()
        with httpx.Client(base_url=BASE_URL, timeout=30, trust_env=False) as client:
            manifest = load_manifest() if arguments.verify_only else create_fixture(client, engine, tables)
            verify_fixture(client, engine, tables, manifest)
        return 0
    except Exception as exception:
        print("COUPON_UI_FIXTURE_FAILED type=" + type(exception).__name__ + " details_not_printed=true")
        return 1
    finally:
        if engine is not None:
            engine.dispose()


if __name__ == "__main__":
    raise SystemExit(main())
