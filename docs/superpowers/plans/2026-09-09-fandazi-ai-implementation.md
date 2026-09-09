# 饭搭子 AI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an installable Android XML + MVVM food-ordering app with a Spring Boot backend and a constrained single-agent ordering workflow, supported by an editable HTML/CSS prototype and repeatable test data.

**Architecture:** The Android app owns presentation, local ASR, cached UI state, and user authorization. Spring Boot owns authentication, catalog, cart, addresses, orders, simulated payment, delivery state transitions, agent orchestration, tool authorization, and persistence. The `FoodOrderingAgent` uses a server-side allowlist of deterministic business tools; Android receives readable execution events over SSE.

**Tech Stack:** Kotlin, Android XML, MVVM, OkHttp, Glide, Room, Coroutines/Flow, Sherpa-ONNX Paraformer INT8, Java 17, Spring Boot, Spring Security/JWT, Spring Data JPA, MySQL, SSE, OpenAPI/Knife4j, HTML/CSS/JavaScript.

**Spec:** `docs/superpowers/specs/2026-09-09-fandazi-ai-design.md`

## Global Constraints

- The product is named “饭搭子 AI”.
- Android uses XML layouts, MVVM, OkHttp for HTTP, and Glide for images.
- The backend uses Java 17 and Spring Boot; the LLM key, base URL, and model name stay server-side.
- The first release uses one `FoodOrderingAgent`, not multiple agents.
- Default order submission requires an order confirmation card; automatic mode is bounded by address and amount rules.
- “按上次配置复购” locks the original merchant, dish, quantity, and options; unavailable items stop the flow rather than being replaced.
- Payment is simulated; post-payment delivery status is advanced by a backend state machine.
- Only the customer app is built. No merchant, rider, operations, real payment, map picker, or third-party App automation is included.
- The initial dataset is synthetic, repeatable, and includes normal and failure cases.
- The local ASR model is packaged in the APK and transcribes after recording ends.

---

### Task 1: Establish the workspace structure and build contracts

**Files:**
- Create: `README.md`
- Create: `.gitignore`
- Create: `ui-prototype/README.md`
- Create: `backend/README.md`
- Create: `android-app/README.md`
- Create: `data/seed/README.md`

**Interfaces:**
- Produces the top-level directories and the environment-variable contract consumed by later tasks.
- The backend exposes `http://localhost:8080/api`; the Android debug build uses a configurable base URL.

- [ ] **Step 1: Document local prerequisites and run commands**

Write README sections for Android Studio, JDK 17, MySQL, Node-free browser preview, backend environment variables (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`, `JWT_SECRET`), and the rule that secrets must not be committed.

- [ ] **Step 2: Add ignore rules**

Ignore `.gradle/`, `build/`, `.idea/`, `local.properties`, generated APKs, runtime logs, `.env`, and downloaded model archives while keeping checked-in model assets under `android-app/app/src/main/assets/`.

- [ ] **Step 3: Verify the structure**

Run `Get-ChildItem -Force` from the workspace root and confirm `android-app`, `backend`, `ui-prototype`, `data`, and `docs` exist.

- [ ] **Step 4: Commit**

```bash
git add README.md .gitignore ui-prototype/README.md backend/README.md android-app/README.md data/seed/README.md
git commit -m "chore: establish 饭搭子 AI workspace"
```

### Task 2: Build the editable HTML/CSS UI prototype

**Files:**
- Create: `ui-prototype/index.html`
- Create: `ui-prototype/styles.css`
- Create: `ui-prototype/app.js`
- Create: `ui-prototype/assets/README.md`

**Interfaces:**
- `app.js` exposes client-side state for `currentPage`, `cart`, `agentMode`, `orderStatus`, and `agentEvents`.
- Navigation targets are `login`, `home`, `search`, `merchant`, `checkout`, `payment`, `orders`, `order-detail`, `addresses`, `profile`, and `agent`.

- [ ] **Step 1: Write the prototype interaction checklist**

Add comments to `ui-prototype/README.md` listing the exact clickable acceptance flow: login → home → merchant → option picker → cart → checkout → payment → order detail, plus floating assistant → prompt → execution events → confirmation card.

- [ ] **Step 2: Create the phone shell and reusable visual tokens**

Define CSS variables for the bright yellow brand color, neutral backgrounds, AI blue-purple accent, card radius, spacing scale, and typography. Build a 390 x 844 phone viewport with bottom navigation and a global AI floating button.

- [ ] **Step 3: Add the core screens**

Render login/register, home, search results, merchant menu, checkout, mock payment, order list/detail, address management, profile, automation settings, and the AI assistant panel from data objects in `app.js`.

- [ ] **Step 4: Add deterministic prototype actions**

Implement `navigate(page)`, `addToCart(dishId, options)`, `changeQuantity(itemId, delta)`, `submitMockPayment()`, `advanceOrderStatus()`, `toggleAutomation()`, and `runAgentDemo(prompt)`. `runAgentDemo` must emit events in this order: intent parsed → searching merchants → checking dishes → drafting cart → validating order → waiting for confirmation.

- [ ] **Step 5: Add prototype acceptance checks**

Open `ui-prototype/index.html` in a browser and verify that all navigation buttons, quantity controls, option selections, assistant open/close behavior, confirmation card, and delivery timeline work without a backend.

- [ ] **Step 6: Commit**

```bash
git add ui-prototype
git commit -m "feat: add editable 饭搭子 AI ui prototype"
```

### Task 3: Create the Spring Boot backend foundation and authentication

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/fandazi/FandaziApplication.java`
- Create: `backend/src/main/java/com/fandazi/core/security/SecurityConfig.java`
- Create: `backend/src/main/java/com/fandazi/core/security/JwtService.java`
- Create: `backend/src/main/java/com/fandazi/feature/auth/*`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/com/fandazi/feature/auth/AuthControllerTest.java`

**Interfaces:**
- `POST /api/auth/register` accepts `{ "username": string, "password": string, "nickname": string }` and returns `{ "token": string, "user": UserSummary }`.
- `POST /api/auth/login` accepts `{ "username": string, "password": string }` and returns the same response shape.
- `GET /api/users/me` returns `UserSummary` for a valid bearer token.

- [ ] **Step 1: Write failing authentication tests**

Cover successful registration, duplicate username rejection, successful login, incorrect password rejection, and missing-token rejection. Assert HTTP status and response JSON fields.

- [ ] **Step 2: Run the tests and confirm failure**

Run `mvn -f backend/pom.xml -Dtest=AuthControllerTest test`. Expected result: tests fail because the Spring Boot application and endpoints do not yet exist.

- [ ] **Step 3: Implement the minimum authentication stack**

Add `UserEntity`, `UserRepository`, password hashing with BCrypt, `AuthService`, `AuthController`, JWT creation/validation, and a stateless `SecurityFilterChain`. Keep database and LLM credentials sourced from environment properties.

- [ ] **Step 4: Run the tests and confirm success**

Run the same Maven command and verify all authentication tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add backend authentication"
```

### Task 4: Implement catalog, addresses, cart, orders, simulated payment, and delivery state

**Files:**
- Create: `backend/src/main/java/com/fandazi/feature/catalog/*`
- Create: `backend/src/main/java/com/fandazi/feature/address/*`
- Create: `backend/src/main/java/com/fandazi/feature/cart/*`
- Create: `backend/src/main/java/com/fandazi/feature/order/*`
- Create: `backend/src/main/java/com/fandazi/feature/payment/*`
- Create: `backend/src/main/java/com/fandazi/feature/delivery/*`
- Test: `backend/src/test/java/com/fandazi/feature/order/OrderServiceTest.java`
- Test: `backend/src/test/java/com/fandazi/feature/cart/CartServiceTest.java`

**Interfaces:**
- `GET /api/merchants?keyword=&category=&maxDeliveryMinutes=&maxPrice=` returns paginated merchant summaries.
- `GET /api/merchants/{merchantId}/dishes` returns menu categories, dishes, options, availability, and image URLs.
- `GET/POST/PUT/DELETE /api/addresses` manages user-owned addresses; `PATCH /api/addresses/{id}/default` sets the default.
- `POST /api/cart/drafts`, `PUT /api/cart/drafts/{id}/items`, and `POST /api/cart/drafts/{id}/validate` manage candidate carts.
- `POST /api/orders` requires an idempotency key and returns an order with `PENDING_PAYMENT` status.
- `POST /api/orders/{id}/pay` changes payment to `PAID` and delivery to `MERCHANT_ACCEPTED`.
- `GET /api/orders/{id}` returns the latest order and delivery timeline.

- [ ] **Step 1: Write service tests for business invariants**

Cover owner-only address access, default-address uniqueness, unavailable dish rejection, price recalculation from database values, minimum order validation, idempotent order creation, duplicate payment rejection, and delivery transitions.

- [ ] **Step 2: Implement catalog and address entities/services**

Create `MerchantEntity`, `DishEntity`, `DishOptionEntity`, `AddressEntity`, repositories, DTOs, and controllers. Add server-side filtering for keyword, category, price, delivery time, rating, and availability.

- [ ] **Step 3: Implement cart draft validation**

Use `CartDraft` and `CartDraftItem` entities. On validation, reload every dish and option, reject unavailable items, calculate subtotal, delivery fee, discount, and total, then return an immutable validation snapshot.

- [ ] **Step 4: Implement order creation and idempotency**

Persist address and item snapshots in `Order` and `OrderItem`. Require `Idempotency-Key`; repeated requests with the same user and key return the original order without another insert.

- [ ] **Step 5: Implement simulated payment and scheduled delivery transitions**

Allow only `PENDING_PAYMENT → PAID`; reject repeated payment. A scheduled service advances `MERCHANT_ACCEPTED → PREPARING → RIDER_PICKED_UP → DELIVERING → DELIVERED` using timestamps, while every transition is persisted.

- [ ] **Step 6: Run the service tests**

Run `mvn -f backend/pom.xml -Dtest=OrderServiceTest,CartServiceTest test`. Expected result: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat: add food ordering domain"
```

### Task 5: Create and validate the synthetic test dataset

**Files:**
- Create: `data/seed/merchants.json`
- Create: `data/seed/dishes.json`
- Create: `data/seed/users.json`
- Create: `data/seed/addresses.json`
- Create: `data/seed/orders.json`
- Create: `data/seed/agent-scenarios.json`
- Create: `backend/src/main/resources/db/seed/SeedDataLoader.java`
- Create: `backend/src/test/java/com/fandazi/seed/SeedDataLoaderTest.java`

**Interfaces:**
- Seed files are deterministic UTF-8 JSON and load through `SeedDataLoader` only when the `seed.enabled` profile is active.
- `agent-scenarios.json` contains `name`, `userMessage`, `expectedTools`, `requiresConfirmation`, and `expectedOutcome`.

- [ ] **Step 1: Write seed validation tests**

Assert 12 merchants, 100–140 dishes, at least one option group per applicable dish, at least one unavailable dish, at least one price-change scenario, at least one default and invalid address, and at least one paid historical order.

- [ ] **Step 2: Author the dataset**

Create synthetic Chinese merchant and dish names across fast food, rice bowls, noodles, congee/soup, light meals, drinks, and regional snacks. Add ratings, fees, delivery times, discounts, spicy levels, garnish tags, allergy tags, and stock states.

- [ ] **Step 3: Add known Agent scenarios**

Include “30 元以内想吃辣的”, “按上次配置再来一单”, “我感冒了中午推荐清淡一点”, “不要香菜使用默认地址”, plus missing address, price increase, unavailable repeat item, amount over limit, and malformed tool argument cases.

- [ ] **Step 4: Run seed tests and load data locally**

Run `mvn -f backend/pom.xml -Dtest=SeedDataLoaderTest test`, then start the seed profile and verify the catalog count and user-owned history through the API.

- [ ] **Step 5: Commit**

```bash
git add data backend/src/main/resources/db/seed backend/src/test/java/com/fandazi/seed
git commit -m "test: add deterministic food ordering data"
```

### Task 6: Implement Android shell, authentication, networking, and image loading

**Files:**
- Create: `android-app/settings.gradle.kts`
- Create: `android-app/build.gradle.kts`
- Create: `android-app/app/src/main/AndroidManifest.xml`
- Create: `android-app/app/src/main/java/com/fandazi/app/core/network/*`
- Create: `android-app/app/src/main/java/com/fandazi/app/core/image/ImageLoader.kt`
- Create: `android-app/app/src/main/java/com/fandazi/app/feature/auth/*`
- Create: `android-app/app/src/main/res/layout/*`
- Test: `android-app/app/src/test/java/com/fandazi/app/feature/auth/*`

**Interfaces:**
- `ApiClient` wraps OkHttp and exposes suspend request functions with bearer-token injection and structured `ApiError` mapping.
- `SessionRepository` exposes `StateFlow<SessionState>` and `suspend login/register/logout`.
- `ImageLoader.load(ImageView, url, placeholder, error)` is the only feature-level image-loading entry point and delegates to Glide.

- [ ] **Step 1: Create failing ViewModel tests**

Test login success, invalid credentials, timeout mapping, logout clearing the token, and process recreation restoring the session from encrypted local storage or a protected preference wrapper.

- [ ] **Step 2: Create the Android XML/MVVM project shell**

Set up one Activity, Navigation component, Fragment destinations, ViewModel factories, lifecycle collection, and a shared theme. Use XML layouts only for screens.

- [ ] **Step 3: Implement the OkHttp client**

Configure connect/read/write timeouts, an interceptor for the JWT header, JSON serialization, one retry for idempotent GET requests, and response parsing that preserves server error codes.

- [ ] **Step 4: Implement Glide image loading**

Configure memory and disk caching, rounded image transformation, a local placeholder, and a local error drawable. Do not let image failure block merchant or dish text rendering.

- [ ] **Step 5: Implement login/register screens and session state**

Connect XML forms to ViewModels, show field errors, disable duplicate submissions, persist the token, and route authenticated users to Home.

- [ ] **Step 6: Run Android unit tests and compile**

Run `.\gradlew.bat testDebugUnitTest assembleDebug` from `android-app`. Expected result: PASS and a debug APK is produced.

- [ ] **Step 7: Commit**

```bash
git add android-app
git commit -m "feat: add android shell and network foundation"
```

### Task 7: Implement Android food flow, addresses, orders, and simulated payment

**Files:**
- Create: `android-app/app/src/main/java/com/fandazi/app/feature/home/*`
- Create: `android-app/app/src/main/java/com/fandazi/app/feature/catalog/*`
- Create: `android-app/app/src/main/java/com/fandazi/app/feature/cart/*`
- Create: `android-app/app/src/main/java/com/fandazi/app/feature/address/*`
- Create: `android-app/app/src/main/java/com/fandazi/app/feature/order/*`
- Create: `android-app/app/src/main/res/layout/*`
- Test: `android-app/app/src/test/java/com/fandazi/app/feature/order/*`

**Interfaces:**
- Each screen exposes a `UiState` data class through `StateFlow`; one-shot navigation and toast events use a separate event flow.
- `OrderRepository.createOrder(request, idempotencyKey)` returns `OrderDetail`; `pay(orderId)` returns the updated order.

- [ ] **Step 1: Write ViewModel tests**

Cover search loading/error/empty states, menu category selection, option validation, quantity changes, address selection, checkout total refresh, payment result, and order status refresh.

- [ ] **Step 2: Implement Home and Search**

Build yellow search header, categories, merchant cards, dish result cards, delivery metadata, Glide images, loading skeletons, empty state, and retry state.

- [ ] **Step 3: Implement Merchant Detail and Cart**

Use RecyclerView with menu sections, an option dialog, cart quantity controls, and a sticky cart summary. Persist draft cart state in Room when leaving the screen.

- [ ] **Step 4: Implement Address Management**

Build list, add, edit, delete, and set-default flows. Prevent deletion of the only required default address when an active automation policy depends on it.

- [ ] **Step 5: Implement Checkout and Mock Payment**

Render address, item snapshots, options, notes, delivery fee, discounts, and final total. Send an idempotency key, show the mock payment page, and route success to Order Detail.

- [ ] **Step 6: Implement Orders and status timeline**

Show active/history tabs and a detail timeline driven by backend status. Poll the detail endpoint while the order is not terminal; stop polling at `DELIVERED`, cancellation, or screen disposal.

- [ ] **Step 7: Run Android tests and manual flow**

Run unit tests, install the debug APK, and complete registration → search → add → checkout → payment → delivery timeline using seeded data.

- [ ] **Step 8: Commit**

```bash
git add android-app
git commit -m "feat: add android food ordering flow"
```

### Task 8: Integrate the local ASR model

**Files:**
- Create: `android-app/app/src/main/java/com/fandazi/app/feature/asr/SpeechRecognizer.kt`
- Create: `android-app/app/src/main/java/com/fandazi/app/feature/asr/AudioRecorder.kt`
- Create: `android-app/app/src/main/java/com/fandazi/app/feature/asr/AsrViewModel.kt`
- Create: `android-app/app/src/main/res/drawable/*`
- Copy: `redBook/app/libs/sherpa-onnx-1.12.39.aar` to `android-app/app/libs/`
- Copy: `redBook/app/libs/sherpa-onnx-classes.jar` to `android-app/app/libs/`
- Copy: `redBook/app/src/main/assets/sherpa-onnx/.../model.int8.onnx` and `tokens.txt` to `android-app/app/src/main/assets/sherpa-onnx/.../`
- Test: `android-app/app/src/test/java/com/fandazi/app/feature/asr/AsrViewModelTest.kt`

**Interfaces:**
- `SpeechRecognizer.transcribe(file: File): Result<String>` runs off the main thread.
- `AsrViewModel` exposes `AsrUiState.Idle`, `Recording`, `Recognizing`, `Success(text)`, and `Error(message)`.

- [ ] **Step 1: Add the Sherpa-ONNX dependencies and assets**

Copy only the model/runtime files from the existing redBook project after verifying checksums and file sizes. Do not copy unrelated chat code or configuration secrets.

- [ ] **Step 2: Write ASR state tests**

Cover permission denial, empty result, successful result, recognizer initialization failure, and retry after failure using a fake `SpeechRecognizer`.

- [ ] **Step 3: Implement recording and offline recognition**

Use a press-and-hold microphone action, save a temporary recording, decode to 16 kHz mono PCM, run the offline Paraformer recognizer on `Dispatchers.Default`, delete temporary audio after completion, and never submit an empty transcript.

- [ ] **Step 4: Connect ASR to the Agent input**

Return recognized text to the assistant input field; allow editing before sending. The initial release does not show partial transcripts or perform TTS.

- [ ] **Step 5: Build and verify APK size and function**

Run `.\gradlew.bat assembleDebug`, inspect the APK size, install it, record a Chinese food request, and confirm text is inserted into the assistant input.

- [ ] **Step 6: Commit**

```bash
git add android-app
git commit -m "feat: add offline chinese asr"
```

### Task 9: Implement the constrained single Agent and streaming events

**Files:**
- Create: `backend/src/main/java/com/fandazi/feature/agent/*`
- Create: `backend/src/main/java/com/fandazi/feature/automation/*`
- Create: `backend/src/main/java/com/fandazi/feature/preference/*`
- Test: `backend/src/test/java/com/fandazi/feature/agent/AgentOrchestratorTest.java`
- Test: `backend/src/test/java/com/fandazi/feature/automation/AutomationPolicyTest.java`

**Interfaces:**
- `POST /api/agent/sessions/{sessionId}/messages` accepts `{ "text": string, "mode": "CONFIRM" | "THIS_TASK_AUTO" }` and returns an event-stream identifier.
- `GET /api/agent/sessions/{sessionId}/events` streams `AgentEvent` records with `type`, `summary`, `toolName`, `status`, and optional `payload`.
- `AgentOrchestrator.handle(userId, sessionId, text, mode)` returns a terminal `AgentResult` after recording every event.
- Tools are server-side methods: `searchMerchants`, `searchDishes`, `getDishDetail`, `getUserPreferences`, `getDefaultAddress`, `getLastOrder`, `createCartDraft`, `updateCartDraft`, `validateCart`, `createOrder`, and `getOrderStatus`.

- [ ] **Step 1: Write failing Agent tests**

Test normal recommendation, missing-information clarification, confirmation-required order, task-scoped auto order under the amount limit, long-term policy validation, exact repeat-order behavior, unavailable repeat item termination, and rejection of an unregistered tool.

- [ ] **Step 2: Define typed tool contracts**

Create request/response records for every tool. Each write tool receives authenticated `userId`, `sessionId`, and an idempotency context from the orchestrator rather than accepting them from model-generated arguments.

- [ ] **Step 3: Implement deterministic policy gates**

Create `AutomationPolicyService` and `OrderAuthorizationService`. Enforce confirmation mode, one-task grants, long-term default address, maximum amount, current price, availability, and expiry before `createOrder`.

- [ ] **Step 4: Implement the model adapter and tool loop**

Use a provider-neutral server configuration (`LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`). Send tool schemas to the model, execute only registered tools, append validated tool results, cap the tool loop, and return a structured final response. Never expose hidden chain-of-thought.

- [ ] **Step 5: Implement preference analysis**

Extract candidate preferences from confirmed order history. Save only user-confirmed long-term preferences; preserve allergy and dietary restrictions with higher priority than ordinary taste preferences.

- [ ] **Step 6: Implement the health-context policy**

For statements such as “我感冒了”, constrain output to general meal filtering. Do not diagnose, prescribe, or claim food treats illness. Escalate concerning symptom descriptions to a safety message and do not auto-order.

- [ ] **Step 7: Implement SSE event publication**

Publish readable events for intent parsing, search, filtering, draft-cart changes, validation, confirmation required, order creation, payment, failure, and completion. Persist events so reconnecting clients can resume from the last event ID.

- [ ] **Step 8: Run Agent tests**

Run `mvn -f backend/pom.xml -Dtest=AgentOrchestratorTest,AutomationPolicyTest test`. Expected result: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend
git commit -m "feat: add constrained food ordering agent"
```

### Task 10: Integrate the Android Agent UI and end-to-end behavior

**Files:**
- Create: `android-app/app/src/main/java/com/fandazi/app/feature/agent/*`
- Create: `android-app/app/src/main/res/layout/fragment_agent_sheet.xml`
- Create: `android-app/app/src/main/res/layout/item_agent_event.xml`
- Create: `android-app/app/src/main/res/layout/item_order_confirmation.xml`
- Modify: Android host fragments to mount the floating assistant
- Test: `android-app/app/src/test/java/com/fandazi/app/feature/agent/AgentViewModelTest.kt`

**Interfaces:**
- `AgentViewModel` exposes `AgentUiState` containing messages, execution events, draft cart, confirmation card, authorization mode, and error state.
- `AgentRepository.sendMessage` starts a session request; `observeEvents` consumes SSE and maps reconnects to the last event ID.

- [ ] **Step 1: Write Agent ViewModel tests**

Cover opening/closing the half-sheet, expanding full-screen, sending text, ASR text insertion, rendering events, showing confirmation, confirming/canceling, and recovering after SSE disconnect.

- [ ] **Step 2: Build the floating assistant**

Add a global yellow/AI-accent floating button on Home, Search, Merchant, and Order screens. Hide it on authentication and mock payment screens.

- [ ] **Step 3: Build half-sheet and full-screen chat**

Render user/assistant messages, quick prompts, microphone input, execution event cards, retry actions, and the draft cart summary. Keep the underlying food page synchronized with Agent changes.

- [ ] **Step 4: Build the order confirmation card**

Show merchant, items, options, address, delivery fee, discounts, total, authorization mode, amount limit, and confirm/cancel actions. Disable confirm while validation is in progress.

- [ ] **Step 5: Connect auto modes**

Implement per-task authorization in the current session and long-term automation settings with default address and amount cap. Show a forced confirmation whenever the backend rejects automatic execution.

- [ ] **Step 6: Run the main end-to-end scenario**

Use the seeded account and send: “我感冒了，中午想吃清淡点，不要香菜，30 元以内，按默认地址帮我点。” Verify ASR/text input, event stream, recommendation, draft cart, confirmation card, simulated payment, and delivery timeline.

- [ ] **Step 7: Commit**

```bash
git add android-app
git commit -m "feat: integrate android food ordering agent"
```

### Task 11: Complete cross-layer tests, documentation, and portfolio evidence

**Files:**
- Create: `docs/api-and-agent.md`
- Create: `docs/architecture.md`
- Create: `docs/interview-notes.md`
- Create: `backend/src/test/java/com/fandazi/e2e/*`
- Create: `android-app/app/src/androidTest/java/com/fandazi/app/flow/*`
- Modify: `README.md`

**Interfaces:**
- The README provides one reproducible command sequence for seed data, backend startup, Android base URL configuration, APK build, and UI prototype preview.
- The evidence directory contains test output, screenshots, and the final demo recording location without committing secrets.

- [ ] **Step 1: Add backend integration tests**

Use an isolated test database or H2 profile to verify register → catalog → address → cart → order → payment → delivery status. Assert that ownership and idempotency rules hold across HTTP boundaries.

- [ ] **Step 2: Add Android flow tests**

Cover login, merchant search, option selection, cart, address selection, checkout, simulated payment, order status display, and AI confirmation using a fake API client.

- [ ] **Step 3: Add Agent scenario evaluation**

Run every record in `data/seed/agent-scenarios.json`; compare actual tool sequence, confirmation requirement, and outcome to the expected fields. Store results as a readable Markdown report.

- [ ] **Step 4: Add architecture and interview documentation**

Document Android MVVM boundaries, OkHttp and Glide choices, ASR packaging tradeoff, SSE event model, tool authorization, idempotency, single-Agent rationale, and why deterministic business rules remain outside the model.

- [ ] **Step 5: Run the complete verification set**

Run `mvn -f backend/pom.xml test`, `.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest assembleDebug`, and manually open the HTML prototype. Confirm no API keys, database passwords, or personal account data appear in tracked files.

- [ ] **Step 6: Build portfolio evidence**

Capture the UI prototype, Android APK home screen, ASR input, Agent execution events, confirmation card, mock payment, order timeline, and error recovery. Record one normal confirmation flow and one bounded automatic flow.

- [ ] **Step 7: Commit**

```bash
git add README.md docs backend/src/test android-app/app/src/androidTest
git commit -m "test: document and verify 饭搭子 AI"
```

## Execution Checkpoints

- Checkpoint A: Tasks 1–2 produce a navigable, editable UI prototype.
- Checkpoint B: Tasks 3–5 produce a seeded, independently testable backend.
- Checkpoint C: Tasks 6–8 produce an installable Android food app with offline ASR.
- Checkpoint D: Tasks 9–10 connect the constrained Agent to Android.
- Checkpoint E: Task 11 produces the verified APK, reports, documentation, and portfolio evidence.

Each checkpoint must pass its listed tests before the next checkpoint begins.
