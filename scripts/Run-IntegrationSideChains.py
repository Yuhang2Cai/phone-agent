"""独立集成设备副链：真实断网、一次503、Agent停机恢复、敏感记忆确认，不模拟业务响应。"""
import argparse
import json
import os
import re
import subprocess
import time
import uuid
from pathlib import Path
import httpx


def main():
    """使用最新APK和本轮合成账号；所有控制命令与恢复都可核对，失败不计通过。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    # 子进程切换到各阶段目录后仍使用绝对证据路径，避免相对路径被再次拼接且难以定位截图。
    args.evidence = args.evidence.resolve()
    if args.evidence.exists() or ".runtime" not in args.evidence.resolve().parts:
        raise ValueError("副链证据必须是新的私有目录")
    args.evidence.mkdir(parents=True)
    root = Path(__file__).resolve().parents[1]
    users = {u["scenario"]: u for u in json.loads(args.fixtures.read_text(encoding="utf-8-sig"))["scenarios"]}
    adb = "C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe"
    env = os.environ.copy()
    env.update(JAVA_HOME="C:/Users/caid0/.jdks/ms-17.0.17", JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8")
    with (args.evidence/"controls.log").open("w", encoding="utf-8") as log:
        def control(command, check=True):
            """只运行当前模拟器及固定集成容器命令；输出仍保留私有目录。"""
            return subprocess.run(command, stdout=log, stderr=log, check=check)
        control([adb, "-s", "emulator-5554", "uninstall", "com.fandazi.app"], False)
        control([adb, "-s", "emulator-5554", "uninstall", "com.dirror.music"], False)
        control([adb, "-s", "emulator-5554", "install", str(args.apk.resolve())])
        def flow(name, user=None, extra=None):
            """每阶段只运行现有明确场景；任一非零退出立即失败，不将后续成功覆盖首次失败。"""
            user = user or users["purchase"]
            folder = args.evidence/name.removesuffix(".yaml")
            folder.mkdir()
            values = {"MAESTRO_USERNAME": user["username"], "MAESTRO_PASSWORD": user["password"], **(extra or {})}
            cmd = ["C:/Users/caid0/.local/maestro/bin/maestro.bat", "--device", "emulator-5554", "test",
                   "--test-output-dir", str(folder), "--debug-output", str(folder)]
            for key, value in values.items():
                cmd += ["-e", f"{key}={value}"]
            cmd.append(str(root/"maestro"/name))
            with (folder/"maestro.log").open("w", encoding="utf-8") as output:
                result = subprocess.run(cmd, env=env, cwd=folder.resolve(), stdout=output, stderr=output)
            if result.returncode:
                raise RuntimeError(f"设备副链{name}失败exit={result.returncode}")
            print(f"{name}: exit0", flush=True)
        flow("group-buying-05-login.yaml")
        wifi = subprocess.check_output([adb, "-s", "emulator-5554", "shell", "settings", "get", "global", "wifi_on"], text=True).strip()
        data = subprocess.check_output([adb, "-s", "emulator-5554", "shell", "settings", "get", "global", "mobile_data"], text=True).strip()
        def wait_network(connected, label):
            """保存真实系统默认网络证据，不以开关命令成功代替断网/恢复事实。"""
            for _ in range(30):
                dump = subprocess.check_output([adb, "-s", "emulator-5554", "shell", "dumpsys", "connectivity"], text=True, encoding="utf-8", errors="replace")
                match = bool(re.search(r"Active default network:\s*\d+", dump)) if connected else "Active default network: none" in dump
                if match:
                    (args.evidence/f"network-{label}.txt").write_text(dump, encoding="utf-8")
                    return
                time.sleep(1)
            raise RuntimeError("实际默认网络未达到目标状态")
        # 真实切断全部网络后，原地恢复；finally确保失败也恢复，不能污染下一轮验收。
        try:
            control([adb, "-s", "emulator-5554", "shell", "svc", "wifi", "disable"])
            control([adb, "-s", "emulator-5554", "shell", "svc", "data", "disable"])
            wait_network(False, "off")
            flow("group-buying-05-orders-network.yaml")
        finally:
            control([adb, "-s", "emulator-5554", "shell", "svc", "wifi", "enable" if wifi == "1" else "disable"])
            control([adb, "-s", "emulator-5554", "shell", "svc", "data", "enable" if data == "1" else "disable"])
        wait_network(True, "restored")
        flow("group-buying-05-orders-network-recover.yaml")
        # 重新定位首页再注入一次本人503；开发控制端不存在或未启用就报错，不能改接口骗过测试。
        flow("group-buying-12-home.yaml")
        with httpx.Client(base_url="http://127.0.0.1:18080", timeout=120, trust_env=False) as client:
            purchase = users["purchase"]
            auth = client.post("/api/auth/login", json={k: purchase[k] for k in ("username", "password")}).raise_for_status().json()
            headers = {"Authorization": "Bearer "+auth["token"]}
            client.post("/api/development/order-failure", headers=headers).raise_for_status()
            flow("group-buying-05-orders-error.yaml")
            # Agent停机时业务目录依然200且公开AI明确FAILED；恢复只重试，不启动8090。
            try:
                control(["docker", "stop", "fandazi-t12-agent-1"])
                assert client.get("/api/stores").status_code == 200
                result = client.post("/api/agent/sessions/"+str(uuid.uuid4())+"/messages", headers=headers,
                                     json={"text": "你好", "mode": "CONFIRM"}).raise_for_status().json()
                assert result["status"] == "FAILED"
                flow("task5-ai-error.yaml", extra={"TASK5_QUERY": "hello"})
            finally:
                control(["docker", "start", "fandazi-t12-agent-1"])
            # 健康恢复以真实HTTP判定，不用固定sleep就假设恢复；超时仍失败。
            with httpx.Client(trust_env=False, timeout=3) as health:
                for _ in range(60):
                    try:
                        if health.get("http://127.0.0.1:18091/internal/v1/health").status_code == 200:
                            break
                    except httpx.HTTPError:
                        pass
                    time.sleep(1)
                else:
                    raise RuntimeError("Agent恢复未通过健康检查")
            # Windows批处理会把无空格参数中的竖线解释为管道；用单一Unicode正则避免测试命令被拆开。
            # 仍断言真实问候文字，不能仅以错误卡消失或消息区域存在代替恢复成功。
            flow("task5-ai-retry.yaml", extra={"TASK5_EXPECTED": r".*[\u4f60\u60a8]\u597d.*"})
            # 敏感和重复普通偏好由真实聊天政策生成；只操作本轮合成账号，不复用原用户记忆。
            empty = users["no_orders"]
            token = client.post("/api/auth/login", json={k: empty[k] for k in ("username", "password")}).raise_for_status().json()["token"]
            own = {"Authorization": "Bearer "+token}
            for query in ["请记住我对花生过敏", "我喜欢川菜", "我喜欢川菜"]:
                client.post("/api/agent/sessions/"+str(uuid.uuid4())+"/messages", headers=own,
                            json={"text": query, "mode": "CONFIRM"}).raise_for_status()
            states = {m["status"] for m in client.get("/api/agent/memories", headers=own).raise_for_status().json()["items"]}
            assert {"pending", "pending_sensitive"}.issubset(states)
            flow("group-buying-10-memory-confirm-clear.yaml", user=empty)
    print("网络/503/Agent停机恢复/真实敏感与普通记忆确认副链完成；默认服务配置需恢复")


if __name__ == "__main__":
    main()
