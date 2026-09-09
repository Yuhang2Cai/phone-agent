# Task 4 Report — Food Ordering Domain

## Delivered

- Catalog entities and read endpoints for merchants and dishes.
- Current-user address management with ownership checks and exactly one default address.
- Cart drafts whose validation recalculates all totals from current server-side menu data.
- Idempotent order creation with immutable address and dish-price snapshots.
- Simulated payment and forward-only delivery-state transitions.

## Tests

- `mvn -f backend/pom.xml -Dtest=OrderServiceTest,CartServiceTest test`: 6 tests, 0 failures, 0 errors.
- `mvn -f backend/pom.xml test`: 16 tests, 0 failures, 0 errors.
- `git diff --check`: passed with no output.

## Commit

`feat: add food ordering domain`

## Remaining work

- Task 5 will add deterministic seed data and a loader.
- Task 7 will consume these endpoints from the Android client.
