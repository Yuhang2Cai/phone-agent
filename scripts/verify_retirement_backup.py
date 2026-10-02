"""Task11数据库安全检查：备份真实两库、恢复到随机隔离库并核对，不删除或恢复生产数据。

凭据只从本机Docker环境读取。SQL与逐表摘要只写入忽略的.runtime，公开输出仅包含
数据库名称、表数、行数和一致性结果。恢复库保留供独立验收，不自动DROP。
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import uuid
import pymysql


DOCKER = os.getenv("DOCKER_EXECUTABLE", "C:/Program Files/Docker/Docker/resources/bin/docker.exe")
CONTAINER = os.getenv("FANDAZI_MYSQL_CONTAINER", "mysql-server")


def database_snapshot(connection, database):
    """计算完整表行摘要与迁移历史；稳定排序防止无主键表返回顺序影响比较。"""
    if not re.fullmatch(r"[a-zA-Z0-9_]+", database):
        raise ValueError("非法数据库名称")
    result = {}
    with connection.cursor() as cursor:
        cursor.execute("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s AND TABLE_TYPE='BASE TABLE'", (database,))
        names = [row[0] for row in cursor.fetchall()]
        for name in names:
            # 表名来自服务器，但仍转义标识符；不把任何用户值拼进SQL。
            table = name.replace("`", "``")
            cursor.execute(f"SELECT * FROM `{database}`.`{table}`")
            rows = sorted(json.dumps(row, default=str, ensure_ascii=False, separators=(",", ":")) for row in cursor.fetchall())
            digest = hashlib.sha256("\n".join(rows).encode("utf-8")).hexdigest()
            result[name] = {"rows": len(rows), "sha256": digest}
        history = []
        if "flyway_schema_history" in names:
            cursor.execute(f"SELECT version,checksum,success FROM `{database}`.flyway_schema_history ORDER BY installed_rank")
            history = [list(row) for row in cursor.fetchall()]
    return {"tables": result, "flyway": history}


def backup_and_verify(output, databases):
    """执行单事务备份和只向新库的恢复；源变化或恢复差异立即失败，不继续迁移。"""
    # 输入校验：固定来源白名单，恢复目标只能由本函数生成，禁止调用者指定生产目标。
    if any(not re.fullmatch(r"[a-zA-Z0-9_]+", name) for name in databases):
        raise ValueError("非法来源数据库")
    output = output.resolve()
    runtime = (Path(__file__).resolve().parents[1] / ".runtime").resolve()
    if not output.is_relative_to(runtime):
        raise ValueError("私有备份必须写入.runtime")
    output.mkdir(parents=True, exist_ok=False)
    raw = subprocess.run([DOCKER, "inspect", CONTAINER, "--format", "{{json .Config.Env}}"], capture_output=True, check=True)
    settings = dict(item.split("=", 1) for item in json.loads(raw.stdout) if "=" in item)
    secret = settings.get("MYSQL_ROOT_PASSWORD")
    if not secret:
        raise RuntimeError("容器未提供恢复权限，禁止伪造验证")
    connection = pymysql.connect(host="127.0.0.1", port=int(os.getenv("MYSQL_PORT", "3306")), user="root", password=secret,
                                 charset="utf8mb4", autocommit=True)
    summary = []
    try:
        for source in databases:
            before = database_snapshot(connection, source)
            # --databases不能使用，否则dump中的USE会把恢复写回源库。
            command = 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump -uroot --single-transaction --hex-blob --no-tablespaces --skip-add-drop-table ' + source
            dump = subprocess.run([DOCKER, "exec", CONTAINER, "sh", "-c", command], capture_output=True, check=True).stdout
            if re.search(rb"(?im)^\s*(USE\s|CREATE\s+DATABASE\s|DROP\s+DATABASE\s)", dump):
                raise RuntimeError("备份包含数据库重定向，拒绝恢复")
            (output / f"{source}.sql").write_bytes(dump)
            clone = "t11_restore_" + uuid.uuid4().hex
            with connection.cursor() as cursor:
                cursor.execute(f"CREATE DATABASE `{clone}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
            restore = 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --database=' + clone
            subprocess.run([DOCKER, "exec", "-i", CONTAINER, "sh", "-c", restore], input=dump, capture_output=True, check=True)
            restored = database_snapshot(connection, clone)
            after = database_snapshot(connection, source)
            same = before == restored == after
            item = {"source": source, "restore": clone, "tables": len(before["tables"]),
                    "rows": sum(x["rows"] for x in before["tables"].values()), "equal": same,
                    "dumpSha256": hashlib.sha256(dump).hexdigest(), "flyway": before["flyway"]}
            (output / f"{source}-comparison.json").write_text(json.dumps({"before": before, "restored": restored, "after": after}, ensure_ascii=False, indent=2), encoding="utf-8")
            summary.append(item)
            (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
            if not same:
                raise RuntimeError("源库变化或恢复摘要不一致，不能继续迁移")
    finally:
        connection.close()
    return summary


def main():
    """独立验收可重复运行，每轮创建新私有目录与恢复库，不覆盖历史证据。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--business-db", default=os.getenv("FANDAZI_DB_NAME", "fandazi_groupbuy_dev"))
    parser.add_argument("--compare-to", type=Path)
    args = parser.parse_args()
    try:
        summaries = backup_and_verify(args.output, [args.business_db, "dianping_intent"])
        if args.compare_to:
            # 追加迁移只允许新元数据与迁移历史增加；原业务表的每行摘要必须保持不变。
            prior = args.compare_to.resolve()
            runtime = (Path(__file__).resolve().parents[1] / ".runtime").resolve()
            if not prior.is_relative_to(runtime):
                raise ValueError("比较来源必须是私有备份")
            for item in summaries:
                name = item["source"]
                old = json.loads((prior / f"{name}-comparison.json").read_text(encoding="utf-8"))["before"]
                new = json.loads((args.output / f"{name}-comparison.json").read_text(encoding="utf-8"))["after"]
                changed = [table for table, value in old["tables"].items()
                           if table != "flyway_schema_history" and new["tables"].get(table) != value]
                # 已执行版本的checksum和状态不得改变，即使旧表行数仍相等也必须拒绝。
                history = {row[0]: row for row in new["flyway"]}
                if changed or any(history.get(row[0]) != row for row in old["flyway"]):
                    raise RuntimeError("原事实或迁移校验和改变")
                item["previousFactsUnchanged"] = True
            (args.output / "summary.json").write_text(json.dumps(summaries, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(summaries, ensure_ascii=False))
    except Exception as error:
        # 异常正文可能含SQL或连接信息；只输出类型，私有材料保留供本机定位。
        print(json.dumps({"status": "failed", "errorType": type(error).__name__}))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
