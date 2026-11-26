"""Task5修复证据：仅只读本任务随机账号的最新会话，不读取其它用户或写入交易数据。"""
import json
import os
import sys
from pathlib import Path
from urllib.parse import urlparse

import pymysql


def main():
    """阶段名只控制证据文件名；公开摘要使用白名单，完整准备状态只留忽略目录。"""
    phase = sys.argv[1]
    if phase not in {"before-old-tap", "after-old-tap", "after-current-cancel"}:
        raise ValueError("只允许三个预定验证阶段")
    root = Path("C:/fdz-build")
    account = json.loads((root / ".runtime/task5-account.json").read_text(encoding="utf-8-sig"))
    address = urlparse(os.environ["DB_URL"].removeprefix("jdbc:"))
    connection = pymysql.connect(host=address.hostname, port=address.port or 3306,
        user=os.environ["DB_USERNAME"], password=os.environ["DB_PASSWORD"], database=address.path.strip("/"),
        charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor)
    try:
        with connection.cursor() as cursor:
            # 用户名来自忽略目录的随机隔离账号，参数化过滤并仅取最新设备会话；绝不读取Task8夹具。
            cursor.execute("SELECT s.session_id,s.active_run_id,s.active_run_status,s.trace_id,s.pending_confirmation "
                "FROM agent_sessions s JOIN app_users u ON u.id=s.user_id WHERE u.username=%s ORDER BY s.id DESC LIMIT 1",
                (account["username"],))
            row = cursor.fetchone()
            if row is None:
                raise RuntimeError("未找到本任务会话，不能以空证据代替验证")
            (root / f".runtime/task5-fixround1-{phase}-raw.json").write_text(
                json.dumps(row, default=str, ensure_ascii=False, indent=2), encoding="utf-8")
            summary = json.loads(row.pop("pending_confirmation") or "{}")
            row["summary"] = {key: value for key, value in summary.items() if key in {
                "title", "action", "resourceId", "quantity", "amountCents", "ruleVersion", "expiresAt"}}
            (root / f"docs/verification/task5-evidence/fixround1/{phase}.json").write_text(
                json.dumps(row, default=str, ensure_ascii=False, indent=2), encoding="utf-8")
            print(json.dumps(row, default=str, ensure_ascii=False))
    finally:
        connection.close()


if __name__ == "__main__":
    main()
