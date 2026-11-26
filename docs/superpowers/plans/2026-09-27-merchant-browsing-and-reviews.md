# Merchant Browsing and Reviews Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让首页与商家 Tab 分页展示后端演示商家，按真实商家 ID 打开对应菜单，并在商家详情中提供后端驱动的只读评价分页。

**Architecture:** 后端以稳定排序的 Spring `Page` 提供商家与评价数据，并通过幂等种子同步补齐评价。Android 使用独立的 `MerchantFeedViewModel` 管理首页和商家列表分页，活动级 `OrderingViewModel` 负责按 ID 选择商家和跨商家购物车确认，详情级 `MerchantReviewViewModel` 管理评价分页。

**Tech Stack:** Java 17、Spring Boot 3.4.5、Spring Data JPA、Flyway、JUnit 5、Kotlin、Android Views/ViewBinding、StateFlow、Kotlin Coroutines、JUnit 4、MockWebServer、Maestro。

**Spec:** `docs/superpowers/specs/2026-09-27-merchant-browsing-and-reviews-design.md`

## Global Constraints

- 首页标题必须为“好店推荐”，不得展示距离、附近或定位准确度文案。
- Android 商家分页固定 `size=4`，仅请求营业商家，按商家 ID 升序稳定分页。
- 首页与商家 Tab 的分页状态和滚动位置相互独立。
- 商家详情不得使用“清粥小菜”或静态菜单作为缺省数据。
- 评价接口固定按 `created_at DESC, id DESC` 排序；Android 每页请求 10 条。
- 评价只读，不提供发布、修改或删除入口。
- 保留跨商家切换时清空购物车的确认流程。
- Android 实施与 Maestro 验收前先卸载 `com.fandazi.app` 和 `com.dirror.music`，再安装本次最新 debug APK。
- 每个任务只提交该任务列出的文件，不夹带当前工作区中的其他修改或测试产物。

---

## File Map

### Backend

- `backend/src/main/java/com/fandazi/feature/catalog/MerchantController.java`：商家详情、稳定分页和评价分页 HTTP 接口。
- `backend/src/main/java/com/fandazi/feature/catalog/MerchantRepository.java`：商家排序查询。
- `backend/src/main/java/com/fandazi/feature/catalog/MerchantReviewEntity.java`：评价持久化实体。
- `backend/src/main/java/com/fandazi/feature/catalog/MerchantReviewRepository.java`：按商家分页读取评价和幂等种子查重。
- `backend/src/main/java/com/fandazi/feature/catalog/MerchantReviewResponse.java`：评价响应 DTO。
- `backend/src/main/resources/db/migration/V5__create_merchant_reviews.sql`：生产数据库表、外键和索引。
- `backend/src/test/java/com/fandazi/feature/catalog/MerchantCatalogControllerTest.java`：商家详情、分页和评价接口测试。
- `backend/src/main/java/com/fandazi/seed/SeedDataLoader.java`：每次启动均执行评价的增量幂等同步。
- `backend/src/test/java/com/fandazi/seed/SeedDataLoaderTest.java`：已有数据库补齐评价和重复运行不重复测试。
- `data/seed/reviews.json`：12 家演示商家的只读评价夹具。
- `data/seed/README.md`：评价夹具格式说明。

### Android

- `android-app/app/src/main/java/com/fandazi/app/feature/ordering/OrderingModels.kt`：`PagedResult<T>` 与 `MerchantReview`。
- `android-app/app/src/main/java/com/fandazi/app/feature/ordering/OrderingRepository.kt`：商家页、单商家、菜单和评价页网络协议。
- `android-app/app/src/test/java/com/fandazi/app/feature/ordering/OrderingRepositoryTest.kt`：URL 与 JSON 映射测试。
- `android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantFeedViewModel.kt`：首页/商家列表复用的独立分页状态机。
- `android-app/app/src/test/java/com/fandazi/app/feature/merchant/MerchantFeedViewModelTest.kt`：首屏、追加、去重、失败和末页测试。
- `android-app/app/src/main/java/com/fandazi/app/feature/ordering/OrderingViewModel.kt`：按 ID 获取商家、切换确认、菜单竞态保护和详情导航事件。
- `android-app/app/src/test/java/com/fandazi/app/feature/ordering/OrderingViewModelTest.kt`：真实 ID、无缺省商家、切换确认和竞态测试。
- `android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantCardBinder.kt`：首页和商家列表统一卡片渲染，不生成距离数据。
- `android-app/app/src/main/java/com/fandazi/app/feature/home/HomeFragment.kt`：首页分页加载与商家选择。
- `android-app/app/src/main/res/layout/fragment_home.xml`：动态推荐列表和分页状态容器。
- `android-app/app/src/main/res/layout/item_home_merchant.xml`：移除距离控件，展示真实配送字段。
- `android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantListFragment.kt`：底部商家 Tab 的列表页。
- `android-app/app/src/main/res/layout/fragment_merchant_list.xml`：商家列表、首屏/追加错误和末页状态。
- `android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantDetailFragment.kt`：从原详情页拆出菜单/评价两种内容。
- `android-app/app/src/main/res/layout/fragment_merchant_detail.xml`：详情加载、菜单、评价和购物车区域。
- `android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantReviewViewModel.kt`：当前商家的只读评价分页。
- `android-app/app/src/main/res/layout/item_merchant_review.xml`：评价卡片。
- `android-app/app/src/main/res/navigation/nav_graph.xml`：`merchantFragment` 指向列表，新增 `merchantDetailFragment`。
- `android-app/app/src/main/java/com/fandazi/app/MainActivity.kt`：详情目标归类和底栏选中逻辑。
- `android-app/app/src/main/java/com/fandazi/app/feature/search/SearchFragment.kt`：搜索结果复用统一详情导航事件。
- `android-app/app/src/main/res/values/strings.xml`：推荐、分页、错误和评价文案。
- `maestro/ticket-09-merchant-pagination-reviews.yaml`：完整用户路径验收。
- `maestro/README.md`：新增流程运行说明。

---

### Task 1: Stable Merchant Paging and Lookup API

**Files:**
- Modify: `backend/src/main/java/com/fandazi/feature/catalog/MerchantController.java`
- Modify: `backend/src/main/java/com/fandazi/feature/catalog/MerchantRepository.java`
- Create: `backend/src/test/java/com/fandazi/feature/catalog/MerchantCatalogControllerTest.java`

**Interfaces:**
- Produces: `GET /api/merchants?page={page}&size={size}&openOnly={boolean}` returning `Page<MerchantResponse>` sorted by `id ASC`.
- Produces: `GET /api/merchants/{id}` returning one `MerchantResponse` or HTTP 404.
- Preserves: existing `keyword`, `category`, `maxDeliveryMinutes` and `maxPrice` filters.

- [ ] **Step 1: Write failing MVC tests for stable open-only paging and lookup**

```java
@Test
void pagesOpenMerchantsInStableIdOrder() throws Exception {
    merchantRepository.save(new MerchantEntity("关门店", "快餐", TEN, ONE, 20, false));
    var first = merchantRepository.save(new MerchantEntity("甲店", "快餐", TEN, ONE, 20, true));
    var second = merchantRepository.save(new MerchantEntity("乙店", "快餐", TEN, ONE, 20, true));

    mockMvc.perform(get("/api/merchants").param("page", "0").param("size", "1").param("openOnly", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(first.getId()))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.last").value(false));
    mockMvc.perform(get("/api/merchants").param("page", "1").param("size", "1").param("openOnly", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(second.getId()));
}

@Test
void getsMerchantByIdAndRejectsMissingId() throws Exception {
    var merchant = merchantRepository.save(new MerchantEntity("甲店", "快餐", TEN, ONE, 20, true));
    mockMvc.perform(get("/api/merchants/{id}", merchant.getId()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("甲店"));
    mockMvc.perform(get("/api/merchants/{id}", Long.MAX_VALUE))
        .andExpect(status().isNotFound());
}
```

- [ ] **Step 2: Run the focused tests and verify the new contract fails**

Run: `cd backend && mvn -Dtest=MerchantCatalogControllerTest test`

Expected: FAIL because `openOnly` and `GET /{id}` are not implemented and current results are not explicitly sorted.

- [ ] **Step 3: Implement stable filtering and ID lookup**

Use `Sort.by(Sort.Direction.ASC, "id")` before applying filters, add `@RequestParam(defaultValue = "false") boolean openOnly`, and include `(!openOnly || merchant.isOpen())` in `matches`. Add:

```java
@GetMapping("/{id}")
public ResponseEntity<MerchantResponse> merchant(@PathVariable Long id) {
    return merchants.findById(id)
        .map(MerchantResponse::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
}
```

- [ ] **Step 4: Run catalog and existing controller tests**

Run: `cd backend && mvn -Dtest=MerchantCatalogControllerTest,DomainControllerTest,AgentGatewayTest test`

Expected: PASS; existing search filters remain compatible.

- [ ] **Step 5: Commit the merchant API slice**

```text
git add backend/src/main/java/com/fandazi/feature/catalog/MerchantController.java backend/src/main/java/com/fandazi/feature/catalog/MerchantRepository.java backend/src/test/java/com/fandazi/feature/catalog/MerchantCatalogControllerTest.java
git commit -m "feat: add stable merchant paging"
```

### Task 2: Read-only Merchant Reviews API

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__create_merchant_reviews.sql`
- Create: `backend/src/main/java/com/fandazi/feature/catalog/MerchantReviewEntity.java`
- Create: `backend/src/main/java/com/fandazi/feature/catalog/MerchantReviewRepository.java`
- Create: `backend/src/main/java/com/fandazi/feature/catalog/MerchantReviewResponse.java`
- Modify: `backend/src/main/java/com/fandazi/feature/catalog/MerchantController.java`
- Modify: `backend/src/test/java/com/fandazi/feature/catalog/MerchantCatalogControllerTest.java`

**Interfaces:**
- Produces: `Page<MerchantReviewResponse> reviews(Long merchantId, int page, int size)`.
- Produces: repository method `Page<MerchantReviewEntity> findByMerchantId(Long merchantId, Pageable pageable)`.
- Produces: `GET /api/merchants/{merchantId}/reviews?page=0&size=10`.

- [ ] **Step 1: Add failing review endpoint tests**

Create reviews for two merchants, then assert the endpoint returns only the requested merchant, orders equal timestamps by descending ID, reports page metadata, validates `size`, and returns 404 for a missing merchant.

```java
mockMvc.perform(get("/api/merchants/{id}/reviews", merchant.getId()).param("page", "0").param("size", "1"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.content[0].reviewerName").value("小饭"))
    .andExpect(jsonPath("$.content[0].rating").value(4.8))
    .andExpect(jsonPath("$.totalElements").value(2));
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `cd backend && mvn -Dtest=MerchantCatalogControllerTest test`

Expected: FAIL because the table, entity, repository and endpoint do not exist.

- [ ] **Step 3: Add the Flyway migration**

```sql
CREATE TABLE merchant_reviews (
    id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT NOT NULL,
    reviewer_name VARCHAR(80) NOT NULL,
    rating DECIMAL(2,1) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_merchant_reviews_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id),
    CONSTRAINT uk_merchant_review_seed UNIQUE (merchant_id, reviewer_name, created_at)
);
CREATE INDEX idx_merchant_reviews_page ON merchant_reviews (merchant_id, created_at DESC, id DESC);
```

- [ ] **Step 4: Implement entity, response, repository and controller method**

The repository call must use `PageRequest.of(page, size, Sort.by(desc("createdAt"), desc("id")))`; controller bounds are `@Min(0)` for page and `@Min(1) @Max(100)` for size. Map every field named in the spec without embedding seed-only fields in the response.

```java
public interface MerchantReviewRepository extends JpaRepository<MerchantReviewEntity, Long> {
    Page<MerchantReviewEntity> findByMerchantId(Long merchantId, Pageable pageable);
    boolean existsByMerchantIdAndReviewerNameAndCreatedAt(Long merchantId, String reviewerName, Instant createdAt);
}

public record MerchantReviewResponse(
    Long id, Long merchantId, String reviewerName,
    BigDecimal rating, String content, Instant createdAt
) {
    static MerchantReviewResponse from(MerchantReviewEntity value) {
        return new MerchantReviewResponse(value.getId(), value.getMerchant().getId(),
            value.getReviewerName(), value.getRating(), value.getContent(), value.getCreatedAt());
    }
}

@GetMapping("/{merchantId}/reviews")
public ResponseEntity<Page<MerchantReviewResponse>> reviews(
        @PathVariable Long merchantId,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
    if (!merchants.existsById(merchantId)) return ResponseEntity.notFound().build();
    Pageable pageable = PageRequest.of(page, size,
        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    return ResponseEntity.ok(reviews.findByMerchantId(merchantId, pageable).map(MerchantReviewResponse::from));
}
```

- [ ] **Step 5: Run endpoint and Flyway validation tests**

Run: `cd backend && mvn -Dtest=MerchantCatalogControllerTest,FlywayAgentSchemaValidationTest test`

Expected: PASS.

- [ ] **Step 6: Commit the review API slice**

```text
git add backend/src/main/resources/db/migration/V5__create_merchant_reviews.sql backend/src/main/java/com/fandazi/feature/catalog/MerchantReviewEntity.java backend/src/main/java/com/fandazi/feature/catalog/MerchantReviewRepository.java backend/src/main/java/com/fandazi/feature/catalog/MerchantReviewResponse.java backend/src/main/java/com/fandazi/feature/catalog/MerchantController.java backend/src/test/java/com/fandazi/feature/catalog/MerchantCatalogControllerTest.java
git commit -m "feat: add read-only merchant reviews api"
```

### Task 3: Idempotent Review Seed Reconciliation

**Files:**
- Create: `data/seed/reviews.json`
- Modify: `data/seed/README.md`
- Modify: `backend/src/main/java/com/fandazi/seed/SeedDataLoader.java`
- Modify: `backend/src/test/java/com/fandazi/seed/SeedDataLoaderTest.java`

**Interfaces:**
- Consumes: `MerchantReviewRepository` and `MerchantReviewEntity` from Task 2.
- Produces: private `syncReviews(JsonNode fixtures)` that runs on both first seed and existing `seed_alice` databases.
- Fixture key: `{merchant, reviewerName, rating, content, createdAt}`.

- [ ] **Step 1: Extend fixture-copy and cleanup code, then write failing seed tests**

Assert all 12 merchant names appear in `reviews.json`, each has 3–5 rows, a database containing `seed_alice` but no reviews gets repaired, and running `loader.run(...)` twice leaves the review count unchanged.

```java
int firstCount = reviewRepository.findAll().size();
loader.run(new DefaultApplicationArguments());
assertThat(reviewRepository.findAll()).hasSize(firstCount);
assertThat(reviewRepository.findAll().stream().collect(groupingBy(r -> r.getMerchant().getName())))
    .allSatisfy((name, rows) -> assertThat(rows).hasSizeBetween(3, 5));
```

- [ ] **Step 2: Run seed tests and verify they fail**

Run: `cd backend && mvn -Dtest=SeedDataLoaderTest test`

Expected: FAIL because `reviews.json` and reconciliation do not exist.

- [ ] **Step 3: Add 3–5 deterministic demo reviews for each merchant**

Use every exact `name` from `data/seed/merchants.json`; use ISO-8601 timestamps and fictional nicknames. Keep content useful for visual verification by mentioning a menu trait such as portion, flavor or delivery packaging.

- [ ] **Step 4: Refactor seed flow so reviews always reconcile**

Read merchants into a `Map<String, MerchantEntity>`. On the existing-user early path, refresh dish images, call `syncReviews(read("reviews.json"), merchantsByName)`, then return. On first seed, call the same method after merchant creation. Before insert, use `existsByMerchantIdAndReviewerNameAndCreatedAt(...)`; reject unknown merchant names and ratings outside 1.0–5.0.

```java
private void syncReviews(JsonNode fixtures, Map<String, MerchantEntity> merchantsByName) {
    for (JsonNode fixture : fixtures) {
        MerchantEntity merchant = Optional.ofNullable(merchantsByName.get(requiredFixtureText(fixture, "merchant")))
            .orElseThrow(() -> new IllegalArgumentException("Review fixture references unknown merchant"));
        BigDecimal rating = fixture.path("rating").decimalValue();
        if (rating.compareTo(BigDecimal.ONE) < 0 || rating.compareTo(new BigDecimal("5.0")) > 0) {
            throw new IllegalArgumentException("Review rating must be between 1.0 and 5.0");
        }
        Instant createdAt = Instant.parse(requiredFixtureText(fixture, "createdAt"));
        String reviewer = requiredFixtureText(fixture, "reviewerName");
        if (!reviews.existsByMerchantIdAndReviewerNameAndCreatedAt(merchant.getId(), reviewer, createdAt)) {
            reviews.save(new MerchantReviewEntity(merchant, reviewer, rating,
                requiredFixtureText(fixture, "content"), createdAt));
        }
    }
}
```

- [ ] **Step 5: Run seed and complete backend tests**

Run: `cd backend && mvn test`

Expected: PASS with review rows unchanged after repeated seed runs.

- [ ] **Step 6: Commit seed reconciliation**

```text
git add data/seed/reviews.json data/seed/README.md backend/src/main/java/com/fandazi/seed/SeedDataLoader.java backend/src/test/java/com/fandazi/seed/SeedDataLoaderTest.java
git commit -m "feat: seed merchant reviews idempotently"
```

### Task 4: Android Paging and Review Network Contracts

**Files:**
- Modify: `android-app/app/src/main/java/com/fandazi/app/feature/ordering/OrderingModels.kt`
- Modify: `android-app/app/src/main/java/com/fandazi/app/feature/ordering/OrderingRepository.kt`
- Modify: `android-app/app/src/test/java/com/fandazi/app/feature/ordering/OrderingRepositoryTest.kt`

**Interfaces:**
- Produces: `data class PagedResult<T>(val content: List<T>, val number: Int, val last: Boolean, val totalElements: Long)`.
- Produces: `data class MerchantReview(val id: Long, val merchantId: Long, val reviewerName: String, val rating: BigDecimal, val content: String, val createdAt: String)`.
- Produces: `loadMerchants(page: Int, size: Int = 4, openOnly: Boolean = true): ApiResult<PagedResult<Merchant>>`.
- Produces: `loadMerchant(merchantId: Long): ApiResult<Merchant>`.
- Produces: `loadReviews(merchantId: Long, page: Int, size: Int = 10): ApiResult<PagedResult<MerchantReview>>`.

- [ ] **Step 1: Write failing MockWebServer contract tests**

Queue one merchant page, one merchant detail and one review page. Assert parsed `number`, `last`, `totalElements`, review timestamp/rating, and exact paths:

```kotlin
assertEquals("/api/merchants?page=0&size=4&openOnly=true", server.takeRequest().path)
assertEquals("/api/merchants/7", server.takeRequest().path)
assertEquals("/api/merchants/7/reviews?page=0&size=10", server.takeRequest().path)
```

- [ ] **Step 2: Run the focused unit test and verify it fails**

Run: `cd android-app && ./gradlew.bat testDebugUnitTest --tests com.fandazi.app.feature.ordering.OrderingRepositoryTest`

Expected: FAIL because the methods and models are missing.

- [ ] **Step 3: Implement one generic page parser and the three repository calls**

Add `JsonObject.toPage(itemParser)` so both merchant and review pages parse the same metadata. Keep `searchMerchants(keyword)` source-compatible by returning `loadMerchants`-style parsed content from `merchants?keyword=...`; do not silently apply `openOnly` to search.

```kotlin
private fun <T> JsonObject.toPage(parser: (JsonObject) -> T) = PagedResult(
    content = array("content").map { parser(it.asJsonObject) },
    number = int("number"),
    last = bool("last"),
    totalElements = long("totalElements")
)

override suspend fun loadMerchants(page: Int, size: Int, openOnly: Boolean) =
    apiClient.get("merchants?page=$page&size=$size&openOnly=$openOnly")
        .mapJson { JsonParser.parseString(it).asJsonObject.toPage(JsonObject::toMerchant) }

override suspend fun loadMerchant(merchantId: Long) =
    apiClient.get("merchants/$merchantId")
        .mapJson { JsonParser.parseString(it).asJsonObject.toMerchant() }

override suspend fun loadReviews(merchantId: Long, page: Int, size: Int) =
    apiClient.get("merchants/$merchantId/reviews?page=$page&size=$size")
        .mapJson { JsonParser.parseString(it).asJsonObject.toPage(JsonObject::toMerchantReview) }
```

- [ ] **Step 4: Run repository and API client tests**

Run: `cd android-app && ./gradlew.bat testDebugUnitTest --tests com.fandazi.app.feature.ordering.OrderingRepositoryTest --tests com.fandazi.app.core.network.ApiClientTest`

Expected: PASS.

- [ ] **Step 5: Commit Android network contracts**

```text
git add android-app/app/src/main/java/com/fandazi/app/feature/ordering/OrderingModels.kt android-app/app/src/main/java/com/fandazi/app/feature/ordering/OrderingRepository.kt android-app/app/src/test/java/com/fandazi/app/feature/ordering/OrderingRepositoryTest.kt
git commit -m "feat: add merchant paging network contracts"
```

### Task 5: Reusable Merchant Feed State Machine

**Files:**
- Create: `android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantFeedViewModel.kt`
- Create: `android-app/app/src/test/java/com/fandazi/app/feature/merchant/MerchantFeedViewModelTest.kt`

**Interfaces:**
- Consumes: `OrderingRepository.loadMerchants(page, 4, true)`.
- Produces: `MerchantFeedState(items, nextPage, isInitialLoading, isAppending, endReached, initialError, appendError)`.
- Produces: `loadInitial()`, `loadNext()`, `retryInitial()` and `retryAppend()`.

- [ ] **Step 1: Write failing coroutine tests for the complete state machine**

Cover: first page replaces content; second page appends and de-duplicates by ID; duplicate triggers while loading cause one repository call; first-page failure exposes only `initialError`; append failure retains items and exposes `appendError`; `last=true` prevents further calls.

```kotlin
viewModel.loadInitial()
advanceUntilIdle()
viewModel.loadNext()
viewModel.loadNext()
advanceUntilIdle()
assertEquals(listOf(1L, 2L, 3L), viewModel.state.value.items.map(Merchant::id))
assertTrue(viewModel.state.value.endReached)
```

- [ ] **Step 2: Run the focused tests and verify they fail**

Run: `cd android-app && ./gradlew.bat testDebugUnitTest --tests com.fandazi.app.feature.merchant.MerchantFeedViewModelTest`

Expected: FAIL because the ViewModel does not exist.

- [ ] **Step 3: Implement the minimal StateFlow pager and factory**

Use one `Job?` guard and a monotonically increasing `generation` incremented by `loadInitial`; discard a result when its captured generation differs. Set `nextPage = result.number + 1`, `endReached = result.last`, and de-duplicate with `(old + new).distinctBy(Merchant::id)`.

```kotlin
data class MerchantFeedState(
    val items: List<Merchant> = emptyList(),
    val nextPage: Int = 0,
    val isInitialLoading: Boolean = false,
    val isAppending: Boolean = false,
    val endReached: Boolean = false,
    val initialError: String? = null,
    val appendError: String? = null
)

fun loadNext() {
    val current = state.value
    if (requestJob?.isActive == true || current.endReached || current.items.isEmpty()) return
    requestPage(current.nextPage, append = true, generation = generation)
}

private fun accept(page: PagedResult<Merchant>, append: Boolean) {
    _state.update { old -> old.copy(
        items = (if (append) old.items + page.content else page.content).distinctBy(Merchant::id),
        nextPage = page.number + 1,
        isInitialLoading = false,
        isAppending = false,
        endReached = page.last,
        initialError = null,
        appendError = null
    ) }
}
```

- [ ] **Step 4: Run feed tests**

Run: `cd android-app && ./gradlew.bat testDebugUnitTest --tests com.fandazi.app.feature.merchant.MerchantFeedViewModelTest`

Expected: PASS.

- [ ] **Step 5: Commit the paging state machine**

```text
git add android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantFeedViewModel.kt android-app/app/src/test/java/com/fandazi/app/feature/merchant/MerchantFeedViewModelTest.kt
git commit -m "feat: add merchant feed state machine"
```

### Task 6: Reliable Merchant Selection by ID

**Files:**
- Modify: `android-app/app/src/main/java/com/fandazi/app/feature/ordering/OrderingViewModel.kt`
- Modify: `android-app/app/src/test/java/com/fandazi/app/feature/ordering/OrderingViewModelTest.kt`

**Interfaces:**
- Consumes: `OrderingRepository.loadMerchant(merchantId)` and `loadMenu(merchantId)`.
- Produces: `SharedFlow<MerchantNavigationEvent>` with `OpenDetail(merchantId)`.
- Preserves: `openMerchant(Long)`, `confirmMerchantSwitch()` and `cancelMerchantSwitch()` public calls.

- [ ] **Step 1: Update the fake repository and write failing selection tests**

Test opening an ID absent from search state, clearing the previous menu immediately, requesting merchant then menu, emitting navigation only after selection, keeping `selectedMerchant=null` on lookup failure, and ignoring a slow response from merchant A after merchant B completes.

```kotlin
viewModel.openMerchant(2)
advanceUntilIdle()
assertEquals(2L, viewModel.uiState.value.selectedMerchant?.id)
assertEquals(setOf(2L), viewModel.uiState.value.menu.map(Dish::merchantId).toSet())
assertEquals(MerchantNavigationEvent.OpenDetail(2), events.single())
```

- [ ] **Step 2: Run the focused tests and verify old behavior fails**

Run: `cd android-app && ./gradlew.bat testDebugUnitTest --tests com.fandazi.app.feature.ordering.OrderingViewModelTest`

Expected: FAIL because `openMerchant` currently searches only `uiState.merchants` and has no navigation event or request generation.

- [ ] **Step 3: Implement ID lookup, cart confirmation and race protection**

At call start clear `menu`, `selectedDish` and prior error, then fetch the merchant. If another cart owns the merchant ID, store the fetched merchant in `pendingMerchantSwitch`; otherwise set `selectedMerchant`, load its menu, and emit `OpenDetail(id)`. Increment `merchantGeneration` for every open/cancel/switch operation and check it before each state update. `confirmMerchantSwitch` clears the cart and continues with the already fetched pending merchant.

```kotlin
sealed interface MerchantNavigationEvent {
    data class OpenDetail(val merchantId: Long) : MerchantNavigationEvent
}

fun openMerchant(merchantId: Long) {
    val request = ++merchantGeneration
    _uiState.update { it.copy(menu = emptyList(), selectedDish = null, isLoading = true, error = null) }
    viewModelScope.launch(dispatcher) {
        when (val result = repository.loadMerchant(merchantId)) {
            is ApiResult.Success -> if (request == merchantGeneration) prepareMerchant(result.value, request)
            is ApiResult.Failure -> if (request == merchantGeneration) showError(result.error)
        }
    }
}

private suspend fun prepareMerchant(merchant: Merchant, request: Long) {
    if (_uiState.value.cartDraft?.merchantId?.let { it != merchant.id } == true) {
        _uiState.update { it.copy(pendingMerchantSwitch = merchant, isLoading = false) }
        return
    }
    _uiState.update { it.copy(selectedMerchant = merchant, pendingMerchantSwitch = null) }
    _navigationEvents.emit(MerchantNavigationEvent.OpenDetail(merchant.id))
    loadMenuFor(merchant.id, request)
}
```

- [ ] **Step 4: Run ordering tests**

Run: `cd android-app && ./gradlew.bat testDebugUnitTest --tests com.fandazi.app.feature.ordering.OrderingViewModelTest`

Expected: PASS, including existing checkout and payment tests.

- [ ] **Step 5: Commit merchant selection**

```text
git add android-app/app/src/main/java/com/fandazi/app/feature/ordering/OrderingViewModel.kt android-app/app/src/test/java/com/fandazi/app/feature/ordering/OrderingViewModelTest.kt
git commit -m "fix: select merchant reliably by id"
```

### Task 7: Paginated Home Recommendations

**Files:**
- Create: `android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantCardBinder.kt`
- Modify: `android-app/app/src/main/java/com/fandazi/app/feature/home/HomeFragment.kt`
- Modify: `android-app/app/src/main/res/layout/fragment_home.xml`
- Modify: `android-app/app/src/main/res/layout/item_home_merchant.xml`
- Modify: `android-app/app/src/main/res/values/strings.xml`
- Modify: `android-app/app/src/test/java/com/fandazi/app/feature/home/HomePresentationTest.kt`

**Interfaces:**
- Consumes: fragment-scoped `MerchantFeedViewModel` and activity-scoped `OrderingViewModel`.
- Produces: `MerchantCardBinder.bind(view, merchant, onClick)` with no synthetic distance or rating.

- [ ] **Step 1: Add failing presentation assertions for copy and card fields**

Assert the section title equals “好店推荐”, subtitle has no “地址/附近/距离”, and card text is derived from `category`, `minimumOrder`, `deliveryFee`, `deliveryMinutes` and `open` only.

- [ ] **Step 2: Run the home presentation test and verify it fails**

Run: `cd android-app && ./gradlew.bat testDebugUnitTest --tests com.fandazi.app.feature.home.HomePresentationTest`

Expected: FAIL because current copy and cards contain nearby/distance data.

- [ ] **Step 3: Replace the three includes with a dynamic container and explicit states**

Keep `home_scroll`; add `home_merchant_list`, `home_merchant_initial_status`, `home_merchant_append_status`, and `home_merchant_end`. Remove `merchant_distance` from `item_home_merchant.xml`; render “起送 ¥X · 配送 ¥Y · N 分钟送达”.

```xml
<LinearLayout
    android:id="@+id/home_merchant_list"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical" />
<TextView
    android:id="@+id/home_merchant_initial_status"
    android:layout_width="match_parent"
    android:layout_height="80dp"
    android:gravity="center"
    android:visibility="gone" />
<TextView
    android:id="@+id/home_merchant_append_status"
    android:layout_width="match_parent"
    android:layout_height="48dp"
    android:gravity="center"
    android:visibility="gone" />
<TextView
    android:id="@+id/home_merchant_end"
    android:layout_width="match_parent"
    android:layout_height="48dp"
    android:gravity="center"
    android:text="@string/list_end"
    android:visibility="gone" />
```

```kotlin
object MerchantCardBinder {
    fun bind(binding: ItemHomeMerchantBinding, merchant: Merchant, onClick: (Long) -> Unit) {
        binding.merchantName.text = merchant.name
        binding.merchantMeta.text = "起送 ¥${merchant.minimumOrder} · 配送 ¥${merchant.deliveryFee} · ${merchant.deliveryMinutes} 分钟送达"
        binding.merchantTags.text = merchant.category
        binding.root.setOnClickListener { onClick(merchant.id) }
    }
}
```

- [ ] **Step 4: Bind scrolling, retry and selection**

On first view creation call `feedViewModel.loadInitial()`. In `homeScroll.setOnScrollChangeListener`, call `loadNext()` when remaining scroll distance is at most 240dp. Re-render only when the merchant ID list changes. Clicking a card calls `orderingViewModel.openMerchant(id)`; collect `navigationEvents` and navigate to `merchantDetailFragment`; show the existing cart-switch dialog and navigate only after positive confirmation produces the event.

```kotlin
binding.homeScroll.setOnScrollChangeListener { scroll, _, y, _, _ ->
    val child = scroll.getChildAt(0)
    if (child.bottom - (scroll.height + y) <= 240.dp) feedViewModel.loadNext()
}

orderingViewModel.navigationEvents.collect { event ->
    if (event is MerchantNavigationEvent.OpenDetail && findNavController().currentDestination?.id == R.id.homeFragment) {
        findNavController().navigate(R.id.merchantDetailFragment)
    }
}
```

- [ ] **Step 5: Run home and ordering unit tests, then assemble debug**

Run: `cd android-app && ./gradlew.bat testDebugUnitTest --tests com.fandazi.app.feature.home.HomePresentationTest --tests com.fandazi.app.feature.ordering.OrderingViewModelTest assembleDebug`

Expected: PASS and debug APK produced.

- [ ] **Step 6: Commit home recommendations**

```text
git add android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantCardBinder.kt android-app/app/src/main/java/com/fandazi/app/feature/home/HomeFragment.kt android-app/app/src/main/res/layout/fragment_home.xml android-app/app/src/main/res/layout/item_home_merchant.xml android-app/app/src/main/res/values/strings.xml android-app/app/src/test/java/com/fandazi/app/feature/home/HomePresentationTest.kt
git commit -m "feat: paginate home merchant recommendations"
```

### Task 8: Merchant Tab List and Detail Navigation Split

**Files:**
- Create: `android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantListFragment.kt`
- Create: `android-app/app/src/main/res/layout/fragment_merchant_list.xml`
- Rename: `android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantFragment.kt` to `MerchantDetailFragment.kt`
- Rename: `android-app/app/src/main/res/layout/fragment_merchant.xml` to `fragment_merchant_detail.xml`
- Modify: `android-app/app/src/main/res/navigation/nav_graph.xml`
- Modify: `android-app/app/src/main/java/com/fandazi/app/MainActivity.kt`
- Modify: `android-app/app/src/main/java/com/fandazi/app/feature/search/SearchFragment.kt`
- Create: `android-app/app/src/test/java/com/fandazi/app/feature/merchant/MerchantNavigationPresentationTest.kt`

**Interfaces:**
- `merchantFragment` remains the bottom-menu ID but points to `MerchantListFragment`.
- `merchantDetailFragment` points to `MerchantDetailFragment` and is never a bottom-menu item.
- Search, home and merchant list navigate only from `MerchantNavigationEvent.OpenDetail`.

- [ ] **Step 1: Write failing navigation presentation tests**

Parse `nav_graph.xml` and assert `merchantFragment` names `MerchantListFragment`, `merchantDetailFragment` exists, and no “清粥小菜” fallback remains in the detail source. Assert `MainActivity` hides the bottom bar on `merchantDetailFragment` while retaining the merchant Tab selection when returning.

- [ ] **Step 2: Run the navigation test and verify it fails**

Run: `cd android-app && ./gradlew.bat testDebugUnitTest --tests com.fandazi.app.feature.merchant.MerchantNavigationPresentationTest`

Expected: FAIL because the bottom Tab currently targets the detail fragment.

- [ ] **Step 3: Move the existing detail class/layout and add list screen**

The list screen uses its own fragment-scoped `MerchantFeedViewModel`, `NestedScrollView` and `LinearLayout`; it binds cards through `MerchantCardBinder`, loads at 240dp from the bottom, and exposes the same initial/append/retry/end states as Home.

```kotlin
class MerchantListFragment : Fragment(R.layout.fragment_merchant_list) {
    private val feedViewModel: MerchantFeedViewModel by viewModels {
        MerchantFeedViewModel.Factory((requireActivity() as MainActivity).orderingRepository())
    }
    private val orderingViewModel: OrderingViewModel by activityViewModels { orderingFactory() }
}
```

```xml
<androidx.core.widget.NestedScrollView
    android:id="@+id/merchant_list_scroll"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="商家"
            android:textSize="24sp"
            android:textStyle="bold" />
        <LinearLayout
            android:id="@+id/merchant_list_items"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical" />
        <TextView
            android:id="@+id/merchant_list_status"
            android:layout_width="match_parent"
            android:layout_height="48dp"
            android:gravity="center" />
    </LinearLayout>
</androidx.core.widget.NestedScrollView>
```

- [ ] **Step 4: Update navigation and selection consumers**

Change Search card click to call only `openMerchant(id)`. Collect `navigationEvents` with `repeatOnLifecycle(STARTED)` and navigate to `merchantDetailFragment`. Update `MainActivity` destination classification to include the detail as an authenticated route while showing the bottom bar only on home, merchant list, orders and profile.

- [ ] **Step 5: Run navigation/search tests and assemble**

Run: `cd android-app && ./gradlew.bat testDebugUnitTest --tests com.fandazi.app.feature.merchant.MerchantNavigationPresentationTest --tests com.fandazi.app.feature.search.SearchUiPresentationTest assembleDebug`

Expected: PASS.

- [ ] **Step 6: Commit the navigation split**

```text
git add android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantListFragment.kt android-app/app/src/main/res/layout/fragment_merchant_list.xml android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantDetailFragment.kt android-app/app/src/main/res/layout/fragment_merchant_detail.xml android-app/app/src/main/res/navigation/nav_graph.xml android-app/app/src/main/java/com/fandazi/app/MainActivity.kt android-app/app/src/main/java/com/fandazi/app/feature/search/SearchFragment.kt android-app/app/src/test/java/com/fandazi/app/feature/merchant/MerchantNavigationPresentationTest.kt
git add -u android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantFragment.kt android-app/app/src/main/res/layout/fragment_merchant.xml
git commit -m "feat: make merchant tab a paged list"
```

### Task 9: Read-only Paginated Reviews in Merchant Detail

**Files:**
- Create: `android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantReviewViewModel.kt`
- Create: `android-app/app/src/test/java/com/fandazi/app/feature/merchant/MerchantReviewViewModelTest.kt`
- Create: `android-app/app/src/main/res/layout/item_merchant_review.xml`
- Modify: `android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantDetailFragment.kt`
- Modify: `android-app/app/src/main/res/layout/fragment_merchant_detail.xml`
- Modify: `android-app/app/src/main/res/values/strings.xml`
- Modify: `android-app/app/src/test/java/com/fandazi/app/feature/merchant/MerchantMenuPresentationTest.kt`

**Interfaces:**
- Produces: `MerchantReviewState(merchantId, items, nextPage, isInitialLoading, isAppending, endReached, initialError, appendError)`.
- Produces: `load(merchantId)`, `loadNext()` and `retry()`; changing merchant ID clears old reviews and increments request generation.
- UI mode: `MerchantDetailTab.MENU` or `MerchantDetailTab.REVIEWS`.

- [ ] **Step 1: Write failing review ViewModel and tab presentation tests**

Cover lazy first load, pagination/ID de-duplication, stale merchant response rejection, initial retry, append retry, empty reviews, and tab visibility rules: reviews hide categories/menu/cart; menu restores them without another menu request.

- [ ] **Step 2: Run focused tests and verify they fail**

Run: `cd android-app && ./gradlew.bat testDebugUnitTest --tests com.fandazi.app.feature.merchant.MerchantReviewViewModelTest --tests com.fandazi.app.feature.merchant.MerchantMenuPresentationTest`

Expected: FAIL because review state and tab behavior are absent.

- [ ] **Step 3: Implement the review pager**

Use the same guards as `MerchantFeedViewModel`, but call `loadReviews(merchantId, page, 10)`. A repeated `load` with the same merchant ID and existing items is a no-op; a new ID clears all page state before requesting page 0.

```kotlin
data class MerchantReviewState(
    val merchantId: Long? = null,
    val items: List<MerchantReview> = emptyList(),
    val nextPage: Int = 0,
    val isInitialLoading: Boolean = false,
    val isAppending: Boolean = false,
    val endReached: Boolean = false,
    val initialError: String? = null,
    val appendError: String? = null
)

fun load(merchantId: Long) {
    if (state.value.merchantId == merchantId && (state.value.items.isNotEmpty() || state.value.isInitialLoading)) return
    generation += 1
    _state.value = MerchantReviewState(merchantId = merchantId, isInitialLoading = true)
    requestPage(merchantId, page = 0, append = false, requestGeneration = generation)
}
```

- [ ] **Step 4: Add explicit detail states and review rendering**

Remove fallback merchant name/meta/image. When `selectedMerchant` is null show “请先选择商家” and a button navigating up. During menu load show progress; on empty menu show “该商家暂时没有可售菜品”; on failure show the repository error and a retry calling `openMerchant(selectedMerchant.id)`. The review tab renders rating, fictional nickname, body and formatted date; its scroll listener loads the next page at 240dp remaining. No input or publish controls are present.

```kotlin
private fun selectTab(tab: MerchantDetailTab) = with(binding) {
    val reviews = tab == MerchantDetailTab.REVIEWS
    categoryList.isVisible = !reviews
    menuScroll.isVisible = !reviews
    cartContainer.isVisible = !reviews
    reviewScroll.isVisible = reviews
    if (reviews) viewModel.uiState.value.selectedMerchant?.id?.let(reviewViewModel::load)
}

private fun renderMerchant(state: OrderingUiState) {
    val merchant = state.selectedMerchant
    binding.missingMerchantGroup.isVisible = merchant == null
    binding.merchantContent.isVisible = merchant != null
    if (merchant == null) return
    binding.merchantName.text = merchant.name
    binding.menuEmpty.isVisible = !state.isLoading && state.error == null && state.menu.isEmpty()
}
```

- [ ] **Step 5: Run merchant tests and all Android unit tests**

Run: `cd android-app && ./gradlew.bat testDebugUnitTest`

Expected: PASS.

- [ ] **Step 6: Commit merchant reviews UI**

```text
git add android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantReviewViewModel.kt android-app/app/src/test/java/com/fandazi/app/feature/merchant/MerchantReviewViewModelTest.kt android-app/app/src/main/res/layout/item_merchant_review.xml android-app/app/src/main/java/com/fandazi/app/feature/merchant/MerchantDetailFragment.kt android-app/app/src/main/res/layout/fragment_merchant_detail.xml android-app/app/src/main/res/values/strings.xml android-app/app/src/test/java/com/fandazi/app/feature/merchant/MerchantMenuPresentationTest.kt
git commit -m "feat: show paginated merchant reviews"
```

### Task 10: Service Integration, Maestro Acceptance, and Full Regression

**Files:**
- Create: `maestro/ticket-09-merchant-pagination-reviews.yaml`
- Modify: `maestro/README.md`
- Update if required by discovered regression only: files already owned by Tasks 1–9.

**Interfaces:**
- Consumes: backend on port 8080, agent service on port 8090, latest debug APK and seeded database.
- Produces: one reproducible Maestro flow covering the complete ticket.

- [ ] **Step 1: Add a Maestro flow for the complete user path**

The flow must log in/enter as the existing test user, assert “好店推荐”, scroll until merchants from at least three pages are visible, open two different merchants and assert distinct merchant names plus visible dish cards, return and open the merchant Tab, assert it is a list, scroll and open a merchant, switch to “评价”, assert a seeded review body, scroll reviews, switch back to “点菜”, and assert a dish card returns. Add negative assertions for “附近好店”, “距离” and “添加地址后”.

- [ ] **Step 2: Run backend and Agent regression before device work**

Run: `cd backend && mvn test`

Run: `cd agent-service && python -m pytest -q`

Expected: both exit 0.

- [ ] **Step 3: Build the final Android debug APK**

Run: `cd android-app && ./gradlew.bat clean testDebugUnitTest assembleDebug`

Expected: `BUILD SUCCESSFUL` and `android-app/app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 4: Start services and verify health/data**

Run the repository start scripts in separate terminals, then verify `GET http://127.0.0.1:8080/api/merchants?page=0&size=4&openOnly=true` returns 4 rows and `GET /api/merchants/{id}/reviews?page=0&size=10` returns seeded reviews. Confirm the Agent health endpoint used by `start-fandazi-agent.ps1` responds before launching the app.

- [ ] **Step 5: Perform the mandatory clean emulator installation**

```text
adb uninstall com.fandazi.app
adb uninstall com.dirror.music
adb shell pm list packages com.fandazi.app
adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk
```

Expected: `com.fandazi.app` is absent before install. If `com.dirror.music` reports `DELETE_FAILED_INTERNAL_ERROR`, record the exact emulator response and verify it cannot overlay or launch during this flow before continuing.

- [ ] **Step 6: Run the new flow and then the complete Maestro suite**

Run: `maestro test maestro/ticket-09-merchant-pagination-reviews.yaml`

Run: `maestro test maestro`

Expected: every flow passes against the just-installed APK. Save failure screenshots/XML only under `.scratch/` and do not commit them.

- [ ] **Step 7: Inspect the final diff and run whitespace checks**

Run: `git diff --check`

Run: `git status --short`

Expected: no whitespace errors; only intended source, fixture, migration, test and Maestro files remain changed.

- [ ] **Step 8: Commit acceptance artifacts**

```text
git add maestro/ticket-09-merchant-pagination-reviews.yaml maestro/README.md
git commit -m "test: cover merchant paging and reviews"
```

- [ ] **Step 9: Record final verification evidence**

In the task handoff report, include exact backend, Agent, Android and Maestro commands with exit results; list any emulator-only warning separately. Do not claim completion if the ticket flow or full suite did not run successfully.
