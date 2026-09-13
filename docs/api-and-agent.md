# API and Agent Notes

## Public App API

Android talks only to the Spring Boot `/api` surface with a user Bearer token. The normal user flow is:

1. `POST /api/auth/register` or `POST /api/auth/login`
2. `GET /api/merchants` and `GET /api/merchants/{merchantId}/dishes`
3. `POST /api/addresses`, `PATCH /api/addresses/{id}/default`
4. `POST /api/cart/drafts`, `PUT /api/cart/drafts/{id}/items`, `POST /api/cart/drafts/{id}/validate`
5. `POST /api/orders` with `Idempotency-Key`
6. `POST /api/orders/{id}/pay` with `Idempotency-Key`
7. `GET /api/orders/{id}`

Spring Boot recalculates prices, validates address ownership, reserves stock, stores order snapshots, and enforces idempotency. Android never sends internal tool tokens.

## Agent API

Android sends user messages to:

- `POST /api/agent/sessions/{sessionId}/messages`
- `GET /api/agent/sessions/{sessionId}/events`

The message endpoint returns the terminal or paused `AgentResult`. The events endpoint is SSE and supports `Last-Event-ID` so the client can reconnect without replaying the whole session.

Spring Boot is the only service allowed to call Python Agent Service:

- `POST /internal/agent/run`

Python Agent Service calls Spring Boot tools only through:

- `POST /internal/tools/{toolName}`

Every tool request carries `userId`, `sessionId`, `traceId`, `workflowId`, `idempotencyKey`, and payload. The allowed tool set is fixed: `search_merchants`, `list_dishes`, `get_last_order`, `create_cart_draft`, `validate_cart`, `get_addresses`, `create_order`, and `pay_mock_order`.

## Fake Verification

Task 12 adds deterministic tests that do not consume LLM quota:

- Backend `CrossLayerOrderingFlowTest` covers register -> menu -> address -> cart -> order -> payment -> timeline, plus ownership and idempotency.
- Android `FakeApiOrderingFlowTest` covers login, search, dish option selection, cart, address, checkout, payment, order timeline, and Agent confirmation using a fake API client.
- Agent `test_agent_seed_scenarios.py` reads `data/seed/agent-scenarios.json` and compares tool sequence, confirmation requirement, and terminal status using Fake Spring Tool Client.

The optional DeepSeek connectivity check is local only. It must never print the key or response body; record only HTTP status or failure class.
