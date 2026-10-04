"""Task13 精确图片回填的失败先行测试。

五组场景分别防止名称前缀误写、缺失主键静默跳过、旧值冲突覆盖、
重复应用再次改写和事务中途失败留下半回填；也检查交易字段保持原值。
"""

from __future__ import annotations

import importlib.util
import sqlite3
import sys
from pathlib import Path


MODULE = Path(__file__).with_name("Apply-CatalogImages.py")
spec = importlib.util.spec_from_file_location("apply_catalog_images", MODULE)
assert spec is not None and spec.loader is not None
mapping = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mapping)


def database() -> sqlite3.Connection:
    """构建只含测试业务字段的内存库，保证测试不会访问集成数据库。"""
    connection = sqlite3.connect(":memory:")
    connection.execute("CREATE TABLE dishes(id INTEGER PRIMARY KEY, name TEXT, image_url TEXT, price_amount INTEGER)")
    connection.execute("CREATE TABLE deal_packages(public_id TEXT PRIMARY KEY, title TEXT, description TEXT, "
                       "active INTEGER, image_url TEXT, image_alt TEXT, price_amount INTEGER)")
    connection.executemany("INSERT INTO dishes VALUES(?,?,?,?)", [
        (1, "鸡肉饭 1", "old-a", 1880), (2, "鸡肉饭升级 1", "old-b", 1980)])
    connection.execute("INSERT INTO deal_packages VALUES(?,?,?,?,?,?,?)",
                       ("uuid-a", "双人套餐 1", "两份鸡肉饭", 1, "old-deal", "旧图", 8800))
    connection.commit()
    return connection


def dish(entity_id: int = 1, old: str = "old-a") -> dict:
    """建立固定主键与原图条件的菜品映射，测试不使用模糊名称查找。"""
    return {"entityType": "dish", "entityId": entity_id,
            "expectedName": "鸡肉饭 1", "expectedOldImageUrl": old,
            "imageUrl": "new-a", "imageAlt": "鸡肉饭"}


def deal() -> dict:
    """建立套餐映射，供交易字段和双图片字段断言使用。"""
    return {"entityType": "deal", "entityId": "uuid-a",
            "expectedName": "双人套餐 1", "expectedDescription": "两份鸡肉饭",
            "expectedOldImageUrl": "old-deal", "expectedOldImageAlt": "旧图",
            "imageUrl": "new-deal", "imageAlt": "鸡肉饭主菜"}


def exact_id() -> None:
    """相似前缀实体不能因一条映射被误更新，价格也必须保持。"""
    connection = database()
    result = mapping.apply_mapping(connection, [dish()], dry_run=False)
    rows = connection.execute("SELECT id,image_url,price_amount FROM dishes ORDER BY id").fetchall()
    assert result["changed"] == 1 and rows == [(1, "new-a", 1880), (2, "old-b", 1980)]


def missing_id() -> None:
    """映射指向不存在的主键时应拒绝整批提交。"""
    connection = database()
    try:
        mapping.apply_mapping(connection, [dish(entity_id=999)], dry_run=False)
    except ValueError:
        pass
    else:
        assert False, "不存在的实体 ID 未触发拒绝"
    assert connection.execute("SELECT image_url FROM dishes WHERE id=1").fetchone()[0] == "old-a"


def conflict() -> None:
    """旧图片与审核快照不一致时不得覆盖运营期新增的值。"""
    connection = database()
    try:
        mapping.apply_mapping(connection, [dish(old="different")], dry_run=False)
    except ValueError:
        pass
    else:
        assert False, "旧值冲突未触发拒绝"
    assert connection.execute("SELECT image_url FROM dishes WHERE id=1").fetchone()[0] == "old-a"


def idempotent() -> None:
    """重复应用同一审核清单时，第二次应报告零行变更。"""
    connection = database()
    first = mapping.apply_mapping(connection, [dish(), deal()], dry_run=False)
    second = mapping.apply_mapping(connection, [dish(), deal()], dry_run=False)
    assert first["changed"] == 2 and second["changed"] == 0
    assert connection.execute("SELECT price_amount FROM deal_packages WHERE public_id='uuid-a'").fetchone()[0] == 8800


def rollback() -> None:
    """第二条 SQL 更新被数据库拒绝时，第一条已更新的图片必须回滚。"""
    connection = database()
    connection.execute("CREATE TRIGGER reject_deal_image BEFORE UPDATE OF image_url ON deal_packages "
                       "BEGIN SELECT RAISE(FAIL, '受控注入的图片写入失败'); END")
    connection.commit()
    try:
        mapping.apply_mapping(connection, [dish(), deal()], dry_run=False)
    except sqlite3.DatabaseError:
        pass
    else:
        assert False, "第二条 SQL 失败未触发事务回滚"
    assert connection.execute("SELECT image_url FROM dishes WHERE id=1").fetchone()[0] == "old-a"
    assert connection.execute("SELECT price_amount FROM deal_packages WHERE public_id='uuid-a'").fetchone()[0] == 8800


CASES = {"exact_id": exact_id, "missing_id": missing_id, "conflict": conflict,
         "idempotent": idempotent, "rollback": rollback}


def main() -> None:
    """只执行指定场景并保留原始断言栈，便于区分业务失败和环境错误。"""
    if len(sys.argv) != 2 or sys.argv[1] not in CASES:
        raise SystemExit("需要指定一个已知测试场景")
    CASES[sys.argv[1]]()
    print(f"{sys.argv[1]} passed")


if __name__ == "__main__":
    main()
