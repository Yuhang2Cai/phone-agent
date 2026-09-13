# Verification Report

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
