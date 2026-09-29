# 饭搭子 AI 团购化与独立 Agent 服务重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有外卖 App 重构为可运行的餐饮到店团购 App，并用 `D:\project\-Demo-master` 独立 Agent 服务替换手机项目旧 Agent Service。

**Architecture:** 先冻结跨仓库契约、团购核心模型和安全边界，再按浏览推荐、购买支付、券核销、售后支持、长期记忆五个纵向切片完成 Spring Boot、Agent 和 Android。Android 只连接 Spring Boot，Agent 只通过 Spring 白名单工具访问业务事实。

**Tech Stack:** Android XML + MVVM + OkHttp + Gson、Spring Boot 3.4.5 + Java 17 + JPA + Flyway + MySQL、Python + LangGraph + FastAPI + MySQL + Chroma、JUnit + pytest + Robolectric + MockWebServer + Maestro。

**Spec:** `docs/superpowers/specs/2026-09-29-group-buying-agent-refactor-design.md`

## Global Constraints

- 手机项目根目录：`C:\Users\caid0\Documents\ChatGPT\手机agent\.worktrees\fandazi-ai`。
- Agent 项目根目录：`D:\project\-Demo-master`。
- 所有新增或修改的代码、测试和配置注释必须使用详细、准确的中文。
- 不修改 Agent 基线文件 `node/base_node/decisionNode.py`、`node/base_node/routerNode.py` 和历史实验脚本，除非后续任务明确授权。
- 不修改手机项目已执行的 Flyway `V1-V5`，新迁移从 `V6` 开始。
- Android 不直连 Agent；Agent 不直连手机业务数据库。
- 业务金额协议统一使用整数分，时间统一使用 UTC ISO-8601，外部资源使用 UUID `publicId`。
- 购买、模拟支付、退款和投诉必须经过 Spring Boot 一次性确认凭证。
- Agent 只在聊天框输出文字或结构化卡片，不自动跳转页面。
- 每完成一个任务，运行该任务列出的测试并勾选复选框；不得用预期结果代替实测结果。
- 所有 Agent 实验同步记录到 `D:\project\-Demo-master\docs\experiments\intent-routing-experiments.md`。
- 工作树当前包含既有未提交修改；执行者不得覆盖、回退或混入无关文件。

## Repository Map

### 手机项目

- `contracts/`：跨仓库协议唯一来源。
- `backend/src/main/java/com/fandazi/feature/`：团购领域、Agent Gateway 和工具执行。
- `backend/src/main/resources/db/migration/`：仅追加 Flyway 迁移。
- `android-app/app/src/main/`：Android 页面、网络模型和聊天卡片。
- `data/seed/`：确定性团购种子数据。
- `maestro/`：每个 Android 切片的真实模拟器验收。

### Agent 项目

- `service/`：FastAPI 内部服务、鉴权、NDJSON 和协议模型。
- `intent/`、`config/intent_templates.yaml`、`config/local_exact_intents.yaml`：团购意图体系。
- `dag/`：工作流、DAG、工具契约和执行器。
- `memory/`：Agent 长期记忆主存储与语义索引。
- `persistence/`、`database/schema/`：Agent 审计、运行和记忆持久化。
- `tests/`：契约、工具、记忆、场景与评测测试。

---

### Task 1: 冻结跨仓库协议与契约校验

**Files:**
- Create: `[手机] contracts/agent-protocol-v1.yaml`
- Create: `[手机] contracts/agent-tools-v1.yaml`
- Create: `[手机] backend/src/test/java/com/fandazi/feature/agent/AgentContractSchemaTest.java`
- Create: `[Agent] config/contracts/agent-protocol-v1.yaml`
- Create: `[Agent] config/contracts/agent-tools-v1.yaml`
- Create: `[Agent] config/contracts/source.sha256`
- Create: `[Agent] tests/test_cross_repo_contracts.py`

**Interfaces:**
- Produces: `AgentRunRequestV1`、`AgentEventV1`、`AgentRunResultV1`、`AgentCardV1`、`ToolRequestV1`、`ToolResultV1`。
- Produces: 15 个工具的参数、返回值、风险等级、确认要求和错误码。
- Consumes: 规格第 7、8、11、12 节。

- [x] **Step 1: 编写失败的 Java 契约测试**

```java
@Test
void agentToolContractContainsEveryApprovedTool() {
    Set<String> expected = Set.of(
        "search_stores", "get_store_detail", "list_deal_packages",
        "get_deal_detail", "search_reviews", "list_user_orders",
        "get_order_detail", "list_user_vouchers", "create_purchase_quote",
        "create_group_buy_order", "pay_mock_order", "request_refund",
        "query_coupon", "query_faq", "submit_complaint"
    );
    assertThat(loadContractToolNames()).containsExactlyInAnyOrderElementsOf(expected);
}
```

- [x] **Step 2: 运行测试并确认因契约不存在而失败**

Run: `mvn -f backend/pom.xml -Dtest=AgentContractSchemaTest test`

Expected: FAIL，错误明确指出 `contracts/agent-tools-v1.yaml` 不存在。

- [x] **Step 3: 编写两份权威 YAML 契约**

```yaml
schemaVersion: "1.0"
toolRequest:
  required: [schemaVersion, userId, sessionId, traceId, runId, taskId, idempotencyKey, payload]
toolResult:
  required: [schemaVersion, ok, data, error]
errors:
  - VALIDATION_ERROR
  - NOT_FOUND
  - FORBIDDEN
  - CONFLICT
  - EXPIRED
  - RATE_LIMITED
  - DEPENDENCY_UNAVAILABLE
  - INTERNAL_ERROR
```

- [x] **Step 4: 在 Agent 仓库写入受控副本和摘要校验测试**

```python
def test_contract_copy_matches_declared_sha256():
    expected = Path("config/contracts/source.sha256").read_text(encoding="utf-8").strip()
    actual = hashlib.sha256(Path("config/contracts/agent-tools-v1.yaml").read_bytes()).hexdigest()
    assert actual == expected
```

- [x] **Step 5: 运行双端契约测试**

Run: `mvn -f backend/pom.xml -Dtest=AgentContractSchemaTest test`

Run: `C:\Users\caid0\anaconda3\envs\pinnT\python.exe -m pytest tests/test_cross_repo_contracts.py -q`

Expected: 两端全部 PASS，工具名、版本和摘要一致。

- [x] **Step 6: 提交契约地基**

```bash
# 手机项目提交
git add contracts backend/src/test/java/com/fandazi/feature/agent/AgentContractSchemaTest.java
git commit -m "feat: define group buying agent contracts"

# Agent 项目提交
git add config/contracts tests/test_cross_repo_contracts.py
git commit -m "feat: import versioned mobile agent contracts"
```

### Task 2: 建立团购数据库、领域实体和确定性种子数据

**Files:**
- Create: `[手机] backend/src/main/resources/db/migration/V6__create_group_buying_domain.sql`
- Create: `[手机] backend/src/main/resources/db/migration/V7__migrate_merchants_to_stores.sql`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/catalog/StoreEntity.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/catalog/DealPackageEntity.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/purchase/PurchaseQuote.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/grouporder/GroupBuyOrderEntity.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/voucher/VoucherEntity.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/refund/RefundRequestEntity.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/coupon/CouponEntity.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/coupon/UserCouponEntity.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/support/ComplaintTicketEntity.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/agent/ConfirmationGrantEntity.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/agent/AgentMessageEntity.java`
- Modify: `[手机] backend/src/main/java/com/fandazi/feature/catalog/MerchantEntity.java`
- Modify: `[手机] backend/src/main/java/com/fandazi/seed/SeedDataLoader.java`
- Create: `[手机] data/seed/stores.json`
- Create: `[手机] data/seed/deals.json`
- Create: `[手机] data/seed/group-orders.json`
- Create: `[手机] data/seed/vouchers.json`
- Create: `[手机] data/seed/coupons.json`
- Modify: `[手机] data/seed/reviews.json`
- Test: `[手机] backend/src/test/java/com/fandazi/feature/groupbuy/GroupBuyingSchemaTest.java`
- Test: `[手机] backend/src/test/java/com/fandazi/seed/GroupBuyingSeedDataTest.java`

**Interfaces:**
- Produces: `Merchant.publicId`、`Store.publicId`、`DealPackage.publicId`、`GroupBuyOrder.publicId`、`Voucher.publicId`。
- Produces: `OrderStatus` 和 `VoucherStatus` 的规格状态机。
- Consumes: Task 1 金额、时间和公开 ID 规则。

- [x] **Step 1: 编写失败的 Flyway 和状态机测试**

```java
@Test
void onePurchasedQuantityCreatesOneVoucher() {
    GroupBuyOrderEntity order = fixture.paidOrderWithQuantity(3);
    assertThat(order.getVouchers()).hasSize(3);
    assertThat(order.getVouchers()).allMatch(v -> v.getStatus() == VoucherStatus.UNUSED);
}
```

- [x] **Step 2: 运行新测试确认缺少团购实体**

Run: `mvn -f backend/pom.xml -Dtest=GroupBuyingSchemaTest,GroupBuyingSeedDataTest test`

Expected: FAIL，缺少团购表或类型。

- [x] **Step 3: 添加 V6/V7 迁移和实体映射**

迁移必须创建品牌公开 ID、门店、套餐适用门店、报价、团购订单、券、退款和确认凭证，并为幂等键、公开 ID、用户订单和券状态建立唯一约束或索引。

- [x] **Step 4: 生成确定性种子数据**

数据量固定为至少 20 个品牌、30 家门店、120 个套餐、300 条评价和 30 张优惠券定义；订单与券覆盖全部规格状态。种子图片使用已有本地授权素材或项目占位图。

- [x] **Step 5: 运行领域和种子测试**

Run: `mvn -f backend/pom.xml -Dtest=GroupBuyingSchemaTest,GroupBuyingSeedDataTest test`

Expected: PASS，重复执行 seed 不产生重复数据。

- [x] **Step 6: 提交领域地基**

```bash
git add backend/src/main/resources/db/migration backend/src/main/java/com/fandazi/feature data/seed backend/src/test/java/com/fandazi
git commit -m "feat: add group buying domain foundation"
```

### Task 3: 为新 Agent 建立 FastAPI、内部 JWT 和 NDJSON 运行协议

**Files:**
- Create: `[Agent] service/__init__.py`
- Create: `[Agent] service/app.py`
- Create: `[Agent] service/models.py`
- Create: `[Agent] service/auth.py`
- Create: `[Agent] service/streaming.py`
- Modify: `[Agent] requirements.txt`
- Create: `[Agent] tests/test_agent_service_api.py`
- Modify: `[手机] backend/src/main/java/com/fandazi/feature/agent/AgentRunRequest.java`
- Modify: `[手机] backend/src/main/java/com/fandazi/feature/agent/AgentResult.java`
- Modify: `[手机] backend/src/main/java/com/fandazi/feature/agent/HttpAgentServiceClient.java`
- Modify: `[手机] backend/src/main/java/com/fandazi/feature/agent/AgentGatewayService.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/agent/AgentMessageRepository.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/agent/AgentMessageService.java`
- Test: `[手机] backend/src/test/java/com/fandazi/feature/agent/AgentGatewayV1ContractTest.java`

**Interfaces:**
- Produces: `POST /internal/v1/agent/runs`，响应 `application/x-ndjson`。
- Produces: `GET /internal/v1/health`。
- Consumes: `AgentRunRequestV1`、`AgentEventV1`、短期内部 JWT。

- [x] **Step 1: 编写失败的 Agent API 测试**

```python
def test_run_rejects_missing_internal_jwt(client):
    response = client.post("/internal/v1/agent/runs", json=valid_request())
    assert response.status_code == 401

def test_run_streams_started_and_completed_events(client, internal_jwt):
    response = client.post(
        "/internal/v1/agent/runs",
        headers={"Authorization": f"Bearer {internal_jwt}"},
        json=valid_request(text="你好"),
    )
    events = [json.loads(line) for line in response.text.splitlines()]
    assert events[0]["type"] == "run_started"
    assert events[-1]["type"] == "run_completed"
```

- [x] **Step 2: 运行测试并确认 HTTP 服务不存在**

Run: `C:\Users\caid0\anaconda3\envs\pinnT\python.exe -m pytest tests/test_agent_service_api.py -q`

- [x] **Step 3: 实现 FastAPI、Pydantic 模型、JWT 校验和 NDJSON 编码**

`runId` 由 Agent 创建；`traceId` 由 Spring 传入；简单请求不生成 `planId`。流式事件只允许公开业务摘要。

- [x] **Step 4: 修改 Spring 客户端逐行消费 NDJSON**

Spring 每收到一条事件就调用 `AgentEventService.record(...)`，终态映射为 `AgentRunResultV1`，传输中断映射为 `DEPENDENCY_UNAVAILABLE`。

- [x] **Step 5: 运行 Python 和 Java 协议测试**

Run: `C:\Users\caid0\anaconda3\envs\pinnT\python.exe -m pytest tests/test_agent_service_api.py -q`

Run: `mvn -f backend/pom.xml -Dtest=AgentGatewayV1ContractTest test`

Expected: JWT 拒绝、NDJSON 顺序、终态解析和中断处理全部 PASS。

- [x] **Step 6: 提交运行协议**

```bash
git add service requirements.txt tests/test_agent_service_api.py
git commit -m "feat: expose versioned agent run stream"

git add backend/src/main/java/com/fandazi/feature/agent backend/src/test/java/com/fandazi/feature/agent/AgentGatewayV1ContractTest.java
git commit -m "feat: consume independent agent event stream"
```

### Task 4: 完成浏览、排序和套餐推荐纵向切片

**Files:**
- Create: `[手机] backend/src/main/java/com/fandazi/feature/catalog/StoreController.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/catalog/StoreService.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/catalog/DealPackageController.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/catalog/DealPackageService.java`
- Refactor: `[手机] backend/src/main/java/com/fandazi/feature/agent/AgentToolService.java`
- Modify: `[Agent] dag/tool_registry.py`
- Modify: `[Agent] dag/tool_adapters.py`
- Modify: `[Agent] config/intent_templates.yaml`
- Modify: `[Agent] intent/models.py`
- Create: `[Agent] workflows/recommendation.py`
- Create: `[Agent] workflows/__init__.py`
- Modify: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/home/HomeFragment.kt`
- Modify: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/home/HomePresentationState.kt`
- Create: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/store/StoreFragment.kt`
- Create: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/store/StoreModels.kt`
- Create: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/deal/DealDetailFragment.kt`
- Modify: `[手机] android-app/app/src/main/res/navigation/nav_graph.xml`
- Test: `[手机] backend/src/test/java/com/fandazi/feature/catalog/StoreDealApiTest.java`
- Test: `[Agent] tests/test_group_buying_read_tools.py`
- Test: `[手机] android-app/app/src/test/java/com/fandazi/app/feature/home/HomeSortTest.kt`
- Create: `[手机] maestro/group-buying-01-browse.yaml`
- Design: `Figma 文件「饭搭子团购浏览 UI」`，用于首页、门店详情和套餐详情的 XML 转换基准。

**Interfaces:**
- Produces: `GET /api/stores`，参数 `cityCode/sort/latitude/longitude/category/minPrice/maxPrice`。
- Produces: `GET /api/stores/{publicId}`、`GET /api/stores/{publicId}/deals`、`GET /api/deals/{publicId}`。
- Produces tools: `search_stores`、`get_store_detail`、`list_deal_packages`、`get_deal_detail`、`search_reviews`。
- Produces intents: `greeting`、`chitchat`、`store_recommendation`、`deal_recommendation`、`store_info`、`review_query`、`group_buy_order`、`coupon`、`refund_after_sale`、`complaint`、`faq`、`out_of_scope` 的基础定义。

- [x] **Step 1: 编写失败的排序和工具测试**

```java
@Test
void distanceSortRequiresUsableLocation() {
    mockMvc.perform(get("/api/stores").param("sort", "DISTANCE"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
}
```

```python
def test_single_recommendation_goal_uses_deterministic_workflow():
    result = run_workflow("推荐附近人均100以内的川菜团购")
    assert result.is_composite is False
    assert result.tool_names == ["search_stores", "list_deal_packages"]
```

- [x] **Step 2: 运行三端测试并确认失败**

Run: `mvn -f backend/pom.xml -Dtest=StoreDealApiTest test`

Run: `C:\Users\caid0\anaconda3\envs\pinnT\python.exe -m pytest tests/test_group_buying_read_tools.py -q`

Run: `cd android-app; .\gradlew.bat testDebugUnitTest --tests "*HomeSortTest"`

- [x] **Step 3: 实现 Spring 查询、排序和工具分派**

综合排序必须稳定；人均使用 `Store.averageSpendCents`；距离排序只在有效坐标存在时启用。工具结果只返回契约允许字段。

- [x] **Step 4: 迁移 12 类意图基础定义并实现 Agent 确定性推荐工作流**

先一次性迁移 12 类意图名称、输出模型和最小可用边界示例，确保后续购买、券和售后切片不再依赖旧外卖意图；千级本地文案和完整离线评测留到 Task 9。随后实现推荐确定性工作流。一个推荐目标可以串行调用多个只读工具，但保持 `is_composite=false`；多个独立目标才进入 DAG。

- [ ] **Step 5: 先完成 Figma UI 设计，再改造 Android 首页、门店和套餐页面**

UI 实施不采用 TDD。必须先在 Figma 中完成并确认以下设计稿，再将设计转换为 Android XML：

1. 首页团购发现：位置、搜索、推荐/人均/距离排序、门店卡片和底部导航。
2. 门店详情：门店概览、评分、地址、团购套餐入口和评价入口。
3. 套餐详情：价格、原价、库存、购买须知、评价和购买按钮。

Figma 设计文件：`https://www.figma.com/design/fDqc9laGSxeCnh83uuw8Zz`。

完成 Figma 设计后，按设计稿转换 XML，并保证首页不显示商圈；定位拒绝时禁用距离排序；门店页不出现菜品加购和旧外卖购物车。

- [ ] **Step 6: 运行 Android 构建、Maestro 和视觉验收**

UI 验收顺序固定为：

1. 构建最新 debug APK。
2. 从模拟器卸载旧版 `com.fandazi.app` 和干扰应用 `com.dirror.music`。
3. 安装最新 APK。
4. 执行 `maestro test maestro/group-buying-01-browse.yaml`。
5. 对照 Figma 设计稿检查页面层级、间距、颜色、字体、文字溢出和点击路径。

Run: `mvn -f backend/pom.xml -Dtest=StoreDealApiTest test`

Run: `C:\Users\caid0\anaconda3\envs\pinnT\python.exe -m pytest tests/test_group_buying_read_tools.py -q`

Run: `cd android-app; .\gradlew.bat testDebugUnitTest assembleDebug`

Run: `maestro test maestro/group-buying-01-browse.yaml`

Expected: Figma 设计与 XML 页面一致；登录和游客都能浏览；三种排序符合规则；推荐工具序列正确；Maestro 真实模拟器流程通过。

- [ ] **Step 7: 视觉验收通过后提交浏览推荐切片**

```bash
git add backend android-app maestro
git commit -m "feat: add group buying discovery slice"

git add dag workflows tests/test_group_buying_read_tools.py
git commit -m "feat: orchestrate store and deal recommendations"
```

### Task 5: 建立 Android AI 标签、流式消息和聊天卡片框架

**Files:**
- Modify: `[手机] android-app/app/src/main/res/menu/bottom_navigation_menu.xml`
- Modify: `[手机] android-app/app/src/main/res/navigation/nav_graph.xml`
- Replace: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentSheetFragment.kt`
- Remove from runtime: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentFloatingButtonPolicy.kt`
- Create: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentChatFragment.kt`
- Modify: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentModels.kt`
- Modify: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentRepository.kt`
- Modify: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentViewModel.kt`
- Create: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/agent/card/AgentCardRenderer.kt`
- Create: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/agent/card/AgentCardModels.kt`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/agent/AgentCardEnrichmentService.java`
- Create: `[手机] android-app/app/src/main/res/layout/fragment_agent_chat.xml`
- Create: `[手机] android-app/app/src/main/res/layout/item_agent_store_card.xml`
- Create: `[手机] android-app/app/src/main/res/layout/item_agent_deal_card.xml`
- Modify: `[手机] android-app/app/src/main/res/values/strings.xml`
- Test: `[手机] android-app/app/src/test/java/com/fandazi/app/feature/agent/AgentLoginGateTest.kt`
- Test: `[手机] android-app/app/src/test/java/com/fandazi/app/feature/agent/AgentCardRendererTest.kt`
- Test: `[手机] backend/src/test/java/com/fandazi/feature/agent/AgentCardEnrichmentTest.java`
- Create: `[手机] maestro/group-buying-02-agent-chat.yaml`

**Interfaces:**
- Consumes: `AgentEventV1` 和 `AgentCardV1`。
- Produces: 聊天内文字、业务进度、门店卡、套餐卡和错误卡。
- Constraint: 卡片只能在用户点击后执行受控导航，事件接收不得自动切换 Fragment。

- [ ] **Step 1: 编写失败的登录门槛和禁止自动跳转测试**

```kotlin
@Test
fun guestSelectingAgentReturnsLoginDestination() {
    assertEquals(Route.Login(returnTo = Route.Agent), coordinator.openAgent(isLoggedIn = false))
}

@Test
fun receivingStoreCardDoesNotNavigateUntilClick() {
    viewModel.onEvent(storeCardEvent("store-public-id"))
    assertNull(viewModel.navigation.value)
}
```

- [ ] **Step 2: 运行 Android 与 Spring 测试确认失败**

Run: `cd android-app; .\gradlew.bat testDebugUnitTest --tests "*AgentLoginGateTest" --tests "*AgentCardRendererTest"`

Run: `mvn -f backend/pom.xml -Dtest=AgentCardEnrichmentTest test`

- [ ] **Step 3: 将 AI助手加入底部导航并移除悬浮入口**

游客进入 AI 标签时只展示登录引导；登录成功后返回 AI 标签。删除布局中的常驻悬浮球引用，不删除仍被历史测试引用的源码，直到 Task 11 统一清理。

- [ ] **Step 4: 实现 NDJSON/SSE 事件解析和版本化卡片渲染**

Agent 仅返回卡片类型和公开资源 ID，Spring 的 `AgentCardEnrichmentService` 校验资源归属并补充价格、状态和按钮。未知卡片版本渲染为安全错误卡，不从模型文本解析业务字段。

- [ ] **Step 5: 运行 Android 测试、构建和 Maestro**

Run: `cd android-app; .\gradlew.bat testDebugUnitTest lintDebug assembleDebug`

Run: `mvn -f backend/pom.xml -Dtest=AgentCardEnrichmentTest test`

Run: `maestro test maestro/group-buying-02-agent-chat.yaml`

Expected: 游客被引导登录；流式文字和卡片在聊天框显示；收卡时页面不自动跳转。

- [ ] **Step 6: 提交聊天框架**

```bash
git add android-app maestro/group-buying-02-agent-chat.yaml
git commit -m "feat: add authenticated agent chat tab and cards"
```

### Task 6: 完成报价、确认、下单和模拟支付纵向切片

**Files:**
- Create: `[手机] backend/src/main/java/com/fandazi/feature/purchase/PurchaseQuoteService.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/purchase/PurchaseController.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/grouporder/GroupBuyOrderService.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/grouporder/GroupBuyOrderController.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/agent/ConfirmationGrantService.java`
- Modify: `[手机] backend/src/main/java/com/fandazi/feature/payment/PaymentService.java`
- Modify: `[手机] backend/src/main/java/com/fandazi/feature/agent/AgentToolService.java`
- Create: `[Agent] workflows/purchase.py`
- Modify: `[Agent] dag/tool_registry.py`
- Modify: `[Agent] dag/tool_adapters.py`
- Create: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/purchase/DealCheckoutFragment.kt`
- Create: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/purchase/PurchaseViewModel.kt`
- Create: `[手机] android-app/app/src/main/res/layout/fragment_deal_checkout.xml`
- Create: `[手机] android-app/app/src/main/res/layout/item_agent_confirmation_card_v1.xml`
- Test: `[手机] backend/src/test/java/com/fandazi/feature/purchase/PurchaseConfirmationTest.java`
- Test: `[手机] backend/src/test/java/com/fandazi/feature/grouporder/GroupBuyOrderIdempotencyTest.java`
- Test: `[Agent] tests/test_purchase_workflow.py`
- Test: `[手机] android-app/app/src/test/java/com/fandazi/app/feature/purchase/PurchaseViewModelTest.kt`
- Create: `[手机] maestro/group-buying-03-purchase.yaml`

**Interfaces:**
- Produces tools: `create_purchase_quote`、`create_group_buy_order`、`pay_mock_order`。
- Produces cards: `confirmation_card`、`order_card`。
- Consumes: 单次 `ConfirmationGrant` 和系统生成幂等键。

- [ ] **Step 1: 编写失败的报价变化、确认过期和幂等测试**

```java
@Test
void changedDealVersionInvalidatesConfirmationGrant() {
    ConfirmationGrant grant = fixture.grantForDealVersion(3);
    fixture.updateDealToVersion(4);
    assertThatThrownBy(() -> service.createOrder(grant.token()))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("资源已变化");
}
```

```python
def test_purchase_stops_at_confirmation_before_write_tool():
    result = run_purchase("买两份双人套餐")
    assert result.status == "AWAITING_CONFIRMATION"
    assert result.tool_names == ["get_deal_detail", "create_purchase_quote"]
```

- [ ] **Step 2: 运行切片测试确认失败**

Run: `mvn -f backend/pom.xml -Dtest=PurchaseConfirmationTest,GroupBuyOrderIdempotencyTest test`

Run: `C:\Users\caid0\anaconda3\envs\pinnT\python.exe -m pytest tests/test_purchase_workflow.py -q`

- [ ] **Step 3: 实现报价、确认凭证和写工具**

报价保存套餐、数量、金额和规则版本快照。创建订单和模拟支付分别要求确认；重复幂等键且指纹一致时返回原结果，指纹不一致时返回 `CONFLICT`。

- [ ] **Step 4: 实现 Agent 暂停与恢复**

首次运行输出确认卡并保存 `runId` checkpoint；用户确认产生新 `traceId`，恢复原 `runId`，执行对应写工具后终结。

- [ ] **Step 5: 实现 Android 套餐确认和模拟支付**

确认页只包含套餐、数量、优惠、总额和使用规则，不出现配送地址、配送费或菜品规格。

- [ ] **Step 6: 运行全切片验证**

Run: `mvn -f backend/pom.xml -Dtest=PurchaseConfirmationTest,GroupBuyOrderIdempotencyTest test`

Run: `C:\Users\caid0\anaconda3\envs\pinnT\python.exe -m pytest tests/test_purchase_workflow.py -q`

Run: `cd android-app; .\gradlew.bat testDebugUnitTest assembleDebug`

Run: `maestro test maestro/group-buying-03-purchase.yaml`

- [ ] **Step 7: 提交购买支付切片**

```bash
git add backend android-app maestro/group-buying-03-purchase.yaml
git commit -m "feat: add confirmed group buying purchase flow"

git add workflows dag tests/test_purchase_workflow.py
git commit -m "feat: orchestrate confirmed group buying purchase"
```

### Task 7: 完成团购券、二维码和模拟核销纵向切片

**Files:**
- Create: `[手机] backend/src/main/java/com/fandazi/feature/voucher/VoucherService.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/voucher/VoucherController.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/voucher/VoucherTokenService.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/voucher/DemoRedemptionController.java`
- Modify: `[手机] backend/src/main/java/com/fandazi/feature/agent/AgentToolService.java`
- Modify: `[Agent] dag/tool_registry.py`
- Modify: `[Agent] dag/tool_adapters.py`
- Create: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/voucher/VoucherDetailFragment.kt`
- Create: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/voucher/VoucherModels.kt`
- Create: `[手机] android-app/app/src/main/res/layout/fragment_voucher_detail.xml`
- Create: `[手机] android-app/app/src/main/res/layout/item_agent_voucher_card.xml`
- Test: `[手机] backend/src/test/java/com/fandazi/feature/voucher/VoucherLifecycleTest.java`
- Test: `[手机] backend/src/test/java/com/fandazi/feature/voucher/VoucherTokenSecurityTest.java`
- Test: `[Agent] tests/test_voucher_tools.py`
- Create: `[手机] maestro/group-buying-04-voucher-redemption.yaml`

**Interfaces:**
- Produces tools: `list_user_vouchers`、`get_order_detail`。
- Produces: `POST /api/vouchers/{publicId}/display-token` 和受保护演示核销接口。
- Produces card: `voucher_card`。

- [ ] **Step 1: 编写失败的券数量、令牌过期和重复核销测试**

```java
@Test
void redemptionTokenCannotBeUsedTwice() {
    String token = fixture.issueDisplayToken();
    redemption.redeem(token, fixture.storePublicId(), "redeem-key-1");
    assertThatThrownBy(() -> redemption.redeem(token, fixture.storePublicId(), "redeem-key-2"))
        .hasMessageContaining("团购券不可核销");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -f backend/pom.xml -Dtest=VoucherLifecycleTest,VoucherTokenSecurityTest test`

- [ ] **Step 3: 实现一份一券、短时令牌和幂等核销**

二维码载荷必须签名并包含券公开 ID、适用门店、签发时间和过期时间；日志不得记录完整令牌。

- [ ] **Step 4: 接入 Agent 券查询和聊天卡片**

Agent 只读取券摘要，不获取可直接核销的秘密；二维码令牌仅在 Android 已认证请求中按需签发。

- [ ] **Step 5: 实现 Android 订单分段视图和券详情**

订单标签为“待使用、已使用、退款/售后、全部”。核销入口明确标记为演示功能。

- [ ] **Step 6: 运行测试与 Maestro**

Run: `mvn -f backend/pom.xml -Dtest=VoucherLifecycleTest,VoucherTokenSecurityTest test`

Run: `C:\Users\caid0\anaconda3\envs\pinnT\python.exe -m pytest tests/test_voucher_tools.py -q`

Run: `cd android-app; .\gradlew.bat testDebugUnitTest assembleDebug`

Run: `maestro test maestro/group-buying-04-voucher-redemption.yaml`

- [ ] **Step 7: 提交券核销切片**

```bash
git add backend android-app maestro/group-buying-04-voucher-redemption.yaml
git commit -m "feat: add secure voucher redemption flow"

git add dag tests/test_voucher_tools.py
git commit -m "feat: add voucher query tools"
```

### Task 8: 完成订单、退款、评价、优惠券、FAQ 和投诉切片

**Files:**
- Create: `[手机] backend/src/main/java/com/fandazi/feature/refund/RefundService.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/refund/RefundController.java`
- Refactor: `[手机] backend/src/main/java/com/fandazi/feature/catalog/MerchantReviewEntity.java`
- Refactor: `[手机] backend/src/main/java/com/fandazi/feature/catalog/MerchantReviewRepository.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/review/ReviewService.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/support/ComplaintService.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/coupon/CouponService.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/coupon/CouponController.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/support/ComplaintController.java`
- Modify: `[手机] backend/src/main/java/com/fandazi/feature/agent/AgentToolService.java`
- Create: `[Agent] workflows/after_sale.py`
- Modify: `[Agent] dag/tool_registry.py`
- Modify: `[Agent] dag/tool_adapters.py`
- Modify: `[Agent] node/agent/data/03-用户常见问题FAQ.md`
- Modify: `[Agent] node/agent/data/11-会员积分与活动规则FAQ.md`
- Modify: `[Agent] node/agent/data/12-售后维权与纠纷处理FAQ.md`
- Modify: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/order/OrdersFragment.kt`
- Create: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/order/GroupOrderDetailFragment.kt`
- Create: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/review/ReviewFragment.kt`
- Test: `[手机] backend/src/test/java/com/fandazi/feature/refund/RefundEligibilityTest.java`
- Test: `[手机] backend/src/test/java/com/fandazi/feature/review/ReviewEligibilityTest.java`
- Test: `[手机] backend/src/test/java/com/fandazi/feature/support/CouponComplaintToolTest.java`
- Test: `[Agent] tests/test_after_sale_workflow.py`
- Create: `[手机] maestro/group-buying-05-after-sale.yaml`

**Interfaces:**
- Produces tools: `list_user_orders`、`get_order_detail`、`request_refund`、`query_coupon`、`query_faq`、`submit_complaint`。
- Constraint: 仅 `UNUSED` 券可退款；至少一张券核销后允许每个订单评价一次。

- [ ] **Step 1: 编写失败的退款与评价资格测试**

```java
@Test
void redeemedVoucherCannotBeRefunded() {
    VoucherEntity voucher = fixture.redeemedVoucher();
    assertThatThrownBy(() -> refunds.request(voucher, "行程变化", fixture.grant()))
        .hasMessageContaining("已核销团购券不可退款");
}

@Test
void orderWithoutRedeemedVoucherCannotBeReviewed() {
    assertThatThrownBy(() -> reviews.create(fixture.unusedOrder(), 5, "很好"))
        .hasMessageContaining("核销后才能评价");
}
```

- [ ] **Step 2: 运行测试确认资格规则尚未实现**

Run: `mvn -f backend/pom.xml -Dtest=RefundEligibilityTest,ReviewEligibilityTest,CouponComplaintToolTest test`

- [ ] **Step 3: 实现售后领域和白名单工具**

退款和投诉要求确认凭证；FAQ、优惠券和订单查询为只读。所有资源先校验用户归属，再返回脱敏结果。

- [ ] **Step 4: 实现 Agent 售后确定性工作流和复合 DAG 场景**

“查退款条件并推荐火锅套餐”拆成两个独立目标；推荐分支可以在退款查询失败时继续，退款写节点失败时停止其下游写操作。

- [ ] **Step 5: 实现 Android 售后、评价和对应聊天卡片**

退款确认卡展示券、金额、原因和有效期；订单详情只对符合资格的订单显示评价入口。

- [ ] **Step 6: 运行切片测试与 Maestro**

Run: `mvn -f backend/pom.xml -Dtest=RefundEligibilityTest,ReviewEligibilityTest,CouponComplaintToolTest test`

Run: `C:\Users\caid0\anaconda3\envs\pinnT\python.exe -m pytest tests/test_after_sale_workflow.py -q`

Run: `cd android-app; .\gradlew.bat testDebugUnitTest assembleDebug`

Run: `maestro test maestro/group-buying-05-after-sale.yaml`

- [ ] **Step 7: 提交售后切片**

```bash
git add backend android-app maestro/group-buying-05-after-sale.yaml
git commit -m "feat: add group buying after sale flow"

git add workflows dag node/agent/data tests/test_after_sale_workflow.py
git commit -m "feat: orchestrate group buying support tools"
```

### Task 9: 扩充团购意图完整模板、千级本地规则和离线评测

**Files:**
- Modify: `[Agent] config/intent_templates.yaml`
- Modify: `[Agent] config/local_exact_intents.yaml`
- Modify: `[Agent] intent/models.py`
- Modify: `[Agent] intent/template_loader.py`
- Modify: `[Agent] intent/local_exact_template_loader.py`
- Modify: `[Agent] intent/decision_model_adapter.py`
- Modify: `[Agent] intent/router_fallback_adapter.py`
- Modify: `[Agent] intent/orchestrator.py`
- Modify: `[Agent] node/base_node/intentRouterNode.py`
- Modify: `[Agent] tests/data/local_exact_shadow_cases.yaml`
- Create: `[Agent] tests/data/group_buying_intent_cases.yaml`
- Modify: `[Agent] tests/test_local_exact_corpus.py`
- Modify: `[Agent] tests/test_intent_orchestrator.py`
- Modify: `[Agent] tests/evaluate_intent_architecture.py`

**Interfaces:**
- Produces: 在 Task 4 的 12 类基础定义上补齐完整正反边界例、千级整句规则语料和 `is_composite` 评测基线。
- Preserves: 本地精准规则 -> 决策模型 -> 重型分类兜底 -> `out_of_scope`。
- Constraint: 不修改 `decisionNode.py`、`routerNode.py` 和历史实验脚本。

- [ ] **Step 1: 编写失败的 12 类配置测试**

```python
def test_group_buying_intent_names_are_exact():
    assert set(load_templates().intent_names) == {
        "greeting", "chitchat", "store_recommendation", "deal_recommendation",
        "store_info", "review_query", "group_buy_order", "coupon",
        "refund_after_sale", "complaint", "faq", "out_of_scope",
    }
```

- [ ] **Step 2: 运行意图测试确认未迁移类别或语料覆盖不足**

Run: `C:\Users\caid0\anaconda3\envs\pinnT\python.exe -m pytest tests/test_intent_template_loader.py tests/test_local_exact_corpus.py tests/test_intent_orchestrator.py -q`

- [ ] **Step 3: 更新模板、示例、边界和阈值**

每类必须包含正例、反例和边界例；明确“你好，推荐一个团购套餐”属于业务意图。继续使用背景 `state + 0.80`，除非新实验以实测结果支持调整。

- [ ] **Step 4: 重建团购本地精准文案**

保留通用问候、闲聊、投诉和 FAQ；删除配送、骑手和收货地址专属终结文案。所有文案仍使用规范化整句哈希匹配，禁止关键词包含分类。

- [ ] **Step 5: 运行单元测试与 12 类离线评测**

Run: `C:\Users\caid0\anaconda3\envs\pinnT\python.exe -m pytest tests/test_intent_template_loader.py tests/test_local_exact_corpus.py tests/test_intent_orchestrator.py tests/test_main_graph_routing.py -q`

Run: `C:\Users\caid0\anaconda3\envs\pinnT\python.exe tests/evaluate_intent_architecture.py --dataset tests/data/group_buying_intent_cases.yaml`

- [ ] **Step 6: 将实测结果写入实验文档**

记录实验编号、目的、控制变量、数据规模、命令、准确率、各层调用率、P50/P95、错误样例和对当前方案的影响；未完成在线模型调用时明确写“未完成在线测量”。

- [ ] **Step 7: 提交意图迁移**

```bash
git add config intent node/base_node/intentRouterNode.py tests docs/experiments/intent-routing-experiments.md
git commit -m "feat: migrate intent routing to group buying domain"
```

### Task 10: 实现 Agent 长期记忆和用户管理入口

**Files:**
- Create: `[Agent] database/schema/003_create_agent_memory.sql`
- Create: `[Agent] memory/__init__.py`
- Create: `[Agent] memory/models.py`
- Create: `[Agent] memory/repository.py`
- Create: `[Agent] memory/vector_index.py`
- Create: `[Agent] memory/service.py`
- Create: `[Agent] memory/retriever.py`
- Create: `[Agent] memory/retention.py`
- Create: `[Agent] config/memory.yaml`
- Modify: `[Agent] service/app.py`
- Modify: `[Agent] graph/main_graph.py`
- Create: `[Agent] tests/test_memory_service.py`
- Create: `[Agent] tests/test_memory_retrieval.py`
- Create: `[Agent] tests/test_memory_retention.py`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/agent/AgentMemoryClient.java`
- Create: `[手机] backend/src/main/java/com/fandazi/feature/agent/AgentMemoryController.java`
- Create: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/profile/AiMemoryFragment.kt`
- Create: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/profile/AiMemoryViewModel.kt`
- Create: `[手机] android-app/app/src/main/res/layout/fragment_ai_memory.xml`
- Test: `[手机] backend/src/test/java/com/fandazi/feature/agent/AgentMemoryGatewayTest.java`
- Test: `[手机] android-app/app/src/test/java/com/fandazi/app/feature/profile/AiMemoryViewModelTest.kt`
- Create: `[手机] maestro/group-buying-06-agent-memory.yaml`

**Interfaces:**
- Produces: 记忆 CRUD、明确“记住/忘记”、Top-K 检索、TTL 清理和 Chroma 重建。
- Authority: MySQL `memory_items` 是权威；Chroma 只保存 `memoryId/document/embedding/metadata`。

- [ ] **Step 1: 编写失败的记忆边界测试**

```python
def test_realtime_order_fact_is_never_persisted_as_memory(memory_service):
    result = memory_service.remember("我的订单 ORD-9 还有一张未使用券", explicit=True)
    assert result.rejected_reason == "business_fact"

def test_delete_removes_mysql_and_chroma(memory_service):
    memory = memory_service.remember("记住我偏爱微辣川菜", explicit=True)
    memory_service.delete(memory.id)
    assert memory_service.repository.get(memory.id) is None
    assert memory_service.vector_index.get(memory.id) is None
```

- [ ] **Step 2: 运行记忆测试确认模块不存在**

Run: `C:\Users\caid0\anaconda3\envs\pinnT\python.exe -m pytest tests/test_memory_service.py tests/test_memory_retrieval.py tests/test_memory_retention.py -q`

- [ ] **Step 3: 实现 MySQL 权威记忆和 Chroma 索引**

记录用户范围、类型、内容、来源、敏感级别、确认状态、创建/更新时间和过期时间。写入、修改和删除先提交 MySQL，再同步或补偿 Chroma。

- [ ] **Step 4: 实现分层召回与 token 预算**

会话开始注入少量稳定画像；每轮按当前问题检索 Top-K 情景、反馈和程序性记忆。冲突、过期或低相关记忆不注入。

- [ ] **Step 5: 实现 Spring 代理和 Android“AI 记忆”页面**

Android 不直连 Agent Memory API；所有查看、纠正、删除和清空请求经 Spring 校验用户身份。

- [ ] **Step 6: 运行三端测试与 Maestro**

Run: `C:\Users\caid0\anaconda3\envs\pinnT\python.exe -m pytest tests/test_memory_service.py tests/test_memory_retrieval.py tests/test_memory_retention.py -q`

Run: `mvn -f backend/pom.xml -Dtest=AgentMemoryGatewayTest test`

Run: `cd android-app; .\gradlew.bat testDebugUnitTest assembleDebug`

Run: `maestro test maestro/group-buying-06-agent-memory.yaml`

- [ ] **Step 7: 提交长期记忆切片**

```bash
git add database/schema/003_create_agent_memory.sql memory config/memory.yaml service graph tests
git commit -m "feat: add governed long term agent memory"

git add backend android-app maestro/group-buying-06-agent-memory.yaml
git commit -m "feat: expose user managed agent memory"
```

### Task 11: 退役外卖运行路径并标记旧 Agent Service

**Files:**
- Create: `[手机] agent-service/DEPRECATED.md`
- Modify: `[手机] README.md`
- Modify: `[手机] start-fandazi-services.ps1`
- Modify: `[手机] backend/src/main/resources/application.yml`
- Create: `[手机] backend/src/main/resources/db/migration/V8__remove_takeaway_runtime_tables.sql`
- Delete after reference scan: `[手机] backend/src/main/java/com/fandazi/feature/address/`
- Delete after reference scan: `[手机] backend/src/main/java/com/fandazi/feature/cart/`
- Delete after reference scan: `[手机] backend/src/main/java/com/fandazi/feature/delivery/`
- Delete after reference scan: `[手机] backend/src/main/java/com/fandazi/feature/automation/`
- Delete after reference scan: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/profile/Address*.kt`
- Delete after reference scan: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/order/CartFragment.kt`
- Delete after reference scan: `[手机] android-app/app/src/main/java/com/fandazi/app/feature/merchant/DishOptionsBottomSheet.kt`
- Modify: `[手机] android-app/app/src/main/res/navigation/nav_graph.xml`
- Modify: `[手机] android-app/app/src/main/res/values/strings.xml`
- Test: `[手机] backend/src/test/java/com/fandazi/architecture/NoTakeawayRuntimeDependencyTest.java`
- Test: `[手机] android-app/app/src/test/java/com/fandazi/app/architecture/NoTakeawayNavigationTest.kt`

**Interfaces:**
- Removes: 地址、购物车、菜品规格、配送状态机和长期自动下单的默认运行依赖。
- Preserves: 旧 `agent-service/` 源码，仅作为停用历史参考。

- [ ] **Step 1: 编写失败的运行依赖扫描测试**

```java
@Test
void productionCodeDoesNotReferenceTakeawayPackages() {
    assertThat(scanMainSources("com.fandazi.feature.address", "com.fandazi.feature.delivery"))
        .isEmpty();
}
```

- [ ] **Step 2: 使用 `rg` 建立删除前引用清单**

Run: `rg -n "feature\.(address|cart|delivery|automation)|配送|骑手|购物车|收货地址" backend/src/main android-app/app/src/main`

Expected: 输出中的每个运行引用都被分配到本任务；历史文档和旧 Agent 源码可以保留并明确标记。

- [ ] **Step 3: 添加 V8 清理迁移并删除已无引用代码**

V8 只删除已经由团购表替代的外卖交易表和列；执行前后均验证 Flyway checksum 与备份恢复说明。

- [ ] **Step 4: 修改启动脚本和配置只启动新 Agent**

`AGENT_SERVICE_URL` 指向独立 Agent；旧目录不进入启动、健康检查、Compose、CI 和发布产物。

- [ ] **Step 5: 运行引用扫描与全量构建**

Run: `mvn -f backend/pom.xml -Dtest=NoTakeawayRuntimeDependencyTest test`

Run: `cd android-app; .\gradlew.bat testDebugUnitTest lintDebug assembleDebug`

Run: `rg -n "配送|骑手|购物车|收货地址" backend/src/main android-app/app/src/main`

Expected: 运行代码中无外卖领域残留；允许出现明确说明迁移历史的文档文本。

- [ ] **Step 6: 提交退役改动**

```bash
git add README.md agent-service/DEPRECATED.md start-fandazi-services.ps1 backend android-app
git commit -m "refactor: retire takeaway runtime paths"
```

### Task 12: 建立可插拔评测、集成部署和最终验收

**Files:**
- Create: `[Agent] evaluation/__init__.py`
- Create: `[Agent] evaluation/models.py`
- Create: `[Agent] evaluation/scorer.py`
- Create: `[Agent] evaluation/runner.py`
- Create: `[Agent] evaluation/scorers/__init__.py`
- Create: `[Agent] evaluation/scorers/intent.py`
- Create: `[Agent] evaluation/scorers/tool_sequence.py`
- Create: `[Agent] evaluation/scorers/memory.py`
- Create: `[Agent] evaluation/scorers/safety.py`
- Create: `[Agent] tests/data/group_buying_agent_scenarios.yaml`
- Create: `[Agent] tests/test_evaluation_plugins.py`
- Modify: `[Agent] docs/experiments/intent-routing-experiments.md`
- Create: `[Agent] Dockerfile`
- Create: `[手机] backend/Dockerfile`
- Create: `[手机] docker-compose.integration.yml`
- Modify: `[手机] start-fandazi-services.ps1`
- Create: `[手机] maestro/group-buying-99-full-regression.yaml`
- Modify: `[手机] docs/verification-report.md`
- Modify: `[手机] docs/architecture.md`
- Modify: `[手机] docs/api-and-agent.md`

**Interfaces:**
- Produces: `ScenarioCase`、`EvaluationResult`、`Scorer` 插件接口。
- Produces: 可启动 MySQL、Spring Boot 和新 Agent 的集成 Compose。
- Produces: 最终实测报告和回滚说明。

- [ ] **Step 1: 编写失败的评分器替换测试**

```python
class ConstantScorer:
    name = "constant"

    def score(self, case, actual):
        return Score(value=1.0, details={"source": "test"})

def test_runner_accepts_replacement_scorer():
    result = EvaluationRunner([ConstantScorer()]).run([sample_case()])
    assert result.metrics["constant"] == 1.0
```

- [ ] **Step 2: 运行评测插件测试确认接口不存在**

Run: `C:\Users\caid0\anaconda3\envs\pinnT\python.exe -m pytest tests/test_evaluation_plugins.py -q`

- [ ] **Step 3: 实现统一用例、结果和评分器接口**

默认评分器覆盖意图、复合判断、DAG、工具、参数、记忆、卡片、安全和任务成功率。新增评分器只注册插件，不修改 Agent 主图。

- [ ] **Step 4: 建立跨服务 Compose 与健康检查**

Compose 只包含 MySQL、Spring Boot 和新 Agent；Agent 连接自己的 `dianping_intent`，Spring 使用手机业务数据库。为 Agent 的 Chroma 目录配置命名卷，确保容器重建后索引仍可复用，同时保留从 MySQL `memory_items` 全量重建索引的命令。旧 Agent 不得出现在服务清单。

- [ ] **Step 5: 运行三端全量自动化测试**

Run: `mvn -f backend/pom.xml test`

Run: `C:\Users\caid0\anaconda3\envs\pinnT\python.exe -m pytest -q`

Run: `cd android-app; .\gradlew.bat testDebugUnitTest lintDebug assembleDebug`

Expected: 所有测试 PASS；若在线模型或模拟器不可用，报告必须明确阻塞，不得声称对应验收通过。

- [ ] **Step 6: 启动真实集成环境并执行场景评测**

Run: `docker compose -f docker-compose.integration.yml up --build -d`

Run: `C:\Users\caid0\anaconda3\envs\pinnT\python.exe -m evaluation.runner --dataset tests/data/group_buying_agent_scenarios.yaml --output tests/results/group_buying_agent_evaluation.json`

Expected: 输出准确率、工具选择、记忆、确认安全、任务成功率和 P50/P95；实测结果写入实验文档。

- [ ] **Step 7: 安装最新 APK 并运行全部 Maestro**

先按项目约束卸载旧 `com.fandazi.app` 和干扰应用 `com.dirror.music`，再安装本次 debug APK。

Run: `maestro test maestro/group-buying-99-full-regression.yaml`

Expected: 浏览、排序、登录门槛、AI 文字/卡片、购买、支付、券、核销、退款、评价和记忆管理完整通过。

- [ ] **Step 8: 验证降级与回滚**

停止新 Agent 后，普通团购页面、购买和订单仍可使用，AI 标签显示暂不可用；恢复或回滚 Agent 镜像后会话可以继续，不启动旧 Agent Service。

- [ ] **Step 9: 更新最终文档和实验记录**

`docs/verification-report.md` 写入实际命令、日期、环境和结果；Agent 实验文档写入本轮实测指标；架构文档列出所有最终文件和运行方式。

- [ ] **Step 10: 提交最终集成**

```bash
git add evaluation tests Dockerfile docs
git commit -m "test: add pluggable group buying agent evaluation"

git add backend/Dockerfile docker-compose.integration.yml start-fandazi-services.ps1 maestro docs
git commit -m "chore: complete group buying integration verification"
```

## Task Dependency Order

```text
Task 1 契约
  -> Task 2 团购领域
  -> Task 3 Agent 运行协议
  -> Task 4 浏览推荐
  -> Task 5 AI 聊天卡片
  -> Task 6 购买支付
  -> Task 7 券与核销
  -> Task 8 售后支持
  -> Task 9 意图语料与评测扩充
  -> Task 10 长期记忆
  -> Task 11 外卖退役
  -> Task 12 评测与最终验收
```

Task 4 完成后，Task 5 与 Task 9 可以在文件边界不冲突时并行；Task 6、7、8 必须按业务状态依赖顺序执行。Task 11 必须等所有替代路径通过后再删除旧运行代码。
