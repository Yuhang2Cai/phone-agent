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

## Repair round 2 follow-up

- Moved order-create, order-detail, first-payment, and payment-idempotent-replay response mapping into active service transactions, preventing lazy `timeline` and `optionIds` collections from escaping into HTTP serialization.
- Added MockMvc coverage for first and replayed order creation and payment responses, including option snapshots and password-hash absence.
- Added CartService quantity bounds: positive quantities only, a per-line limit of 100, a duplicate-dish aggregate limit of 1,000, and long-based exact aggregation before the stock update.
- Added a real multi-dish rollback regression proving that an earlier stock reservation is restored when a later dish is unavailable.

### Repair round 2 follow-up verification

- `mvn -f backend/pom.xml test`: 34 tests, 0 failures, 0 errors.
- `git diff --check`: passed with no output.

### Repair round 2 follow-up commit

`fix: complete task 4 http and cart safeguards` (final commit recorded in repository history)

## Repair round 3

- Added server-owned merchant promotion rules using a threshold and fixed discount amount; cart quotes now calculate the discount and final total from persisted merchant data, ignoring client prices/discounts.
- Added the calculated discount to the immutable order amount snapshot and `OrderResponse`.
- Added Jakarta Bean Validation to address, cart-item, and order input DTOs, enabled `@Valid` on relevant controllers, and normalized body-validation failures to HTTP 400 with a stable error payload.
- Centralized adjacent order-state enforcement in `OrderEntity.advance`; delivery service and all internal transitions now use the same guard.
- Added HTTP regressions for invalid inputs, cross-user order isolation, and same-key/different-request conflicts, plus promotion and non-admission domain tests.

### Repair round 3 verification

- `mvn -f backend/pom.xml test`: 39 tests, 0 failures, 0 errors.
- `git diff --check`: passed with no output.

### Repair round 3 commit

Pending at report creation; final commit recorded in repository history.
