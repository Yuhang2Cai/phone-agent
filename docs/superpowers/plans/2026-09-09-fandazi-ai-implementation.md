# 饭搭子 AI 实施计划

> **给执行者：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，按任务逐项执行。本计划使用复选框跟踪进度。

**目标：** 构建一个可安装的 Android 外卖 App，采用 XML + MVVM，配套 Spring Boot 后端、受约束的单 Agent 点餐流程、可编辑 HTML/CSS 原型和可重复的测试数据。

**架构：** Android 负责页面、本地 ASR、缓存和用户授权；Spring Boot 负责登录、菜单、购物车、地址、订单、模拟支付、配送状态、Agent 编排、工具授权和持久化。`FoodOrderingAgent` 只能调用服务端白名单工具，Android 通过 SSE 接收执行事件。

**技术栈：** Kotlin、Android XML、MVVM、OkHttp、Glide、Room、Coroutines/Flow、Sherpa-ONNX Paraformer INT8、Java 17、Spring Boot、Spring Security/JWT、Spring Data JPA、MySQL、SSE、OpenAPI/Knife4j、HTML/CSS/JavaScript。

**设计文档：** `docs/superpowers/specs/2026-09-09-fandazi-ai-design.md`

## 全局约束

- 产品名称为“饭搭子 AI”。
- Android 使用 XML、MVVM、OkHttp 和 Glide。
- 大模型密钥、Base URL 和模型名称只保存在服务端配置中。
- 首版只有一个 `FoodOrderingAgent`，不使用多 Agent。
- 默认下单必须确认；自动模式受默认地址和金额上限约束。
- “按上次配置复购”锁定原商家、菜品、数量和规格；不可用时停止，不跨店替换。
- 支付为模拟支付；支付后的配送状态由后端状态机推进。
- 只开发用户端，不开发商家端、骑手端、运营后台、真实支付、地图选点或第三方 App 自动化。
- 测试数据由项目提供，必须可重复导入并包含异常样本。
- 本地 ASR 模型随 APK 打包，录音结束后再识别。

---

### 任务 1（Task 1）：建立工作区结构和运行约定

**文件：** `README.md`、`.gitignore`、`ui-prototype/README.md`、`backend/README.md`、`android-app/README.md`、`data/seed/README.md`

**产出：** 创建 `android-app`、`backend`、`ui-prototype`、`data` 和 `docs` 目录；约定后端 API 根路径为 `http://localhost:8080/api`。

- [ ] 记录 Android Studio、JDK 17、MySQL、后端启动、Android 构建和 HTML 原型预览方式。
- [ ] 记录 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`、`JWT_SECRET` 的配置含义，并明确禁止提交密钥。
- [ ] 忽略 `.gradle/`、`build/`、`.idea/`、`local.properties`、APK、日志、`.env` 和模型压缩包。
- [ ] 执行 `Get-ChildItem -Force`，确认目录完整。
- [ ] 提交：`git add README.md .gitignore ui-prototype/README.md backend/README.md android-app/README.md data/seed/README.md && git commit -m "chore: establish 饭搭子 AI workspace"`

### 任务 2（Task 2）：制作可编辑 HTML/CSS UI 原型

**文件：** `ui-prototype/index.html`、`ui-prototype/styles.css`、`ui-prototype/app.js`、`ui-prototype/assets/README.md`

**产出接口：** `app.js` 管理 `currentPage`、`cart`、`agentMode`、`orderStatus` 和 `agentEvents`；页面包括 `login`、`home`、`search`、`merchant`、`checkout`、`payment`、`orders`、`order-detail`、`addresses`、`profile`、`agent`。

- [ ] 以 390 × 844 为基准，使用明亮黄色、浅灰白背景、白色卡片和蓝紫色 AI 强调色；用 CSS 变量集中管理颜色、字号、间距、圆角和阴影。
- [ ] 制作登录/注册、首页、搜索、商家菜单、规格选择、购物车、确认订单、模拟支付、订单列表/详情、地址、个人中心、自动化设置和 AI 助手页面。
- [ ] 实现 `navigate(page)`、`addToCart(dishId, options)`、`changeQuantity(itemId, delta)`、`submitMockPayment()`、`advanceOrderStatus()`、`toggleAutomation()` 和 `runAgentDemo(prompt)`。
- [ ] 让 `runAgentDemo` 依次产生“解析意图、搜索商家、查询菜品、构建购物车、校验订单、等待确认”事件。
- [ ] 在浏览器中完成人工验收：登录 → 首页 → 商家 → 规格 → 购物车 → 结算 → 支付 → 订单详情，以及悬浮助手 → 执行事件 → 确认卡片。
- [ ] 提交：`git add ui-prototype && git commit -m "feat: add editable 饭搭子 AI ui prototype"`

### 任务 3（Task 3）：建立 Spring Boot 后端基础和认证

**文件：** `backend/pom.xml`、`backend/src/main/java/com/fandazi/FandaziApplication.java`、`backend/src/main/java/com/fandazi/core/security/`、`backend/src/main/java/com/fandazi/feature/auth/`、`backend/src/main/resources/application.yml`、`backend/src/test/java/com/fandazi/feature/auth/AuthControllerTest.java`

**接口：** `POST /api/auth/register`、`POST /api/auth/login` 返回 `{ token, user }`；`GET /api/users/me` 根据 Bearer Token 返回当前用户。

- [ ] 先写测试：注册成功、用户名重复、登录成功、密码错误、缺少 Token。
- [ ] 执行 `mvn -f backend/pom.xml -Dtest=AuthControllerTest test`，确认实现前失败。
- [ ] 实现 `UserEntity`、`UserRepository`、BCrypt、`AuthService`、`AuthController`、JWT 和无状态 `SecurityFilterChain`。
- [ ] 重新执行测试，确认通过。
- [ ] 提交：`git add backend && git commit -m "feat: add backend authentication"`

### 任务 4（Task 4）：实现商家、地址、购物车、订单、支付和配送状态

**文件：** `backend/src/main/java/com/fandazi/feature/catalog/`、`address/`、`cart/`、`order/`、`payment/`、`delivery/`；测试文件为 `OrderServiceTest.java` 和 `CartServiceTest.java`。

**接口：**

- `GET /api/merchants?keyword=&category=&maxDeliveryMinutes=&maxPrice=` 分页搜索商家。
- `GET /api/merchants/{merchantId}/dishes` 返回菜单、规格、库存和图片地址。
- `GET/POST/PUT/DELETE /api/addresses` 管理地址；`PATCH /api/addresses/{id}/default` 设置默认地址。
- `POST /api/cart/drafts`、`PUT /api/cart/drafts/{id}/items`、`POST /api/cart/drafts/{id}/validate` 管理候选购物车。
- `POST /api/orders` 使用幂等键创建 `PENDING_PAYMENT` 订单。
- `POST /api/orders/{id}/pay` 将订单变为 `PAID` 和 `MERCHANT_ACCEPTED`。
- `GET /api/orders/{id}` 返回订单及配送时间线。

- [ ] 先写业务测试：地址归属、默认地址唯一、下架菜品拒绝、价格重新计算、起送价、订单幂等、重复支付拒绝和状态流转。
- [ ] 实现 `MerchantEntity`、`DishEntity`、`DishOptionEntity`、`AddressEntity`、Repository、DTO 和 Controller。
- [ ] 购物车校验时重新读取菜品和规格，计算小计、配送费、优惠和总价，返回不可变快照。
- [ ] 订单保存地址和菜品快照；相同用户重复使用同一 `Idempotency-Key` 时返回原订单。
- [ ] 实现 `PENDING_PAYMENT → PAID → MERCHANT_ACCEPTED → PREPARING → RIDER_PICKED_UP → DELIVERING → DELIVERED`。
- [ ] 执行 `mvn -f backend/pom.xml -Dtest=OrderServiceTest,CartServiceTest test` 并提交：`git add backend && git commit -m "feat: add food ordering domain"`

### 任务 5（Task 5）：制作可重复的合成测试数据

**文件：** `data/seed/merchants.json`、`dishes.json`、`users.json`、`addresses.json`、`orders.json`、`agent-scenarios.json`、`backend/src/main/resources/db/seed/SeedDataLoader.java`、`SeedDataLoaderTest.java`

**产出：** 约 12 家商户、100–140 个菜品，覆盖快餐、盖饭、面食、粥汤、轻食、饮品和地方小吃；包含评分、配送费、配送时间、优惠、辣度、配料、过敏标签和库存状态。

- [ ] 先写测试，断言商户/菜品数量、规格组、下架样本、价格变化、默认/失效地址和已支付历史订单。
- [ ] 整理“30 元以内想吃辣的”“按上次配置再来一单”“我感冒了中午推荐清淡一点”“不要香菜使用默认地址”等场景。
- [ ] 增加地址缺失、价格变化、复购菜品下架、金额超限和非法参数场景。
- [ ] 使用 `seed.enabled` 配置控制加载；执行 `mvn -f backend/pom.xml -Dtest=SeedDataLoaderTest test`。
- [ ] 菜单与价格使用合成数据，图片使用本地授权素材或占位图；提交：`git add data backend/src/main/resources/db/seed backend/src/test/java/com/fandazi/seed && git commit -m "test: add deterministic food ordering data"`

### 任务 6（Task 6）：实现 Android 外壳、认证、OkHttp 和 Glide

**文件：** `android-app/settings.gradle.kts`、`build.gradle.kts`、`app/src/main/AndroidManifest.xml`、`app/src/main/java/com/fandazi/app/core/network/`、`core/image/ImageLoader.kt`、`feature/auth/`、`res/layout/`。

**产出接口：** `ApiClient` 封装 OkHttp 和 `ApiError`；`SessionRepository` 暴露 `StateFlow<SessionState>` 与 `suspend login/register/logout`；`ImageLoader.load(ImageView, url, placeholder, error)` 统一调用 Glide。

- [ ] 写登录、凭证错误、超时、退出登录和进程重建测试。
- [ ] 建立单 Activity、Navigation、Fragment、ViewModel、生命周期收集和共享主题；所有页面使用 XML。
- [ ] 配置 OkHttp 超时、JWT 拦截器、JSON 解析、幂等 GET 的一次重试和服务端错误码映射。
- [ ] 配置 Glide 内存/磁盘缓存、圆角转换、占位图和错误图；图片失败不能阻断文字展示。
- [ ] 实现登录注册表单、字段错误、重复提交保护、Token 保存和首页路由。
- [ ] 在 `android-app` 执行 `.\gradlew.bat testDebugUnitTest assembleDebug`，确认通过并生成 APK。
- [ ] 提交：`git add android-app && git commit -m "feat: add android shell and network foundation"`

### 任务 7（Task 7）：实现 Android 外卖流程、地址、订单和模拟支付

**文件：** `android-app/app/src/main/java/com/fandazi/app/feature/home/`、`catalog/`、`cart/`、`address/`、`order/`、`res/layout/` 及对应测试目录。

- [ ] 测试搜索加载/错误/空状态、分类、规格、数量、地址、结算金额、支付结果和订单刷新。
- [ ] 实现首页、搜索、商家卡片、菜品卡片、配送信息、骨架屏、空状态和重试状态。
- [ ] 使用 RecyclerView 展示菜单分类和规格弹窗；用 Room 保存离开页面时的购物车草稿。
- [ ] 实现地址新增、编辑、删除和默认地址；自动化依赖的唯一默认地址不得直接删除。
- [ ] 实现结算、幂等键、模拟支付和订单详情；未结束订单刷新详情，结束后停止刷新。
- [ ] 执行单元测试，安装 APK，完成注册 → 搜索 → 加购 → 结算 → 支付 → 配送时间线。
- [ ] 提交：`git add android-app && git commit -m "feat: add android food ordering flow"`

### 任务 8（Task 8）：集成本地 ASR 模型

**文件：** `feature/asr/SpeechRecognizer.kt`、`AudioRecorder.kt`、`AsrViewModel.kt`、ASR 资源和测试。

**来源：** 从 `D:\project\redBook` 复制 `sherpa-onnx-1.12.39.aar`、`sherpa-onnx-classes.jar`、Paraformer 的 `model.int8.onnx` 和 `tokens.txt`；不复制聊天业务和敏感配置。

- [ ] 校验模型文件大小和校验和。
- [ ] 测试权限拒绝、空结果、成功结果、初始化失败和失败重试。
- [ ] 实现按住录音、松开结束、16 kHz 单声道 PCM 解码、后台识别、临时文件清理和空结果拦截。
- [ ] 将结果回填 AI 输入框，允许编辑后发送；不做实时转写和语音播报。
- [ ] 执行 `.\gradlew.bat assembleDebug`，检查 APK 体积并在设备上完成中文点餐语音测试。
- [ ] 提交：`git add android-app && git commit -m "feat: add offline chinese asr"`

### 任务 9（Task 9）：实现受约束的单 Agent 和 SSE 事件

**文件：** `backend/src/main/java/com/fandazi/feature/agent/`、`automation/`、`preference/` 及对应测试。

**接口：**

- `POST /api/agent/sessions/{sessionId}/messages` 接收 `{ text, mode }`，`mode` 为 `CONFIRM` 或 `THIS_TASK_AUTO`。
- `GET /api/agent/sessions/{sessionId}/events` 推送 `AgentEvent`，包含 `type`、`summary`、`toolName`、`status` 和可选 `payload`。
- `AgentOrchestrator.handle(userId, sessionId, text, mode)` 返回终态 `AgentResult`。

- [ ] 测试普通推荐、缺少信息追问、确认下单、任务自动下单、长期策略、精确复购、复购下架终止和非法工具拒绝。
- [ ] 定义工具请求/响应对象；`userId`、`sessionId` 和幂等上下文由服务端注入。
- [ ] 实现 `AutomationPolicyService` 和 `OrderAuthorizationService`，在 `createOrder` 前校验授权、地址、金额、价格、库存和有效期。
- [ ] 通过 `LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL` 配置模型；只执行已注册工具，限制工具循环次数，不输出隐含推理。
- [ ] 从历史订单提取候选偏好，只有用户确认后才写入长期偏好；过敏和忌口优先。
- [ ] 对“我感冒了”等输入只做一般饮食筛选，不诊断、不处方、不声称食物治疗疾病。
- [ ] 发布并持久化解析、搜索、筛选、加购、校验、确认、创建订单、支付、失败和完成事件，支持最后事件 ID 重连。
- [ ] 执行 `mvn -f backend/pom.xml -Dtest=AgentOrchestratorTest,AutomationPolicyTest test` 并提交：`git add backend && git commit -m "feat: add constrained food ordering agent"`

### 任务 10（Task 10）：接入 Android Agent UI 和端到端流程

**文件：** `feature/agent/`、`fragment_agent_sheet.xml`、`item_agent_event.xml`、`item_order_confirmation.xml`、首页/搜索/商家/订单 Fragment 和测试。

- [ ] `AgentViewModel` 暴露消息、事件、候选购物车、确认卡片、授权模式和错误状态。
- [ ] 测试助手打开/关闭、半屏/全屏、文字发送、ASR 回填、事件展示、确认/取消和 SSE 断线恢复。
- [ ] 在首页、搜索、商家和订单页面展示 AI 浮球；认证和模拟支付页面隐藏。
- [ ] 实现半屏/全屏聊天、快捷指令、麦克风、事件卡片、重试和购物车摘要。
- [ ] 订单确认卡片展示商家、菜品、规格、地址、配送费、优惠、总价、授权模式和金额上限。
- [ ] 运行主场景：“我感冒了，中午想吃清淡点，不要香菜，30 元以内，按默认地址帮我点。”
- [ ] 提交：`git add android-app && git commit -m "feat: integrate android food ordering agent"`

### 任务 11（Task 11）：完成跨层测试、文档和作品集材料

**文件：** `docs/api-and-agent.md`、`docs/architecture.md`、`docs/interview-notes.md`、后端集成测试、Android 流程测试和 `README.md`。

- [ ] 用独立测试数据库或 H2 验证注册 → 菜单 → 地址 → 购物车 → 订单 → 支付 → 配送状态，并验证资源归属和幂等。
- [ ] 使用 Fake API Client 覆盖 Android 登录、搜索、规格、购物车、地址、结算、支付、订单和 Agent 确认。
- [ ] 执行 `data/seed/agent-scenarios.json`，比较实际工具序列、确认要求和结果，生成 Markdown 报告。
- [ ] 文档说明 MVVM、OkHttp/Glide、ASR 体积取舍、SSE、工具授权、幂等性和单 Agent 选择。
- [ ] 执行 `mvn -f backend/pom.xml test` 和 `.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest assembleDebug`，确认跟踪文件中没有密钥和个人数据。
- [ ] 制作 UI 原型、APK、ASR、Agent 事件、确认卡片、模拟支付、订单时间线和异常恢复截图/视频。
- [ ] 提交：`git add README.md docs backend/src/test android-app/app/src/androidTest && git commit -m "test: document and verify 饭搭子 AI"`

## 阶段验收点

- **验收点 A：** 任务 1–2 完成可导航、可编辑的 UI 原型。
- **验收点 B：** 任务 3–5 完成有种子数据、可独立测试的后端。
- **验收点 C：** 任务 6–8 完成可安装、支持离线 ASR 的 Android 外卖 App。
- **验收点 D：** 任务 9–10 完成 Agent 与 Android 的连接。
- **验收点 E：** 任务 11 完成 APK、测试报告、技术文档和作品集材料。

每个验收点的测试通过后，才能进入下一个验收点。
