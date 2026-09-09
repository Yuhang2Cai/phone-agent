# Task 5 Report

## Delivered

- Added deterministic, synthetic JSON fixtures for 12 merchants and 120 generated dishes, plus synthetic users, addresses, paid-order history, and nine Agent evaluation scenarios.
- Added a `seed.enabled`-gated `SeedDataLoader` that reads `seed.path` (default `../data/seed`) and uses the synthetic account marker to avoid duplicate loads or touching existing user data.
- Added `SeedDataLoaderTest` covering catalog quantity, option groups, unavailable dishes, price-change fixture, default/expired-address fixture, and paid historical order.

## Verification

- Red: `SeedDataLoaderTest` failed before implementation with expected merchant count `12` and actual `0`.
- Green: `mvn -f backend/pom.xml -Dtest=SeedDataLoaderTest test` (1 test, 0 failures).
- Full: `mvn -f backend/pom.xml test` (42 tests, 0 failures).

## Notes

- All accounts, addresses, phone numbers, password hashes, menu content, and local placeholder paths are synthetic. No remote image links are used.
- Address validity and rich dish metadata remain represented in the JSON fixtures for later Agent/domain work; this task does not introduce Agent, Android, or ASR behavior.

## Revision 1

- Replaced the seed password with a 60-character BCrypt hash for the synthetic-only demo account.
- Orders are now parsed from `orders.json`; the test verifies fixture key-independent item identity and computed total.
- Added seed-only `seed_dish_metadata` and `seed_address_metadata` entities/tables. Historical price, change flag, rating, spice level, ingredients, allergens, and address validity are persisted for later Agent queries without changing core business entities.
- Added tests for metadata recognition, repeated execution, category coverage, and `seed.enabled=false`.
- Revision verification:专项 3/3 and full backend 44/44 tests passed; `git diff --check` is clean.
