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
