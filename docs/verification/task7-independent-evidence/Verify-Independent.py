"""Task7 独立验收运行入口：复用冻结验证器的真实 API/数据库调用，隔离账号与证据。

所属流程为独立验收，依赖 httpx、pymysql 和已冻结开发验证器；不修改产品源码。
账号、完整原始事实与敏感响应仅保存到忽略目录；公开证据不写入口令或 JWT。
"""
import importlib.util
import json
import os
import secrets
import sys
import uuid
from pathlib import Path

import httpx
import pymysql

# 显式改用本轮输出位置，避免运行开发验证器时覆盖既有首次失败或复用旧账号。
spec = importlib.util.spec_from_file_location("task7_frozen_verifier", "C:/fdz-build/docs/verification/task7-evidence/Verify-Task7Api.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)
verifier.PRIVATE = Path("C:/fdz-build/.runtime/task7-independent")
verifier.PUBLIC = Path("C:/fdz-build/docs/verification/task7-independent-evidence")


def main():
    """按阶段运行冻结断言；setup 额外注册独立空账号，异常保留原断言并向上抛出。"""
    phase = sys.argv[1]
    with pymysql.connect(host="127.0.0.1", user=os.environ["DB_USERNAME"], password=os.environ["DB_PASSWORD"],
                         database="fandazi_groupbuy_dev", charset="utf8mb4", autocommit=True,
                         cursorclass=pymysql.cursors.DictCursor) as connection, \
            httpx.Client(base_url="http://127.0.0.1:8080/api", trust_env=False, timeout=140) as client:
        if phase == "setup":
            # 冻结流程新建两套餐、三个实际订单；不恢复原库存、不调用任何售后或核销写接口。
            verifier.setup(client, connection)
            account = dict(username="task7_empty_" + uuid.uuid4().hex[:10], password=secrets.token_urlsafe(24))
            auth = client.post("/auth/register", json={**account, "email": account["username"] + "@example.invalid"}).raise_for_status().json()
            account["userId"] = auth["user"]["id"]
            verifier.save(verifier.PRIVATE / "empty.json", account)
            verifier.login(client, account)
            verifier.check("独立第三账号订单券均为空", client.get("/group-orders").raise_for_status().json() == [] and client.get("/vouchers").raise_for_status().json() == [])
        elif phase in ("protect", "release"):
            verifier.protect(connection, phase == "release")
        else:
            # 仅本轮新账号可以登录；阶段输入不接受任意函数名称或旧目录。
            account = json.loads((verifier.PRIVATE / "account.json").read_text(encoding="utf-8"))
            verifier.login(client, account)
            {"reads": verifier.reads, "agent": verifier.agent, "identity": verifier.identity}[phase](client, connection, account)


if __name__ == "__main__":
    main()
