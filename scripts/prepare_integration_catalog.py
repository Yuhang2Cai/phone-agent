"""集成目录复制：只读既有开发库，仅向独立13307集成库导入目录依赖，禁止写源库。"""
import json
import os
from pathlib import Path
import pymysql


def main():
    """源库只读一致快照；目标身份及表集合严格固定，口令来自忽略文件不输出。"""
    private = json.loads(Path(os.environ["INTEGRATION_SECRET_FILE"]).read_text(encoding="utf-8-sig"))
    source = pymysql.connect(host="127.0.0.1", port=3306, user=os.environ["DB_USERNAME"],
        password=os.environ["DB_PASSWORD"], database="fandazi_groupbuy_dev", charset="utf8mb4")
    target = pymysql.connect(host="127.0.0.1", port=13307, user="fandazi", password=private["db"],
        database="fandazi_groupbuy_integration", charset="utf8mb4")
    copied = {}
    try:
        with source.cursor() as read, target.cursor() as write:
            read.execute("SET TRANSACTION READ ONLY")
            read.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT")
            write.execute("SELECT DATABASE()")
            if write.fetchone()[0] != "fandazi_groupbuy_integration":
                raise RuntimeError("禁止向非集成库导入")
            read.execute("SHOW TABLES")
            tables = [r[0] for r in read.fetchall() if r[0] not in {"flyway_schema_history", "runtime_capability_retirement"}]
            write.execute("SET FOREIGN_KEY_CHECKS=0")
            for table in tables:
                # 表名仅来自受信schema，不读聊天；导入使用参数化行，不拼接数据或密码。
                if not table.replace("_", "").isalnum():
                    raise ValueError("表名非法")
                read.execute(f"SELECT * FROM `{table}`")
                rows = read.fetchall()
                columns = [c[0] for c in read.description]
                if rows:
                    statement = f"INSERT IGNORE INTO `{table}` ("+",".join(f"`{c}`" for c in columns)+") VALUES ("+",".join("%s" for _ in columns)+")"
                    write.executemany(statement, rows)
                copied[table] = len(rows)
            write.execute("SET FOREIGN_KEY_CHECKS=1")
        target.commit()
        print(json.dumps({"sourceReadOnly": True, "target": "fandazi_groupbuy_integration", "tables": len(copied), "rows": sum(copied.values())}))
    except Exception:
        target.rollback()
        raise
    finally:
        source.rollback()
        source.close()
        target.close()


if __name__ == "__main__":
    main()
