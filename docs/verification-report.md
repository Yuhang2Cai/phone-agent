# Verification Report

## 2026-10-03 团购重构当前验收入口

本节为新独立Agent服务的Task12记录；下方2026-09-13是旧外卖Agent历史证据，不代表当前团购或新服务已通过。旧8090仅保留代码，不启动。当前Task9–11已分别独立通过；Task12正在开发自测，尚未独立验收，最终结论待唯一验收者亲跑后回写。

当前源码与设备：双仓按暂存树导出隔离源；Agent生产1120条规则未获真人审批，默认影子；Android业务事实来自Spring。运行说明见`docs/integration-deployment.md`，128个Figma状态及延期/重复边界见`docs/ui-acceptance/`。已知模型两个语义目标错误与简单P95超过8秒观察目标见新Agent的EXP-022，不冒称模型100%或性能全达标。

主Agent自测：Android179单测、lint零错误但637既有警告；新Agent420通过/21条件跳过；隔离后端补入ERROR分发回归后160通过/0失败/0错误/0跳过。真实公开503先被误改401，保留首次设备失败后补入最小既有修复；实际HTTP现为arm200→503→重试200。分段Maestro仅记开发证据，最终独立验收必须最新APK完整99及受控副链实际运行。

ASR、UI13/14核销、退款完成/拒绝预留状态仍延期；不能把测试评价资格夹具称为核销能力完成。原库存71→70事故及同一测试订单按用户“保留”决定原样保留，不恢复或重复购买。

## 历史记录（不复用为当前验收）

Date: 2026-09-13

## Summary

Task 12 added cross-layer/static verification and documentation. Commands below are the actual commands attempted in this workspace. No real API key, JWT secret, internal token, LLM response body, personal address, APK, or runtime secret was written to this report.

## Commands and Results

| Command | Result |
| --- | --- |
| `.\agent-service\.venv\Scripts\python.exe -m pytest agent-service/tests/test_agent_seed_scenarios.py -q --basetemp agent-service/.pytest-basetemp-task12-review-seed -o cache_dir=agent-service/.pytest-cache-task12-review-seed` | PASS: 11 passed, 1 warning in 0.38s after the review fix. Warning was LangGraph serializer deprecation. |
| `.\agent-service\.venv\Scripts\python.exe -m pytest agent-service/tests -q --basetemp agent-service/.pytest-basetemp-task12-review -o cache_dir=agent-service/.pytest-cache-task12-review` | PASS: 136 passed, 2 warnings in 11.45s; after the review fixes, a local rerun passed `142 passed, 2 warnings`. |
| `.\agent-service\.venv\Scripts\python.exe -m compileall -q agent-service/src agent-service/tests` | PASS: exit code 0. |
| `.\agent-service\.venv\Scripts\python.exe -c "<redacted DeepSeek connectivity probe>"` | PASS: HTTP 200. The script loaded local ignored `.env`, did not print `LLM_API_KEY`, and did not print response content. |
| `D:\software\IntelliJ IDEA 2025.2.4\plugins\maven\lib\maven3\bin\mvn.cmd -q -f backend/pom.xml test` | PASS: all 85 backend tests passed. |
| `C:\fdz-build\android-app\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest` | PASS: Android JVM unit tests passed. The single-worker option avoids a Windows stale test-worker lock in this environment. |
| `C:\fdz-build\android-app\gradlew.bat --no-daemon --max-workers=1 assembleDebug` | PASS: debug APK assembled successfully. |
| `java -version` / `javac -version` | PASS via Android Studio JBR: Java 21.0.8. |
| `python -m pytest agent-service/tests/test_agent_seed_scenarios.py -q` | BLOCKED: system `python` is a Microsoft Store stub. The project venv Python was used instead. |
| `rg -n "(sk-[A-Za-z0-9_-]{20,}\|LLM_API_KEY\s*=\|JWT_SECRET\s*=\|AGENT_INTERNAL_TOKEN\s*=\|Bearer\s+[A-Za-z0-9._-]{20,}\|api[_-]?key\s*[:=])" ...` | PASS WITH REVIEW: no committed secret values found. Matches were variable names/placeholders in docs/config/tests and the untracked `task-10-r1-review.md` review text. |
| `git diff --check` | PASS: exit code 0. |

## Seed Agent Scenario Report

`agent-service/tests/test_agent_seed_scenarios.py` reads `data/seed/agent-scenarios.json` and validates these deterministic results with Fake Spring Boot Tool Client:

| Scenario | Expected Tool Sequence | Result | Confirmation Required |
| --- | --- | --- | --- |
| `spicy-under-30` | `search_merchants` | `completed` | No |
| `reorder` | `get_last_order -> create_cart_draft -> validate_cart` | `awaiting_confirmation` | Yes |
| `mild-cold` | `search_merchants` | `completed` | No |
| `no-cilantro-default-address` | `search_merchants -> create_cart_draft -> validate_cart` | `awaiting_confirmation` | Yes |
| `missing-address` | `search_merchants -> create_cart_draft -> validate_cart` | `failed` | No |
| `price-change` | `search_merchants -> create_cart_draft -> validate_cart` | `awaiting_confirmation` | Yes |
| `reorder-unlisted` | `get_last_order` | `failed` | No |
| `over-budget` | `search_merchants -> create_cart_draft -> validate_cart` | `failed` | No |
| `illegal-parameters` | none | `needs_input` | No |

## Coverage Added

### Covered

- Backend: `CrossLayerOrderingFlowTest` covers public API registration, merchant/menu lookup, address defaulting, cart validation, order creation, payment, timeline, ownership isolation, and create/pay idempotency using H2 test configuration.
- Android: `FakeApiOrderingFlowTest` covers login, search, dish option selection, cart, address, checkout, payment, order timeline, and Agent confirmation with a fake API client.
- Agent Service: `test_agent_seed_scenarios.py` covers seed scenario tool sequences with Fake Spring Boot Tool Client and no real LLM quota. The review fix also resumes a confirmed seed order through the real Agent workflow to assert `create_order` is dispatched with current trace, workflow, idempotency key, cart, and address context. A separate `auto_order` seed path asserts production orchestration dispatches `create_order -> pay_mock_order` with stable workflow-scoped idempotency keys.
- Review fixes: confirmation and automatic authorization bind to the confirmed cart snapshot; nested Java order responses are normalized to the payment `orderId`; user-requested budget limits are enforced; the Agent HTTP entry point requires the shared internal token; ambiguous merchant/dish selection fails instead of silently choosing the first result.

### Not Covered By The Android Fake Test

- `FakeApiOrderingFlowTest` is a client-side flow test, not a production backend or Agent Service integration test.
- It does not prove production `AgentViewModel` networking against a live Spring Boot service, production internal-tool authorization, server-side confirmation consumption, or create/pay idempotency.
- Those authorization and idempotency guarantees are covered by the backend H2 tests and Agent Service pytest listed above. The production Android ordering repository and screens are compiled and unit-tested; live device verification still depends on connecting an emulator/device and starting the local services.

## Environment Limits

- The project shell does not expose Maven/Java on PATH; verification uses IntelliJ's Maven and Android Studio's bundled JBR explicitly.
- ADB reported no connected emulator/device during verification, so the APK was built but not installed automatically.
- `agent-service/.pytest_cache` has a pre-existing permission issue; full pytest passed after using workspace-local `--basetemp` and cache directory.
- The untracked root `task-10-r1-review.md` predates this Task 12 work and was not staged.
