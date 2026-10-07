"""R1事实保护模块：仅只读核对714原行和本轮开始全部业务事实，禁止创建订单或修改资格。

依赖pymysql及R0冻结验证器的稳定序列化；原完整行仅放忽略目录，公开证据为计数和SHA。
"""
import importlib.util
import json
import os
import sys
from pathlib import Path

import pymysql

# R0敏感原始事实只用来按ID核对，不输出原内容；R1新增摘要不会覆盖R0首次结果。
BASE = Path("C:/fdz-build")
PRIVATE = BASE / ".runtime/task7-independent"
PUBLIC = BASE / "docs/verification/task7-independent-evidence/r1"
spec = importlib.util.spec_from_file_location("frozen_guard", BASE / "docs/verification/task7-evidence/Verify-Task7Api.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


def main():
    """输入start/end阶段，输出脱敏摘要；任何原事实变化直接抛错，既有证据不覆盖。"""
    phase = sys.argv[1]
    assert phase in ("start", "end")
    result = PUBLIC / ("facts-" + phase + ".json")
    assert not result.exists(), "禁止覆盖事实保护结果"
    original = json.loads((PRIVATE / "original-facts.json").read_text(encoding="utf-8"))
    with pymysql.connect(host="127.0.0.1", user=os.environ["DB_USERNAME"], password=os.environ["DB_PASSWORD"], database="fandazi_groupbuy_dev", charset="utf8mb4", autocommit=True, cursorclass=pymysql.cursors.DictCursor) as connection:
        current = {table: verifier.rows(connection, "SELECT * FROM `" + table + "` ORDER BY id") for table in verifier.TABLES}
    summary = {}
    for table, expected in original.items():
        # 按开始ID选择原完整行，允许此前本任务新增隔离资源保留，但不能掩盖任何原行被改或丢失。
        by_id = {row["id"]: row for row in current[table]}
        selected = [by_id[row["id"]] for row in expected]
        assert verifier.digest(selected) == verifier.digest(expected), table
        summary[table] = {"originalCount": len(expected), "originalSha": verifier.digest(selected), "currentCount": len(current[table]), "currentSha": verifier.digest(current[table])}
    before_path = PRIVATE / "r1/before-facts.json"
    if phase == "start":
        assert not before_path.exists(), "R1已采集开始事实"
        verifier.save(before_path, current)
    else:
        # 401只读流程连本轮隔离订单/券也不得改动；所有业务表整体摘要须保持R1开始值。
        before = json.loads(before_path.read_text(encoding="utf-8"))
        assert verifier.digest(before) == verifier.digest(current), "R1只读流程改变业务事实"
    verifier.save(result, {"phase": phase, "passed": True, "originalTotal": sum(len(v) for v in original.values()), "currentTotal": sum(len(v) for v in current.values()), "tables": summary})
    print("R1原事实保护 " + phase + " 通过")


if __name__ == "__main__":
    main()
