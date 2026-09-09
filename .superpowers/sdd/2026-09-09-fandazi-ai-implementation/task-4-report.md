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

## Repair round 1

- Replaced Address/Cart/Order API entity serialization with explicit response DTOs/projections; added MockMvc regression coverage proving `passwordHash` is absent.
- Added end-to-end `optionIds` handling: draft persistence, ownership/availability validation, option price deltas, and immutable order-item option snapshots.
- Moved stock reservation to transactional order creation with pessimistic dish locking; invalid/out-of-stock/unlisted dishes fail without committing the reservation.
- Removed the user-facing `POST /api/orders/{id}/delivery` progression endpoint; delivery timeline querying remains available for controlled internal use.
- Serialized default-address creation/update with a pessimistic user-row lock while retaining the rule that a default address cannot be deleted.
- Added per-user/order payment `Idempotency-Key` handling with locked order updates and duplicate-payment protection.
- Added order request fingerprints so a repeated key/request replays the original order and a repeated key with a different request returns `409 Conflict`.

### Repair verification

- `mvn -f backend/pom.xml -Dtest=OrderServiceTest,CartServiceTest,DomainControllerTest,AddressConcurrencyTest test`: 15 tests, 0 failures, 0 errors.
- `mvn -f backend/pom.xml test`: 25 tests, 0 failures, 0 errors.
- `git diff --check`: passed with no output.

### Repair commit

Pending at report creation; see the final commit recorded below after staging.

## Repair round 2

- Replaced in-memory entity stock mutation with a transactional conditional update (`stock >= requested`) and aggregate per-dish quantities before decrementing. A failed order rolls the full transaction back.
- Initialized order timeline and option snapshots in the read transaction so `GET /api/orders/{id}` can build its DTO after the transaction closes.
- Exposed menu options through an explicit dish response DTO; no catalog entity is serialized directly.
- Added real concurrent tests for duplicate order creation, last-item inventory reservation, and payment-key replay; strengthened the default-address race test and added an HTTP order-detail snapshot regression test.

### Repair round 2 verification

- `mvn -f backend/pom.xml -Dtest=CartServiceTest,OrderServiceTest,DomainControllerTest,AddressConcurrencyTest,OrderIdempotencyConcurrencyTest test`: 20 tests, 0 failures, 0 errors.
- `mvn -f backend/pom.xml test`: 30 tests, 0 failures, 0 errors.
- `git diff --check`: passed with no output.

### Repair round 2 commit

`fix: harden food ordering domain concurrency`
