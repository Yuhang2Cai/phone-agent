"""按已审核的实体身份把目录照片回填到隔离业务库。

此模块提供可被测试复用的事务映射函数和安全命令行入口。数据库目标固定为
本机 13307 的独立集成库；默认只预览差异，不连接默认 3306 或线上环境。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sqlite3
from pathlib import Path
from typing import Any


def _sqlite(connection: Any) -> bool:
    """识别仅在内存单测中使用的 SQLite，其占位符及行锁语法与 MySQL 不同。"""
    return isinstance(connection, sqlite3.Connection)


def _row(connection: Any, entry: dict) -> tuple:
    """按不可变主键读取当前图片与身份；MySQL 写事务中锁定目标行。"""
    placeholder = "?" if _sqlite(connection) else "%s"
    lock = "" if _sqlite(connection) else " FOR UPDATE"
    cursor = connection.cursor()
    if entry["entityType"] == "dish":
        cursor.execute(f"SELECT id,name,image_url FROM dishes WHERE id={placeholder}{lock}",
                       (entry["entityId"],))
    elif entry["entityType"] == "deal":
        if entry.get("storePublicId"):
            cursor.execute("SELECT d.public_id,d.title,d.description,d.active,d.image_url,d.image_alt,"
                           "s.public_id FROM deal_packages d JOIN stores s ON s.id=d.store_id "
                           f"WHERE d.public_id={placeholder}{lock}", (entry["entityId"],))
        else:
            cursor.execute("SELECT public_id,title,description,active,image_url,image_alt "
                           f"FROM deal_packages WHERE public_id={placeholder}{lock}",
                           (entry["entityId"],))
    else:
        raise ValueError("清单包含未知实体类型")
    return cursor.fetchone()


def _current(connection: Any, entry: dict) -> tuple[str | None, str | None]:
    """核对实体身份、活动状态及不可改描述，再返回当前图片字段。"""
    row = _row(connection, entry)
    if row is None:
        raise ValueError(f"映射实体不存在：{entry['entityType']} {entry['entityId']}")
    if entry["entityType"] == "dish":
        if str(row[0]) != str(entry["entityId"]) or row[1] != entry["expectedName"]:
            raise ValueError(f"菜品身份不符：{entry['entityId']}")
        return row[2], None
    if (row[0] != entry["entityId"] or row[1] != entry["expectedName"]
            or row[2] != entry["expectedDescription"] or not bool(row[3])):
        raise ValueError(f"套餐身份、描述或活动状态不符：{entry['entityId']}")
    if entry.get("storePublicId") and row[6] != entry["storePublicId"]:
        raise ValueError(f"套餐所属门店不符：{entry['entityId']}")
    return row[4], row[5]


def snapshot_mapping(connection: Any, entries: list[dict]) -> list[dict]:
    """只读生成当前图片备份；应用时仍会重新核对，避免备份后发生并发覆盖。"""
    snapshot = []
    for entry in entries:
        image_url, image_alt = _current(connection, entry)
        snapshot.append({"entityType": entry["entityType"], "entityId": entry["entityId"],
                         "imageUrl": image_url, "imageAlt": image_alt})
    return snapshot


def apply_mapping(connection, entries: list[dict], dry_run: bool = True) -> dict:
    """比较审核旧值并事务更新精确实体；失败回滚，返回实际行数与差异。"""
    identities = [(item["entityType"], str(item["entityId"])) for item in entries]
    if len(set(identities)) != len(identities):
        raise ValueError("审核清单包含重复实体身份")
    if _sqlite(connection):
        connection.execute("BEGIN")
    else:
        connection.begin()
    try:
        pending = []
        for entry in entries:
            current_url, current_alt = _current(connection, entry)
            old_url = entry.get("expectedOldImageUrl")
            old_alt = entry.get("expectedOldImageAlt") if entry["entityType"] == "deal" else None
            target_url = entry["imageUrl"]
            target_alt = entry.get("imageAlt") if entry["entityType"] == "deal" else None
            current = (current_url, current_alt)
            old = (old_url, old_alt)
            target = (target_url, target_alt)
            # 已达目标视为幂等；其余只允许从审核快照原值前进，不能覆盖运营期新图。
            if current == target:
                continue
            if current != old:
                raise ValueError(f"图片旧值冲突：{entry['entityType']} {entry['entityId']}")
            pending.append((entry, current, target))
        differences = [{"entityType": entry["entityType"], "entityId": entry["entityId"],
                        "before": list(before), "after": list(after)}
                       for entry, before, after in pending]
        if dry_run:
            connection.rollback()
            return {"changed": 0, "planned": len(pending), "differences": differences}
        placeholder = "?" if _sqlite(connection) else "%s"
        changed = 0
        for entry, _, after in pending:
            cursor = connection.cursor()
            if entry["entityType"] == "dish":
                cursor.execute(f"UPDATE dishes SET image_url={placeholder} WHERE id={placeholder}",
                               (after[0], entry["entityId"]))
            else:
                cursor.execute("UPDATE deal_packages SET "
                               f"image_url={placeholder},image_alt={placeholder} WHERE public_id={placeholder}",
                               (after[0], after[1], entry["entityId"]))
            if cursor.rowcount != 1:
                raise RuntimeError(f"图片写入行数异常：{entry['entityType']} {entry['entityId']}")
            changed += cursor.rowcount
        connection.commit()
        return {"changed": changed, "planned": len(pending), "differences": differences}
    except BaseException:
        # 业务冲突、SQL 错误和受控中断一律撤销整个批次，不能留下半回填。
        connection.rollback()
        raise


def _manifest(path: Path) -> tuple[dict, str]:
    """验证清单结构、来源授权和目标路径后才允许连接数据库。"""
    data = path.read_bytes()
    sha = hashlib.sha256(data).hexdigest().upper()
    manifest = json.loads(data)
    if manifest.get("schemaVersion") != 1 or len(manifest.get("images", [])) != 15:
        raise ValueError("审核清单版本或来源数量不符合当前任务")
    images = {image["imageId"]: image for image in manifest["images"]}
    if len(images) != 15 or any(not item.get("sourceSha256") or not item.get("licenseName")
                                or item.get("contentReviewed") is not True for item in images.values()):
        raise ValueError("照片来源未完整审核")
    entities = manifest.get("entities", [])
    if len(entities) != 249:
        raise ValueError("隔离目录实体数量改变，须重新审核")
    ids = set()
    for entry in entities:
        identity = (entry.get("entityType"), str(entry.get("entityId")))
        if identity in ids or identity[0] not in {"dish", "deal"}:
            raise ValueError("重复或无效的实体身份")
        ids.add(identity)
        image = images.get(entry.get("imageId"))
        if image is None or entry.get("imageUrl") != image.get("url"):
            raise ValueError("实体图片未指向审核来源")
    return manifest, sha


def _private_path(path: Path) -> Path:
    """限制备份与差异输出在忽略的私有目录内，防止误提交当前图片快照。"""
    resolved = path.resolve()
    if ".runtime" not in resolved.parts:
        raise ValueError("备份和输出必须位于 .runtime 私有目录")
    resolved.parent.mkdir(parents=True, exist_ok=True)
    return resolved


def _write_json(path: Path, value: dict, exclusive: bool = False) -> None:
    """先完整构造 JSON 再写出；备份使用独占创建，避免覆盖可恢复的原值。"""
    with path.open("x" if exclusive else "w", encoding="utf-8") as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write("\n")


def main() -> int:
    """固定集成目标，按 dry-run、应用或图片恢复模式执行并保存安全结果。"""
    parser = argparse.ArgumentParser(description="按审核清单回填隔离目录餐图")
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--backup", type=Path)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true")
    mode.add_argument("--apply", action="store_true")
    mode.add_argument("--restore", action="store_true")
    args = parser.parse_args()
    manifest, manifest_sha = _manifest(args.manifest)
    output_path = _private_path(args.output)
    if (args.apply or args.restore) and args.backup is None:
        parser.error("应用和恢复必须提供私有备份路径")
    backup_path = _private_path(args.backup) if args.backup else None
    password = os.environ.get("INTEGRATION_DB_PASSWORD", "")
    if not password:
        parser.error("缺少隔离数据库口令环境变量")
    import pymysql

    # 数据库目标固定，不能由命令参数意外切换到默认 3306、线上库或其他业务库。
    connection = pymysql.connect(host="127.0.0.1", port=13307, user="fandazi",
                                 password=password, database="fandazi_groupbuy_integration",
                                 charset="utf8mb4", autocommit=False)
    try:
        entries = manifest["entities"]
        if args.restore:
            backup = json.loads(backup_path.read_text(encoding="utf-8"))
            if backup.get("manifestSha256") != manifest_sha or len(backup.get("images", [])) != len(entries):
                raise ValueError("备份与当前审核清单不一致")
            by_id = {(item["entityType"], str(item["entityId"])): item for item in backup["images"]}
            if len(by_id) != len(entries):
                raise ValueError("备份中有重复实体")
            reverse = []
            for entry in entries:
                saved = by_id.get((entry["entityType"], str(entry["entityId"])))
                if saved is None:
                    raise ValueError("备份缺少当前清单实体")
                # 恢复目标只取备份里的图片旧值，允许重复恢复但拒绝在此期间被其他人改过的图片。
                reverse.append(entry | {"expectedOldImageUrl": entry["imageUrl"],
                                        "expectedOldImageAlt": entry.get("imageAlt"),
                                        "imageUrl": saved["imageUrl"], "imageAlt": saved["imageAlt"]})
            result = apply_mapping(connection, reverse, dry_run=False)
            action = "restore"
        elif args.apply:
            # 备份在首个写事务前落盘；事务开始后再次核对旧值，防止备份窗口内的竞态覆盖。
            before = snapshot_mapping(connection, entries)
            connection.rollback()
            _write_json(backup_path, {"manifestSha256": manifest_sha, "images": before}, exclusive=True)
            result = apply_mapping(connection, entries, dry_run=False)
            action = "apply"
        else:
            result = apply_mapping(connection, entries, dry_run=True)
            action = "dry-run"
        summary = {"action": action, "database": "127.0.0.1:13307/fandazi_groupbuy_integration",
                   "manifestSha256": manifest_sha, **result}
        _write_json(output_path, summary)
        print(json.dumps({"action": action, "changed": result["changed"],
                          "planned": result["planned"]}, ensure_ascii=False))
        return 0
    finally:
        connection.close()


if __name__ == "__main__":
    raise SystemExit(main())
