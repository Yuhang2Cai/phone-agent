# SDD ledger — plan: docs/superpowers/plans/2026-09-09-fandazi-ai-implementation.md

## 执行上下文

- 工作区：`C:\Users\caid0\Documents\ChatGPT\手机agent\.worktrees\fandazi-ai`
- 分支：`feature/fandazi-ai`
- 计划：`docs/superpowers/plans/2026-09-09-fandazi-ai-implementation.md`
- 设计依据：`docs/superpowers/specs/2026-09-09-fandazi-ai-design.md`
- 基线：`422381f chore: ignore isolated workspaces`

## 计划冲突扫描

| 范围 | 检查内容 | 结果与裁定 |
|---|---|---|
| 任务 1 | 工作区目录、环境变量和忽略规则 | 与全局约束一致；先建立 `.gitignore`，不提交密钥。裁定：通过。 |
| 任务 2 | UI 原型页面与任务 7/10 的页面接口 | 原型独立使用本地状态，后续 Android 只复用页面和交互约定；无共享源码冲突。裁定：通过。 |
| 任务 3 | 认证接口与任务 4/9 的用户身份 | 任务 3 产出 JWT 和 `GET /api/users/me`，后续通过认证上下文获取 `userId`；接口一致。裁定：通过。 |
| 任务 4 | 菜单、地址、购物车、订单接口与任务 5/7/9 | 任务 4 产出业务事实，任务 5 导入数据，任务 7 调用接口，任务 9 调用工具；顺序正确。裁定：通过。 |
| 任务 5 | 种子数据与任务 4/9 的字段 | 数据包含商户、菜品、规格、地址、订单和场景；字段可覆盖工具和测试。裁定：通过。 |
| 任务 6 | Android 网络、会话、图片加载与任务 7/10 | 任务 6 产出 `ApiClient`、会话和图片入口，任务 7/10 只依赖这些边界。裁定：通过。 |
| 任务 7 | Android 标准外卖流程与任务 4 的接口 | DTO 和 Repository 按任务 4 的 HTTP 接口实现；无客户端直接修改业务状态。裁定：通过。 |
| 任务 8 | ASR 与任务 10 的 Agent 输入框 | ASR 仅产出文本，不改变 Agent 协议；输入框接收文本后由任务 10 发送。裁定：通过。 |
| 任务 9 | Agent 工具、授权、SSE 与任务 4/10 | 写工具复用订单服务，授权在后端校验，Android 只消费事件；边界清晰。裁定：通过。 |
| 任务 10 | Agent UI 与任务 6/7/9 的接口 | UI 依赖 OkHttp、订单 Repository 和 SSE Agent Repository；顺序正确。裁定：通过。 |
| 任务 11 | 跨层测试与前面所有任务 | 只在前置任务完成后执行，验证既有契约；无反向依赖。裁定：通过。 |
| 全计划 | 多 Agent、真实支付、第三方 App、地图和实时 ASR | 均被全局约束明确排除；无任务偷偷重新引入。裁定：通过。 |

## 计划缺陷裁定

Ruling: 计划可以按任务顺序执行 — 设计文档是权威依据，任务边界和接口没有发现阻塞性矛盾；若实现中出现冲突，以设计文档为准并在本账本追加裁定。

Ruling: 中文任务标题补充括号内的 `Task N` 标记 — 子任务提取脚本按该标记定位任务；标题主体仍保持中文，执行内容不变。

## 任务状态

- Task 1: complete (commits 422381f..1d8a496, review clean)
- Task 2: fix round 1/5 (addressed 3 original gaps; review found 1 input escaping issue; commits b02bdf5..fcec554)
- Task 2: fix round 2/5 (addressed input escaping; commit ee25609)
- Task 2: minor deferred — browser file:// Console walk-through remains manual; reviewer marked nonblocking
- Task 2: complete (commits 785e98e..ee25609, review clean)
- Task 3: fix round 1/5 continued — added real H2 unique-conflict coverage and narrowed exception handling (commit dd18c8a)
- Task 3: minor deferred — invalid/expired JWT dedicated regression tests and cross-database constraint-message portability can be strengthened later; reviewer marked nonblocking
- Task 3: complete (commits 1a83801..dd18c8a, final review PASS)
- Task 4: pending — next domain implementation brief to be prepared
- Task 4: fix round 1/5 completed — response DTOs, options, stock atomicity, delivery boundary and idempotency/invariant hardening committed through 30db823; 20 domain and 30 full backend tests pass
- Task 4: fix round 2/5 in progress — final review found HTTP order/payment lazy-loading response risk; also adding overflow guard and multi-item rollback regression test
- Task 4: fix round 2/5 completed — DTO conversion, overflow guard and multi-item rollback test committed through 2330ed1; 24 domain and 34 full backend tests pass
- Task 4: fix round 3/5 in progress — review found discount calculation/snapshot missing; also tightening input validation and domain status invariants
- Task 4: fix round 3/5 completed — server promotions/discount snapshot, validation, state guards and HTTP isolation/idempotency tests committed through fb05f7c; 39 full backend tests pass
- Task 4: fix round 4/5 in progress — final review found DeliveryService can bypass payment; also tightening draft and merchant pagination input validation
- Task 4: fix round 4/5 completed — DeliveryService payment boundary, draft ID validation and merchant paging bounds committed through b2ee286; 41 full backend tests pass
- Task 4: complete (final review PASS; commit range 45187bd..b2ee286)
- Task 5: pending — deterministic synthetic data brief to be prepared
- Task 5: implementation complete (commit 6674257); read-only review pending
- Task 5: fix round 1/5 in progress — review found invalid BCrypt fixture, hard-coded orders, and unconsumed price/address semantics
- Task 5: fix round 1/5 completed — valid BCrypt, JSON-driven orders, seed metadata and switch/idempotency tests committed through 38d3e8b; 44 full backend tests pass
- Task 5: fix round 2/5 in progress — review found checked-exception partial commit risk, incomplete metadata, missing local placeholders, and stale review counts
- Task 5: fix round 2/5 continued — rollback recovery test, complete metadata fixtures, local PNG resources, scenario parsing and review-package sync remain
- Task 5: fix round 2/5 completed — rollback recovery, complete metadata, local PNG resources, scenario parsing and review-package sync committed through ba6ef0f; 49 full backend tests pass
- Task 5: fix round 3/5 in progress — review found missing production schema migration, incomplete orders fixture sourcing, and stringified metadata arrays
- Task 5: fix round 3/5 completed — Flyway schema, complete orders fixture sources and typed metadata arrays committed through f1239f0; 53 full backend tests pass
- Task 5: complete (final review PASS; commit range 6674257..f1239f0)
- Task 6: pending — Android shell/auth/network/image brief to be prepared
- Task 6: implementation complete (commit 0c25bfc); build verification blocked by environment; read-only review pending
- Task 6: fix round 1/5 in progress — review found local HTTP cleartext policy, backend 400/error mapping mismatch, and incomplete process-restore routing
- Task 6: fix round 1/5 completed — cleartext/debug policy, error mapping, restore routing, submit race and encrypted write handling committed through b923666; build still environment-blocked
- Task 6: fix round 2/5 in progress — review found duplicate navigation plus release URL, error classification, secure storage failure, and verification-record gaps
- Task 6: fix round 2/5 completed — unified navigation, build-type URLs, error classification, secure-storage failure handling and docs committed through 6c416cb; XML static checks pass, Gradle environment-blocked
- Task 6: fix round 3/5 completed — Kotlin parameter fix, logout error feedback and verification docs committed through 424eef9; XML static checks pass, Gradle environment-blocked
- Task 6: fix round 4/5 in progress — review found Kotlin DSL string syntax error, silent encrypted read failure, and uncommitted review-doc sync
- Task 6: fix round 4/5 completed — Kotlin DSL URL syntax, session read failure propagation, user feedback and docs committed through 126b562; XML static checks pass, Gradle environment-blocked
- Task 6: fix round 5/5 in progress — review found unstable Gradle test path, mode-switch submit-lock deadlock, and cancellation misclassification
- Task 6: fix round 5/5 completed — stable API/debug test paths and cancellation lock release committed through b0a0ca0
- Task 6: fix round 6/6 completed — final path/cancellation/doc hardening committed through 0039af5; XML static checks pass, Gradle environment-blocked
- Task 6: final read-only review pending
- Task 6: fix round 5/5 completed — stable test path, submission-state preservation and cancellation propagation committed through b0a0ca0; XML static checks pass, Gradle environment-blocked
- Task 6: fix round 6/6 in progress — user approved extra loop for build-type test assertion, cancellation lock release, debug manifest path and review-doc synchronization
- Task 6: fix round 6/6 completed — final static-test path, cancellation cleanup and documentation fixes committed through 0039af5
- Task 6: final review completed — no new logic defects found; Gradle/Kotlin/APK verification remains blocked by missing Android SDK and unavailable offline plugin artifacts
- Task 7: blocked at checkpoint — Android build environment must be configured before continuing Android-dependent implementation
- Task 3: pending
- Task 4: pending
- Task 5: pending
- Task 6: pending
- Task 7: pending
- Task 8: pending
- Task 9: pending
- Task 10: pending
- Task 11: pending

## 计划修订（2026-09-10）

- Ruling: Task 9 拆分为 Python LangChain + LangGraph Agent Service，Task 10 承担 Spring Boot Agent Gateway、工具授权和 SSE — 这样 LangGraph 只负责编排，Spring Boot 继续作为订单、金额、地址、库存、授权和幂等性的最终裁决方；代价是新增 Python 服务、跨服务契约和本地运行配置。
- Ruling: 计划标题统一为 `### Task N` — 任务提取脚本按英文标题定位任务，中文标题内容保留。
- Task 9: in progress — Agent Service implementer dispatched; brief `task-9-brief.md`.
- Task 9: fix round 1/5 in progress — review found unsafe negative confirmation, cross-user session reuse, unused LangGraph topology, terminal-state reuse, incomplete hard limits, unstable idempotency, and missing boundary tests; review `task-9-review.md`.
- Task 9: fix round 1/5 completed — C1/I2/I3/M1/M2 addressed in `d484ad9`; scoped re-review still found C2/I1/I4/I5 open plus two Important recovery/idempotency issues; review `task-9-r1-review.md`.
- Task 9: fix round 2/5 in progress — require trusted workflow/version validation, repeatable interrupt handling after non-confirmation, retention of partial-success workflow for payment retry, and regression coverage.
- Task 9: fix round 2/5 completed — I1 and repeated interrupt path addressed in `8df0975`; scoped re-review found C2/I4/I5 open plus snapshot integrity and resume-context/预算 Important findings; review `task-9-r2-review.md`.
- Task 9: fix round 3/5 in progress — require canonical identity/state signatures, uncertain create_order recovery, current trace propagation, per-invocation budget reset, and boundary tests.
- Task 9: fix round 3/5 completed — C2 and resume context addressed in `6c1d06e`; scoped re-review found I4/I5 open plus explicit rejection recovery/cancel Important issue; review `task-9-r3-review.md`.
- Task 9: fix round 4/5 in progress — fresh implementer must add durable order-recovery snapshot restore, retryable-vs-deterministic error classification, recovery cancellation, and regression tests.
- Task 9: fix round 4/5 completed — durable recovery, stable idempotency, deterministic rejection cleanup and cancellation committed in `8af1df7`; 122 Agent Service tests pass.
- Task 9: complete (commits `8d83262..8af1df7`, review clean; final review `task-9-r4-review.md`).
- Task 10: in progress — Spring Boot Agent Gateway, internal tool authorization and SSE implementer to be dispatched.
- Task 10: fix round 1/5 in progress — review found missing Flyway schema, Python/Spring token mismatch, reusable confirmation authorization, non-live SSE, idempotency recovery ordering, unbound auto payment, and missing automation configuration entry; review `task-10-review.md`.
- Task 10: fix round 1/5 completed — Flyway, token contract, authorization lifecycle, live SSE, idempotency recovery, auto-payment binding and automation APIs committed in `9e30edf`; review found concurrent authorization issues.
- Task 10: fix round 2/5 completed — pessimistic authorization consumption, request fingerprint conflict and default-address semantics committed in `184687e`; backend 78 tests and Agent Service 125 tests pass.
- Task 10: complete (commits `1b1b8aa..184687e`, review clean; final review `task-10-r2-review.md`).
- Task 11: in progress — Android Agent UI and end-to-end flow implementer to be dispatched.
- Task 11: fix round 1/5 in progress — review found incomplete confirmation fail-open, main-thread HTTP, non-revoked automation, first-SSE lifecycle, FAILED mapping, stale terminal card and missing candidateCart/UI coverage; review `task-11-review.md`.
- Task 11: fix round 1/5 completed — confirmation fail-closed, IO dispatch, revoke flow, SSE lifecycle, FAILED mapping, terminal cleanup and state/test hardening committed in `7143439`; Android Agent tests 17/17 and lint pass.
- Task 11: minor deferred — POST/SSE event ID deduplication can still show one event twice; reviewer marked nonblocking.
- Task 11: complete (commits `d8780e4..7143439`, review clean for Critical/Important; review `task-11-r1-review.md`).
- Task 12: complete (initial commit `b978d70` documented and verified cross-layer materials; review fix commit `test: close task 12 review findings` adds seed confirmation resume coverage, separates Android fake-test coverage from production authorization/idempotency claims, and keeps secrets out of committed artifacts).
