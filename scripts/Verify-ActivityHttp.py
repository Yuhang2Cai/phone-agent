"""活动真实 HTTP 验收驱动：只访问本机独立后端，创建合成账号并核对订单、任务、退款和账本。

凭据和原请求键仅写入指定的 .runtime 目录；失败保留原始记录，不自动重试业务写入。
"""
import argparse
import concurrent.futures
import json
import pathlib
import secrets
import threading
import time
import urllib.error
import urllib.request
import uuid


class Verification:
    """封装真实请求与安全证据；每个运行目录只能执行一次，避免未知写被无意重放。"""

    def __init__(self, output, deal):
        self.output = pathlib.Path(output).resolve()
        if ".runtime" not in self.output.parts:
            raise ValueError("凭据输出必须位于忽略的 .runtime 目录")
        self.output.mkdir(parents=True, exist_ok=True)
        self.private_path = self.output / "private.json"
        if self.private_path.exists():
            raise ValueError("已有运行记录，请先核查原请求，不能覆盖重跑")
        self.private = {"users": {}, "writes": []}
        self.results = []
        self.lock = threading.Lock()
        self.deal = str(uuid.UUID(deal))
        self.save()

    def save(self):
        """原子替换私有检查点，写请求在发包前先保存目标和原键。"""
        temporary = self.private_path.with_suffix(".tmp")
        temporary.write_text(json.dumps(self.private, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(self.private_path)

    def call(self, method, path, user=None, body=None, key=None, expected=(200,), token=None):
        """一次真实 HTTP 调用，不使用系统代理、不重试；返回响应与单调时钟耗时。"""
        headers = {"Content-Type": "application/json"}
        credential = token if token is not None else user.get("token") if user else None
        if credential:
            headers["Authorization"] = "Bearer " + credential
        if key:
            headers["Idempotency-Key"] = key
        if method != "GET":
            with self.lock:
                self.private["writes"].append({"path": path, "key": key, "body": body, "owner": user.get("user", {}).get("id") if user else None})
                self.save()
        request = urllib.request.Request("http://127.0.0.1:8160/api/" + path, data=None if body is None else json.dumps(body).encode(), method=method, headers=headers)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        started = time.perf_counter_ns()
        try:
            response = opener.open(request, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            status = response.status
        duration = (time.perf_counter_ns() - started) / 1_000_000
        parsed = json.loads(raw) if raw else {}
        if status not in expected:
            raise AssertionError(f"{method} {path} 状态{status}，预期{expected}，原因码{parsed.get('reasonCode', parsed.get('code', '未提供'))}")
        return parsed, duration, status

    def user(self, label):
        """先保存合成用户名和随机密码，再注册；不在控制台输出凭据。"""
        name = "activity-http-" + uuid.uuid4().hex[:12]
        credentials = {"username": name, "password": "A!" + secrets.token_hex(12), "email": name + "@example.invalid", "nickname": "合成验收账号"}
        self.private["users"][label] = {"credentials": credentials}
        self.save()
        result, _, _ = self.call("POST", "auth/register", body=credentials, expected=(201,))
        self.private["users"][label].update(result)
        self.save()
        return self.private["users"][label]

    def accept(self, user, task, key=None):
        """领取真实定义，固定版本和一次性生成的请求键。"""
        key = key or str(uuid.uuid4())
        result, _, _ = self.call("POST", f"activities/tasks/{task['definitionId']}/accept", user, {"expectedRuleVersion": task["ruleVersion"]}, key)
        return result, key

    def paid_order(self, user):
        """报价、确认创建和模拟支付均走真实服务；不直接插订单、不产生真实资金交易。"""
        session = "activity-http-" + uuid.uuid4().hex
        quote, _, _ = self.call("POST", "purchases/quotes", user, {"dealId": self.deal, "quantity": 1, "sessionId": session})
        order, _, _ = self.call("POST", "group-orders", user, {"quoteId": quote["quoteId"], "confirmationGrant": quote["confirmationGrant"], "sessionId": session, "idempotencyKey": str(uuid.uuid4())})
        paid, _, _ = self.call("POST", f"group-orders/{order['orderId']}/mock-pay", user, {"idempotencyKey": str(uuid.uuid4())})
        assert paid["status"] == "PAID"
        return paid

    def claim(self, user, instance, key=None, expected=(200,)):
        """手动领奖一次；并发用例可提供不同键，但实例唯一奖励必须相同。"""
        return self.call("POST", f"activities/task-instances/{instance['instanceId']}/claim", user, {}, key or str(uuid.uuid4()), expected)

    def check(self, name, actual):
        """只记录已核对的安全业务摘要，检查未执行时不会生成通过行。"""
        self.results.append({"scenario": name, "passed": True, "actual": actual})
        (self.output / "results.json").write_text(json.dumps(self.results, ensure_ascii=False, indent=2), encoding="utf-8")

    def refund_preparation(self, user, order):
        """使用真实券和金额绑定凭证，后续提交与领奖共享服务端事务互斥。"""
        payload = {"sessionId": "activity-refund-" + uuid.uuid4().hex, "voucherId": order["vouchers"][0]["publicId"]}
        prepared, _, _ = self.call("POST", "refunds/prepare", user, payload)
        return {**payload, "reason": "合成退款竞争验收", "confirmationGrant": prepared["confirmationGrant"]}

    def functional(self):
        """固定业务场景按依赖推进，任一指定断言失败立即停止并保留所有已有证据。"""
        public, _, _ = self.call("GET", "activities/tasks")
        daily = next(task for task in public["tasks"] if task["type"] == "DAILY_ORDER_COUNT" and task["targetCount"] == 1)
        other = next(task for task in public["tasks"] if task["type"] == "DAILY_ORDER_COUNT" and task["targetCount"] == 3)
        self.call("GET", "coins/account", expected=(401,))
        self.call("GET", "coins/account", token="invalid.synthetic.jwt", expected=(401,))
        self.check("HTTP-01 游客和无效JWT", {"publicTasks": len(public["tasks"]), "protectedStatus": 401})
        a, b = self.user("a"), self.user("b")
        target = f"activities/tasks/{daily['definitionId']}/accept"
        self.call("POST", target, a, {"expectedRuleVersion": 1, "rewardCoins": 999}, str(uuid.uuid4()), (400,))
        self.call("POST", target, a, {"expectedRuleVersion": 999}, str(uuid.uuid4()), (409,))
        instance, original = self.accept(a, daily)
        duplicate, _ = self.accept(a, daily, original)
        assert duplicate["instanceId"] == instance["instanceId"]
        self.call("POST", f"activities/tasks/{other['definitionId']}/accept", a, {"expectedRuleVersion": 1}, original, (409,))
        other_owner, _ = self.accept(b, daily, original)
        assert other_owner["instanceId"] != instance["instanceId"]
        self.call("GET", f"activities/task-instances/{instance['instanceId']}", b, expected=(404,))
        self.claim(b, instance, expected=(404,))
        self.claim(a, instance, expected=(409,))
        self.check("HTTP-02 规则/幂等/归属", {"instance": instance["instanceId"], "otherInstance": other_owner["instanceId"], "noPayment": 409})
        paid = self.paid_order(a)
        detail, _, _ = self.call("GET", f"activities/task-instances/{instance['instanceId']}", a)
        account, _, _ = self.call("GET", "coins/account", a)
        assert detail["status"] == "READY" and account["balance"] == 0
        self.check("HTTP-03 支付只达标不自动发币", {"order": paid["orderId"], "status": detail["status"], "balance": 0})
        # 二十个真实HTTP连接同时竞争，同用户账户锁必须保证唯一回执和余额。
        barrier = threading.Barrier(20)
        def concurrent_claim(_):
            barrier.wait(timeout=20)
            return self.claim(a, instance)[0]
        with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
            receipts = list(pool.map(concurrent_claim, range(20)))
        ids = {receipt["receiptId"] for receipt in receipts}
        assert len(ids) == 1 and all(receipt["balance"] == daily["rewardCoins"] for receipt in receipts)
        ledger, _, _ = self.call("GET", "coins/transactions", a)
        assert len(ledger["items"]) == 1 and ledger["items"][0]["amount"] == daily["rewardCoins"]
        self.call("GET", "coins/transactions?cursor=" + ledger["items"][0]["transactionId"], b, expected=(400,))
        self.check("HTTP-04 二十并发唯一奖励及游标隔离", {"count": 20, "receipt": next(iter(ids)), "ledgerCount": len(ledger["items"]), "balance": receipts[0]["balance"]})
        # 先提交退款再领奖：真实RefundService落库后必须冻结新奖励，不以直接SQL夹具替代。
        b_order = self.paid_order(b)
        refund_body = self.refund_preparation(b, b_order)
        requested, _, _ = self.call("POST", "refunds", b, refund_body)
        refused, _, _ = self.claim(b, other_owner, expected=(409,))
        assert requested["status"] == "REQUESTED" and refused["reasonCode"] == "NOT_READY"
        self.check("HTTP-05 真实退款申请冻结领奖", {"order": b_order["orderId"], "refundStatus": requested["status"], "claimReason": refused["reasonCode"]})
        # 真正并行的退款申请与领奖允许任一合法串行顺序，但不得死锁、半账或重复发放。
        c = self.user("race")
        c_instance, _ = self.accept(c, daily)
        c_order = self.paid_order(c)
        c_refund = self.refund_preparation(c, c_order)
        gate = threading.Barrier(2)
        def racing_claim():
            gate.wait(timeout=10)
            return self.claim(c, c_instance, expected=(200, 409))
        def racing_refund():
            gate.wait(timeout=10)
            return self.call("POST", "refunds", c, c_refund)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            claim_future, refund_future = pool.submit(racing_claim), pool.submit(racing_refund)
            claim_result, _, claim_status = claim_future.result(timeout=30)
            refund_result, _, _ = refund_future.result(timeout=30)
        assert refund_result["status"] == "REQUESTED"
        final_ledger, _, _ = self.call("GET", "coins/transactions", c)
        expected_count = 1 if claim_status == 200 else 0
        assert len(final_ledger["items"]) == expected_count
        if claim_status == 409:
            assert claim_result["reasonCode"] == "NOT_READY"
        final_account, _, _ = self.call("GET", "coins/account", c)
        assert final_account["balance"] == expected_count * daily["rewardCoins"]
        self.check("HTTP-06 真实退款与领奖竞争", {"claimStatus": claim_status, "refundStatus": refund_result["status"], "ledgerCount": expected_count, "balance": final_account["balance"]})


if __name__ == "__main__":
    # 入口限制固定本机8160；参数只选择证据目录和已核对的合成套餐，不接受生产URL。
    parser = argparse.ArgumentParser(description="活动真实HTTP组合验证")
    parser.add_argument("--output", required=True)
    parser.add_argument("--deal-id", required=True)
    args = parser.parse_args()
    verification = Verification(args.output, args.deal_id)
    try:
        verification.functional()
    except Exception as error:
        (verification.output / "failure.json").write_text(json.dumps({"type": type(error).__name__, "message": str(error), "completed": len(verification.results)}, ensure_ascii=False, indent=2), encoding="utf-8")
        raise
    print(f"真实HTTP场景通过：{len(verification.results)}；安全结果：{verification.output / 'results.json'}")
