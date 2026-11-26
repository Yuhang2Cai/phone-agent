"""Task5修复后协议回归：真实请求只经Spring，准备后只取消，按原身份配对HTTP/SSE。"""
import json
import uuid
from pathlib import Path

import httpx

ROOT = Path("C:/fdz-build")
EVIDENCE = ROOT / "docs/verification/task5-evidence/fixround1"


def main():
    """新会话核对暂停补充、准备取消与跨通道身份；完整响应含短期凭据，禁止公开保存。"""
    account = json.loads((ROOT / ".runtime/task5-account.json").read_text(encoding="utf-8-sig"))
    fixture = json.loads((ROOT / ".runtime/task5-order-fixture.json").read_text(encoding="utf-8-sig"))
    session_file = ROOT / ".runtime/task5-fixround1-api-session.txt"
    session = session_file.read_text(encoding="utf-8") if session_file.exists() else str(uuid.uuid4())
    session_file.write_text(session, encoding="utf-8")
    raw_file = ROOT / ".runtime/task5-fixround1-api-raw.json"
    # 修正证据读取器时复用本轮已经实际完成的HTTP，不重复准备报价；SSE每次仍亲自重新读取。
    rows = json.loads(raw_file.read_text(encoding="utf-8")) if raw_file.exists() else []
    with httpx.Client(base_url="http://127.0.0.1:8080/api", trust_env=False, timeout=125) as client:
        # 隔离账号早前保存的token可能已到期；复用真实登录接口取得本轮有效身份，秘密不回写公开证据。
        auth = client.post("/auth/login", json={"username": account["username"], "password": account["password"]}).raise_for_status().json()
        client.headers["Authorization"] = "Bearer " + auth["token"]
        def send(label, text):
            """返回实际协议结果并立即保存私有原件；公开文件只保存显式白名单的身份和状态。"""
            existing = next((row for row in rows if row["scenario"] == label), None)
            if existing:
                print(json.dumps({"scenario": label, "status": existing["response"]["status"], "reusedActualHttp": True}), flush=True)
                return existing["response"]
            response = client.post(f"/agent/sessions/{session}/messages", json={"text": text, "mode": "CONFIRM"}).raise_for_status().json()
            rows.append({"scenario": label, "sessionId": session, "response": response})
            raw_file.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
            print(json.dumps({"scenario": label, "status": response["status"]}), flush=True)
            return response

        def originals(response):
            """网关兼容事件无原eventId，不伪造身份，也不把数据库游标当sequence。"""
            return [event["payload"] for event in response["events"] if event.get("payload", {}).get("eventId")]

        city = client.get("/stores/cities").raise_for_status().json()[0]["cityCode"]
        missing = send("missing-city", "recommend group buying deals nearby")
        resumed = send("resume-city", city)
        assert missing["status"] == "NEEDS_INPUT" and resumed["status"] == "COMPLETED"
        assert originals(missing)[0]["runId"] == originals(resumed)[0]["runId"]
        assert originals(missing)[0]["traceId"] != originals(resumed)[0]["traceId"]
        prepare = send("prepare", f"buy group buying deal {fixture['dealId']} quantity 2")
        assert prepare["status"] == "AWAITING_CONFIRMATION"
        prepare_identity = originals(prepare)
        target_trace = prepare_identity[0]["traceId"]
        sse, raw_lines, data = [], [], []
        cursor = None
        # 从会话起点重放，仅对本轮准备trace配对；等待确认的网关结果是confirmation_required，不是completed。
        with client.stream("GET", f"/agent/sessions/{session}/events", headers={"Last-Event-ID": "0"}, timeout=15) as stream:
            stream.raise_for_status()
            for line in stream.iter_lines():
                raw_lines.append(line)
                if line.startswith("id:"):
                    cursor = int(line[3:].strip())
                elif line.startswith("data:"):
                    data.append(line[5:].strip())
                elif not line and data:
                    event = json.loads("".join(data))
                    data = []
                    if event.get("payload", {}).get("traceId") == target_trace:
                        sse.append({"databaseCursor": cursor, "event": event})
                        if event["type"] == "confirmation_required" and not event.get("payload", {}).get("eventId"):
                            break
        (ROOT / ".runtime/task5-fixround1-sse.txt").write_text("\n".join(raw_lines), encoding="utf-8")
        keys = ("eventId", "sequence", "runId", "traceId", "status")
        pairs = []
        for identity in prepare_identity:
            matches = [row for row in sse if row["event"].get("payload", {}).get("eventId") == identity["eventId"]]
            assert len(matches) == 1
            peer = matches[0]
            pairs.append({"http": {key: identity.get(key) for key in keys},
                "sse": {key: peer["event"]["payload"].get(key) for key in keys}, "databaseCursor": peer["databaseCursor"]})
        assert pairs and all(row["http"] == row["sse"] and all(value is not None for value in row["http"].values()) for row in pairs)
        # 取消保持同run、结束等待；绝不发送确认或购买/支付/售后工具调用。
        cancelled = send("cancel", "取消")
        assert cancelled["nextAction"] == "cancelled" and originals(cancelled)[0]["runId"] == prepare_identity[0]["runId"]
        forbidden = {"create_group_buy_order", "pay_mock_order", "request_refund", "submit_complaint", "redeem_voucher"}
        observed = {event.get("toolName") or event.get("payload", {}).get("toolName") for row in rows for event in row["response"]["events"]}
        assert not forbidden.intersection(observed)
        public = {"sessionId": session, "states": [{"scenario": row["scenario"], "status": row["response"]["status"]} for row in rows],
            "runId": prepare_identity[0]["runId"], "httpOriginalCount": len(prepare_identity), "paired": len(pairs), "allEqual": True,
            "pairs": pairs, "compatibility": [{"type": row["event"]["type"], "runId": row["event"]["payload"].get("runId"),
                "traceId": row["event"]["payload"].get("traceId"), "status": row["event"].get("status"), "databaseCursor": row["databaseCursor"]}
                for row in sse if not row["event"].get("payload", {}).get("eventId")], "forbiddenWriteTools": []}
        (EVIDENCE / "fresh-http-sse-identity.json").write_text(json.dumps(public, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"paired": len(pairs), "allEqual": True, "compatibility": len(public["compatibility"])}))


if __name__ == "__main__":
    main()
