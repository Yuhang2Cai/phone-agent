# Task 10 Report — Agent Gateway, Tool Authorization, and SSE

## Delivered

- Added authenticated Agent Gateway endpoints for message submission and replayable SSE events.
- Added an Agent Service HTTP client with bounded timeout and retryable terminal fallback.
- Persisted server-owned sessions and execution events; model-supplied user, trace, and session ownership cannot select resources.
- Added an internal-token-protected fixed eight-tool router:
  `search_merchants`, `list_dishes`, `get_last_order`, `create_cart_draft`,
  `validate_cart`, `get_addresses`, `create_order`, and `pay_mock_order`.
- Added long-lived automation policies and time-limited task grants. Order creation re-reads server-side address, cart quote, current price, inventory, policy/grant amount, and expiry before using the existing idempotent order service.
- Added reorder termination for unavailable dishes and options, while preserving normal catalogue/cart/order endpoints during Agent Service failure.

## Tests

| Command | Result |
| --- | --- |
| `mvn -f backend/pom.xml -Dtest=AgentGatewayTest,AutomationPolicyTest,AgentToolAuthorizationTest test` | 10 passed, 0 failed, 0 errors |
| `mvn -f backend/pom.xml test` | 62 passed, 0 failed, 0 errors across 14 suites |

The tests cover user/session isolation, token and allowlist enforcement, ordinary recommendations, confirmed and task-auto orders, policy/grant amount and expiry bounds, replay idempotency, unavailable Agent fallback, SSE replay, and dish/option-unavailable reorder termination.

## Deployment note

Set `AGENT_INTERNAL_TOKEN` in Spring Boot and have the isolated Agent Service (or its internal reverse proxy) send the same value in `X-Agent-Token` on `/internal/tools/*`. The existing Agent Service source does not send that header; it was intentionally not changed because Task 10 excludes `agent-service`.

## Fix round 1 completion

- Added production Flyway migration `V2__create_agent_gateway.sql` for `agent_sessions`, `agent_events`, `automation_policies`, and `automation_grants`; the schema is verified with `ddl-auto=validate`.
- Agent Service now reads `AGENT_INTERNAL_TOKEN` and sends it as `X-Agent-Token` on internal tool calls, with configured-token, environment-token, and error-contract tests.
- CONFIRM authorization is bound to one workflow/cart/address and consumed after the matching order is created; task grants are similarly bound to one workflow/cart/order and consumed after payment.
- SSE now keeps live subscribers, replays events after `Last-Event-ID`, and publishes newly recorded events to existing connections.
- Idempotent order and payment retries return the existing resource before inventory, authorization, or other mutable-state checks.
- Automatic payment is restricted to the order created by the current AUTO session/workflow and rechecks authorization before charging.
- Added production policy and task-grant endpoints for long-term automation and one-time automation setup.
- Added negative, cross-user, replay, schema-validation, and cross-service contract coverage; malformed Agent Service responses are mapped to the gateway's unavailable/error contract.

## Final verification

| Command | Result |
| --- | --- |
| Targeted backend agent/automation/schema tests | 19 passed, 0 failed, 0 errors |
| Targeted Agent Service integration tests | 107 passed, 0 failed |
| `mvn -f backend/pom.xml test` | 72 passed, 0 failed, 0 errors |
| `python -m pytest agent-service/tests -q` | 125 passed, 0 failed |
| `python -m compileall -q agent-service/src agent-service/tests` | passed |
| `git diff --check` | passed |

The deployment note above is superseded for this fix round: the Agent Service now sends the internal token. The only observed test output beyond pass/fail status was dependency deprecation/Flyway compatibility warnings; no functional test issue remains known.
