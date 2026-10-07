"""隔离券夹具的离线安全测试：避免重复编号、越权行、共享定义或事实变化进入清理。

本模块仅构造内存数据，不读取凭据，不连接服务或数据库，不执行实际删除。
"""

import copy
import uuid
from datetime import date

import pytest
from sqlalchemy import (Boolean, Column, Date, DateTime, ForeignKey, Integer, MetaData,
                        Numeric, String, Table, create_engine, func, insert, select)

import cleanup_coupon_ui_fixture as cleanup
from create_coupon_ui_fixture import validate_records, validate_snapshot
from test_live_coupon_projection import CASES


@pytest.fixture
def sample():
    """构造八组随机编号的内存清单与快照；只提供校验输入，不代表真实业务账号。"""
    manifest = dict(schemaVersion=1, purpose="task8-coupon-ui-independent", fixtureId=str(uuid.uuid4()),
                    database="fandazi_groupbuy_dev", baseUrl="http://127.0.0.1:8080",
                    owner=dict(username="coupon8ui_" + uuid.uuid4().hex[:16], userId=11),
                    observer=dict(username="coupon8probe_" + uuid.uuid4().hex[:16], userId=12), records=[])
    definitions, claims = [], []
    # 每组独立的券定义与领取行一一对应；预期投影仍取自原八场景，不改原测试断言。
    for index, (scenario, _, _, active, persisted, expected) in enumerate(CASES, 1):
        record = dict(scenario=scenario, couponId=str(uuid.uuid4()), claimId=str(uuid.uuid4()),
                      persisted=persisted, expected=expected, active=active, validFrom="2026-10-02",
                      validTo="2026-10-03", thresholdCents=5000, discountCents=500)
        manifest["records"].append(record)
        definitions.append(dict(id=index, public_id=record["couponId"],
                                name="独立界面验收券 " + scenario,
                                scope_description="独立券界面夹具 " + manifest["fixtureId"][:8],
                                threshold_amount="50.00", discount_amount="5.00", active=active,
                                valid_from=record["validFrom"], valid_to=record["validTo"]))
        claims.append(dict(public_id=record["claimId"], user_id=11, coupon_id=index,
                           status=persisted, order_id=None, used_at=None))
    return manifest, claims, definitions


def test_exact_owned_snapshot_is_accepted(sample):
    """完整八组且均为本人独占的快照必须通过，为保留和精确清理提供正向基线。"""
    manifest, claims, definitions = sample
    assert validate_records(manifest) is None
    assert validate_snapshot(manifest, claims, definitions) is None


def test_duplicate_random_id_is_rejected(sample):
    """清单重复随机编号必须拒绝，防止 IN 条件缩小后仍被误判为八行。"""
    manifest = copy.deepcopy(sample[0])
    manifest["records"][1]["claimId"] = manifest["records"][0]["claimId"]
    with pytest.raises(ValueError):
        validate_records(manifest)


def test_foreign_owner_is_rejected(sample):
    """任何领取行不属于清单 owner 都必须拒绝，不能只凭公开编号删除他人行。"""
    manifest, claims, definitions = sample
    claims[0]["user_id"] = 99
    with pytest.raises(ValueError):
        validate_snapshot(manifest, claims, definitions)


def test_shared_definition_is_rejected(sample):
    """定义被额外领取引用时必须拒绝，避免清理本人的券定义波及其他账号。"""
    manifest, claims, definitions = sample
    claims.append(dict(claims[0], public_id=str(uuid.uuid4()), user_id=99))
    with pytest.raises(ValueError):
        validate_snapshot(manifest, claims, definitions)


def test_changed_persisted_status_is_rejected(sample):
    """Android 只读流程若意外改变持久化状态，清理前必须停下以保留失败事实。"""
    manifest, claims, definitions = sample
    claims[0]["status"] = "USED"
    with pytest.raises(ValueError):
        validate_snapshot(manifest, claims, definitions)


def test_wrong_coupon_reference_is_rejected(sample):
    """公开编号与内部外键必须同时匹配，防止清单或数据库关联被替换后误清理。"""
    manifest, claims, definitions = sample
    claims[0]["coupon_id"] = definitions[1]["id"]
    with pytest.raises(ValueError):
        validate_snapshot(manifest, claims, definitions)


def test_changed_amount_is_rejected(sample):
    """金额事实被修改时必须拒绝，确保清理与留存证据对应本轮实际插入的数据。"""
    manifest, claims, definitions = sample
    definitions[0]["discount_amount"] = "9.00"
    with pytest.raises(ValueError):
        validate_snapshot(manifest, claims, definitions)


@pytest.fixture
def memory_database(sample, monkeypatch):
    """在内存 SQLite 中装配同形表，验证真实删除 SQL；绝不调用开发 MySQL 或实际恢复清单。"""
    manifest, claim_rows, definition_rows = sample
    engine = create_engine("sqlite:///:memory:")
    metadata = MetaData()
    users = Table("app_users", metadata, Column("id", Integer, primary_key=True), Column("username", String))
    definitions = Table("coupons", metadata, Column("id", Integer, primary_key=True),
        Column("public_id", String, unique=True), Column("name", String),
        Column("threshold_amount", Numeric(10, 2)), Column("discount_amount", Numeric(10, 2)),
        Column("valid_from", Date), Column("valid_to", Date), Column("scope_description", String),
        Column("active", Boolean))
    claims = Table("user_coupons", metadata, Column("id", Integer, primary_key=True),
        Column("public_id", String, unique=True), Column("user_id", Integer, ForeignKey("app_users.id")),
        Column("coupon_id", Integer, ForeignKey("coupons.id")), Column("status", String),
        Column("order_id", Integer), Column("used_at", DateTime))
    metadata.create_all(engine)
    with engine.begin() as connection:
        # 启用外键约束，清理顺序必须为领取行后定义；账号只读且最后仍应存在。
        connection.exec_driver_sql("PRAGMA foreign_keys=ON")
        connection.execute(insert(users), [dict(id=account["userId"], username=account["username"])
                                           for account in (manifest["owner"], manifest["observer"])])
        connection.execute(insert(definitions), [dict(row, valid_from=date.fromisoformat(row["valid_from"]),
            valid_to=date.fromisoformat(row["valid_to"])) for row in definition_rows])
        connection.execute(insert(claims), claim_rows)
    manifest["state"], manifest["protectedFiles"] = "VERIFIED", {}
    saved = []
    # 仅替换文件边界；数据库选择、归属校验、删除语句和事务均使用实际脚本逻辑。
    monkeypatch.setattr(cleanup, "protected_hashes", lambda: {})
    monkeypatch.setattr(cleanup, "save_manifest", lambda value: saved.append(copy.deepcopy(value)))
    try:
        yield manifest, engine, {table.name: table for table in (users, definitions, claims)}, saved
    finally:
        engine.dispose()


def table_counts(engine, tables):
    """只读取三张内存表的行数，避免测试输出任何随机账号或完整编号。"""
    with engine.connect() as connection:
        return {name: connection.execute(select(func.count()).select_from(table)).scalar_one()
                for name, table in tables.items()}


def test_cleanup_dry_run_keeps_all_rows(memory_database):
    """默认预检不得删除、更新清单或改变账号，防止准备阶段意外消费 Android 夹具。"""
    manifest, engine, tables, saved = memory_database
    cleanup.cleanup_fixture(manifest, engine, tables)
    assert table_counts(engine, tables) == {"app_users": 2, "coupons": 8, "user_coupons": 8}
    assert manifest["state"] == "VERIFIED" and saved == []


def test_cleanup_executes_exact_rows_and_keeps_accounts(memory_database):
    """在内存库确认精准删除八组，账号与审计保留策略不得退化为清库或删用户。"""
    manifest, engine, tables, saved = memory_database
    cleanup.cleanup_fixture(manifest, engine, tables, execute=True)
    assert table_counts(engine, tables) == {"app_users": 2, "coupons": 0, "user_coupons": 0}
    assert manifest["state"] == "CLEANED" and len(saved) == 1


def test_cleanup_shared_reference_refuses_before_deletion(memory_database):
    """额外账号引用测试定义时实际清理必须拒绝，事务中两类券都应原样保留。"""
    manifest, engine, tables, saved = memory_database
    with engine.begin() as connection:
        connection.execute(insert(tables["user_coupons"]).values(public_id=str(uuid.uuid4()),
            user_id=manifest["observer"]["userId"], coupon_id=1, status="AVAILABLE"))
    with pytest.raises(ValueError):
        cleanup.cleanup_fixture(manifest, engine, tables, execute=True)
    assert table_counts(engine, tables) == {"app_users": 2, "coupons": 8, "user_coupons": 9}
    assert manifest["state"] == "VERIFIED" and saved == []


@pytest.mark.parametrize("switch", ["--execute", "--android-acceptance-complete"])
def test_cleanup_requires_both_confirmations_before_reading_manifest(monkeypatch, switch):
    """只传一个确认开关必须在读取真实文件或连接数据库前拒绝，保护仍在使用的夹具。"""
    monkeypatch.setattr("sys.argv", ["cleanup_coupon_ui_fixture.py", switch])
    assert cleanup.main() == 1
