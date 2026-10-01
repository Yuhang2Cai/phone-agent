# Task1 跨仓库契约开发报告

日期：2026-10-01。角色：契约/测试实现 Agent。
状态：Python 契约开发自测通过；Java 已修正集合别名解析上限，待父线程串行复跑；待独立验收。

本报告只证明下面列出的开发工作与实测，不宣称 Task1 验收通过。没有勾选计划、提交代码、修改 Android/Java/Kotlin 运行时代码或派发独立验收 Agent。

## 1. 输入与环境

已先读取两仓根 AGENTS.md、手机实施计划 Task1 和设计规格第 7/8 节，并读取 backend/pom.xml、真实工具分发、退款/投诉准备、报价、订单、券、FAQ、优惠券、卡片及领域状态定义。

- 手机根：`C:/Users/caid0/Documents/ChatGPT/手机agent/.worktrees/fandazi-ai`；检查时 HEAD：`07b865a8d4f9cc333e0dc32f465f0d358b37ba5e`。
- 新 Agent 根：`D:/project/-Demo-master`；检查时 HEAD：`878c109e90e7b0490198d0f84c569b7cd1a30368`。
- 两仓均已有大量未提交改动。保留原有改动，未回滚、未清理、未提交。
- Python：`C:/Users/caid0/anaconda3/envs/pinnT/python.exe`；PyYAML 6.0.3、pytest 可用。
- Maven：`D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd`。
- JAVA_HOME：`D:/software/IntelliJ IDEA 2025.2.4/jbr`，入口存在。
- Maven 由父线程串行运行。本 Agent 遵循协调要求，没有启动 Maven，避免共享 backend/target 编译冲突。
- 测试均为离线契约/合成载荷检查，不连接数据库或模型，不使用真实用户、订单、凭据或密钥。

## 2. 本 Agent 改动文件

| 仓库 | 文件 | 改动 |
|---|---|---|
| 手机 | contracts/agent-protocol-v1.yaml | 1.1 请求、状态、事件、公开卡片、动作、摘要、内部凭证及兼容边界 |
| 手机 | contracts/agent-tools-v1.yaml | 17 工具、可校验输入输出、领域资源、准备副作用、确认与幂等约束 |
| 手机 | backend/src/test/java/com/fandazi/feature/agent/AgentContractSchemaTest.java | 用 SnakeYAML 结构化解析替换正则；5 个语义检查；重复键拒绝、集合别名上限 500 |
| 手机 | docs/verification/2026-10-01-task1-contract-development.md | 本开发报告及可复跑的额外载荷检查脚本 |
| 新 Agent | config/contracts/agent-protocol-v1.yaml | 手机权威协议的逐字节受控副本 |
| 新 Agent | config/contracts/agent-tools-v1.yaml | 手机权威工具契约的逐字节受控副本 |
| 新 Agent | config/contracts/source.sha256 | 保留历史单行工具摘要入口并更新值 |
| 新 Agent | config/contracts/manifest.yaml | 新增协议与工具的独立 SHA-256 清单 |
| 新 Agent | tests/test_cross_repo_contracts.py | 34 个结构化契约检查，包括普通工具嵌套字段、摘要和真实双仓核对 |

所有新增说明、代码注释及测试文档字符串均为中文。未修改其他测试、服务入口、启动脚本、计划勾选或历史实验基线。父线程的运行时、其他测试、pom/resources 和追加迁移改动不归入本报告的实现范围。

## 3. 契约与兼容决策

1. 两份契约均使用 `schemaVersion: "1.1"`，文件名仍为 v1。请求只接受 `query`，拒绝 `text` 等额外权威字段。
2. Spring 分配首次 `runId`；补充输入与确认恢复沿用它；每次用户输入产生新 `traceId`。序号在本轮链路递增，进度按 `runId/stepId` 合并。
3. `input_required/waiting_input` 与 `confirmation_required/waiting_confirmation` 的 `roundDisposition` 为 `paused`：结束本轮，任务未结束。`run_completed` 只允许 succeeded/cancelled；失败由 error/failed 结束。没有增加名为 paused 的协议事件。
4. 卡片事件的载荷为 `{card:{schemaVersion,cardType,resourceId,data,actions}}`。`data` 按 8 类卡片条件选择具体 schema，动作明确包含 actionId/actionType/label/resourceId/enabled。券候选使用 voucher 卡与 select_voucher。
5. 暂停输入载荷为 question/inputKey/candidates；确认载荷为 summary/actions。退款摘要必须包含套餐及原因，投诉摘要必须包含主题与正文。公开摘要与卡片禁止含确认凭证；tool_result 的公开摘要也拒绝凭证/核销令牌名称。
6. `confirmationSummary.expiresAt` 为可选 date-time，用于展示确认截止时间。`prepare_refund` 输出的 expiresAt 保留当前 API 的“券有效期”含义，不把它当作确认凭证有效期；后者由 Spring 内部凭证记录提供。
7. 请求 context 新增可选布尔 `confirmationAccepted`，只能由可信 Spring 计算，不接受端上自报为授权依据。它不替代写工具的 confirmationGrantId、动作、金额、版本、归属与有效期校验。mode 限定 confirm/this_task_auto/recommend。
8. 内部凭证及工具输入输出统一为 `confirmationGrantId`，不接受 confirmationGrant/grantId。已有准备/报价 API 的旧字段由父线程在集中适配边界归一，不把旧名字扩散到新协议。
9. 工具新增 prepare_refund/prepare_complaint，共 17 项。与 create_purchase_quote 一起声明 WRITE_PREVIEW、不要求预先用户确认、会签发凭证/审计、业务尚未提交，并要求身份、资源归属、限流与审计。四个业务写工具必须明确确认并消费凭证。
10. 普通工具保留真实包装层 stores/store/deals/deal/reviews/orders/order/vouchers/coupons/faqs；集合元素分别定义公开标识、金额、库存、时间、状态或资格。写订单/支付输出采用直接团购订单摘要。
11. FAQ canonical 输入仍为 question，父线程将其映射到历史 keyword；输出保留真实 faqs 数组。创建订单 canonical 输入采用 quoteId/confirmationGrantId，父线程需由真实报价恢复套餐、数量、版本及金额；不能只把字段改名就绕过报价校验。
12. 领域状态按真实枚举保留：订单 PENDING_PAYMENT/PAID/PARTIALLY_USED/USED/REFUNDED/EXPIRED/CANCELLED；退款 REQUESTED/APPROVED/REJECTED/COMPLETED。申请成功不等于退款到账。
13. 时间点声明 date-time，传输目标 UTC；套餐有效日期沿用真实 LocalDate 的 date。资源 ID 暂用非空公开字符串以兼容当前业务事实；UUID、金额存储和页面数据完善仍须 Task2 核对。
14. 幂等键由执行器按 runId/taskId/actionVersion 生成，后端校验请求指纹，结果不确定时先查事实。模型不能决定身份、金额、资格或幂等键。
15. 原 Android 公共 DTO 及父线程报告的取消兼容（CANCELLED 经 JsonValue 映射 COMPLETED、nextAction=cancelled）属于集中适配实现，本 Agent 未修改 Android，也未据契约静态测试宣称该端验收通过。

schema 采用 Draft7 的 type/required/additionalProperties、文档内 $ref 和 if/then；没有用语义正则或注释文本冒充字段校验。解析局部 schema 时必须保留整份权威文档作为引用根，例如以 `#/agentEvent` 为入口；工具执行成功后应另按选中工具的 `output.schema` 校验 data。

## 4. 红绿实测

### 4.1 Python 契约测试

在新 Agent 根执行以下命令。首轮未设置 PYTHONUTF8，失败信息中的中文因控制台编码显示异常；后续设置后输出正常。命令中禁用 pytest 缓存，避免引入额外源码改动。

```powershell
$env:MOBILE_PROJECT_ROOT='C:/Users/caid0/Documents/ChatGPT/手机agent/.worktrees/fandazi-ai'
$env:PYTHONUTF8='1'
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -m pytest tests/test_cross_repo_contracts.py -q --tb=short -p no:cacheprovider
```

| 阶段 | 退出码 | 实际结果 | 证据与含义 |
|---|---:|---|---|
| 测试先增强，原两份 1.0 契约未改 | 1 | 29 failed, 1 passed，0.75s | 版本、input_required、两个准备工具、可校验输入输出及 manifest 缺失均产生预期断言失败；原双仓字节一致通过 |
| 初版结构补齐，尚未新增 manifest | 1 | 1 failed, 29 passed，1.65s | 仅双摘要清单缺失失败 |
| 增强真实领域枚举和确认正文检查 | 1 | 3 failed, 30 passed，1.56s | 抓到订单枚举不符、确认摘要缺少按动作正文约束、manifest 缺失 |
| 修枚举/摘要、同步两仓与双摘要 | 0 | 33 passed，1.36s | 实际配置手机根，未跳过双仓校验 |
| 增强可信确认布尔检查，字段未补 | 1 | 1 failed, 33 passed，1.82s | 缺少 context.confirmationAccepted，预期红灯 |
| 补 context 字段，更新协议摘要 | 0 | 34 passed，1.50s | 17 工具及嵌套资源、卡片、暂停、确认、摘要与真实两仓字节均通过 |

另实际执行错误路径负例，防止配置不存在的手机根时静默返回成功：

```powershell
$env:MOBILE_PROJECT_ROOT='D:/project/-Demo-master/__missing_mobile_for_contract_test__'
$env:PYTHONUTF8='1'
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -m pytest tests/test_cross_repo_contracts.py -q --tb=short -k local_copies_match_mobile_authority -p no:cacheprovider
```

退出码 1，1 failed, 33 deselected，0.20s；失败为明确的 MOBILE_PROJECT_ROOT 不存在。此处是受控负例，非最终真实双仓运行失败。未配置环境变量时测试显式 skip，不能把它算作双仓已核对。

### 4.2 Java 开发测试

父线程统一运行组合 Maven，本 Agent 未并发执行。完整组合命令的 -Dtest 集合未在交接消息中提供，不能冒称自己执行了独立 Maven 红绿命令。

已亲自读取 `backend/target/surefire-reports/TEST-com.fandazi.feature.agent.AgentContractSchemaTest.xml`，实际 5 tests、0 failures、5 errors、0 skipped，均为：

```text
org.yaml.snakeyaml.error.YAMLException:
Number of aliases for non-scalar nodes exceeds the specified max=50
```

已按父线程反馈修复 load：保持 SafeConstructor、setAllowDuplicateKeys(false)，新增 setMaxAliasesForCollections(500)，中文注释说明权威文件通过别名复用类型、放宽但保留上限的原因。这个修正仅改指定 Java 测试，不影响契约字节或摘要。

截至本报告写入时，修正后的 Java Maven 复跑结果尚未交付，状态为“待父线程串行复跑”，不能写 Java GREEN。父线程可用如下针对入口复跑，本段为复跑指令，非已执行结果：

```powershell
$env:JAVA_HOME='D:/software/IntelliJ IDEA 2025.2.4/jbr'
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'
& 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' -f backend/pom.xml '-Dtest=AgentContractSchemaTest' test
```

父线程之前报告的 13 项边界 GREEN，以及工具字段、新标准验证器的其他红绿结果，不在此计作本 Agent 的独立测试结果。

### 4.3 标准 schema 额外检查

当前 Python 环境已有 jsonschema 4.26.0，未修改 requirements，也未引入指定测试的新依赖。使用报告附录脚本通过标准 Draft7Validator 检查 17 工具的正例输入/输出与未知字段/空输出负例、10 事件、请求/结果、暂停/取消、金额、确认正文、卡片、旧别名和凭证泄露。

初次额外检查发现当前环境没有 jsonschema 的可选 date-time 格式检查器；补用 Python datetime.fromisoformat 验证带时区时间，并按格式规范忽略非字符串（允许 nullable 时间）后重跑。最终退出码 0，实际 125 个正反断言通过。此结果属于开发合成载荷检查，不是运行时独立验收；UTC 归一、授权和资源事实仍须由运行时验证。

### 4.4 摘要与差异检查

两个手机权威文件的 SHA-256 与 Agent 副本、manifest 相同：

| 文件 | SHA-256 |
|---|---|
| agent-protocol-v1.yaml | 60420e0e0aadfe82f952de11ac38efa9c3c79778b23d51d59586f48639acb85f |
| agent-tools-v1.yaml | e2b0f42805303ddda0d9fc55af702091364fa62f5ef2eb845a2ca99c4ded21b8 |

`source.sha256` 仍只保存工具摘要的一行 64 位十六进制值；双摘要由 manifest.yaml 补充，避免破坏历史读取方式。pytest 实际比较的是原始字节，未先解析或规范化换行再比较。

两仓针对改动文件的 `git diff --check` 退出码均为 0；仅 Git 的 LF 将在后续写入转换为 CRLF 提示，无空白错误。未来修改或 checkout 后须重新计算摘要并核对原始字节。

## 5. 待验证与交接

- 父线程串行复跑修正后的 AgentContractSchemaTest，回填实际命令、结果和最新 Surefire 证据。
- 父线程完成权威 YAML 打包、标准 networknt 校验及三端集中适配；特別核对公开事件载荷、完整卡片动作、可信 confirmationAccepted 和时间格式。
- quoteId 到报价快照的真实恢复、团购支付的确认与幂等、准备工具限流/审计、各可选筛选参数实际执行情况，不能以本次静态契约检查替代运行验证。
- 售后摘要的 expiresAt 必须取确认凭证截止时间，不能直接复制退款准备输出中的券有效期。
- 取消需终止写入并清理等待；failed 与传输中断必须有可定位错误；不得将暂停、错误或无完整流的数据默认为成功。
- 新 Agent 服务、实际 Spring 工具、真实多轮运行与 Android 集成由父线程安排独立验收。本次没有在线模型或请求并发实验，不把预期结果记为实验实测。
- 未运行独立验收，未勾选计划，未提交。当前整体状态仍为待独立验收，Java 修复待父线程复跑。

## 附录：额外标准载荷检查的实际脚本

在新 Agent 根使用 PowerShell here-string 通过标准输入执行；不会写额外源文件：

```powershell
$env:PYTHONUTF8='1'
@'
import copy
from datetime import datetime
from pathlib import Path
import yaml
from jsonschema import Draft7Validator, FormatChecker

# 当前环境未安装 jsonschema 的可选 RFC3339 依赖，使用标准时间解析器补足格式检查。
checker = FormatChecker()
@checker.checks("date-time", raises=(ValueError, TypeError))
def timestamp(value):
    return not isinstance(value, str) or datetime.fromisoformat(value.replace("Z", "+00:00")).tzinfo is not None

root = Path("config/contracts")
protocol = yaml.safe_load((root / "agent-protocol-v1.yaml").read_text(encoding="utf-8"))
tools = yaml.safe_load((root / "agent-tools-v1.yaml").read_text(encoding="utf-8"))
checks = 0

def validate(document, schema, value, valid=True):
    global checks
    # 标准验证器解析共享引用时保留完整文档根，局部 schema 覆盖入口约束。
    root_schema = dict(document)
    root_schema.update(schema)
    Draft7Validator.check_schema(root_schema)
    errors = list(Draft7Validator(root_schema, format_checker=checker).iter_errors(value))
    assert bool(errors) is not valid, [(list(error.path), error.message) for error in errors]
    checks += 1

# 全部数据均为合成开发夹具，不使用真实身份、订单或凭证。
stamp = "2026-10-01T00:00:00Z"
store = dict(publicId="fixture-store", name="测试门店", brandName="测试品牌", cityCode="fixture-city", address="测试地址", category="测试品类", averageSpendCents=1000, latitude=31, longitude=121, open=True, openingHours="测试时段")
deal = dict(publicId="fixture-deal", storeId="fixture-store", title="测试套餐", description="测试说明", priceCents=1000, originalPriceCents=1200, inventory=5, validFrom="2026-10-01", validTo="2026-10-31", purchaseLimit=2)
voucher = dict(publicId="fixture-voucher", orderId="fixture-order", dealId="fixture-deal", dealTitle="测试套餐", storeId="fixture-store", storeName="测试门店", status="UNUSED", canRefund=True, expiresAt=stamp, redeemedAt=None)
order = dict(orderId="fixture-order", dealId="fixture-deal", storeId="fixture-store", quantity=1, totalCents=1000, status="PAID", voucherCount=1, createdAt=stamp, canReview=False, vouchers=[voucher])
review = dict(storeId="fixture-store", reviewerName="测试评价者", rating=4, content="测试评价", createdAt=stamp)
coupon = dict(couponId="fixture-coupon", claimId="fixture-claim", name="测试优惠券", status="UNUSED", thresholdCents=1000, discountCents=100, validFrom=stamp, validTo=stamp, scopeDescription="测试范围")
faq = dict(id="fixture-faq", question="测试问题", answer="测试答案")
quote = dict(quoteId="fixture-quote", dealId="fixture-deal", quantity=1, subtotalCents=1000, discountCents=200, totalCents=1000, expiresAt=stamp, confirmationGrantId="fixture-grant", dealVersion=1)
refund = dict(requestId="fixture-refund", voucherId="fixture-voucher", status="REQUESTED", reason="测试原因", createdAt=stamp)
ticket = dict(ticketId="fixture-ticket", status="OPEN", subject="测试主题", createdAt=stamp)
fixtures = {
    "search_stores": ({"cityCode": "fixture-city"}, {"stores": [store]}),
    "get_store_detail": ({"storeId": "fixture-store"}, {"store": store}),
    "list_deal_packages": ({"storeId": "fixture-store"}, {"deals": [deal]}),
    "get_deal_detail": ({"dealId": "fixture-deal"}, {"deal": deal}),
    "search_reviews": ({"storeId": "fixture-store"}, {"reviews": [review]}),
    "list_user_orders": ({}, {"orders": [order]}),
    "get_order_detail": ({"orderId": "fixture-order"}, {"order": order}),
    "list_user_vouchers": ({}, {"vouchers": [voucher]}),
    "query_coupon": ({}, {"coupons": [coupon]}),
    "query_faq": ({"question": "测试问题"}, {"faqs": [faq]}),
    "create_purchase_quote": ({"dealId": "fixture-deal", "quantity": 1}, quote),
    "prepare_refund": ({"voucherId": "fixture-voucher"}, dict(voucherId="fixture-voucher", orderId="fixture-order", dealTitle="测试套餐", amountCents=1000, expiresAt=stamp, confirmationGrantId="fixture-grant")),
    "prepare_complaint": ({}, {"orderId": "", "confirmationGrantId": "fixture-grant"}),
    "create_group_buy_order": ({"quoteId": "fixture-quote", "confirmationGrantId": "fixture-grant"}, order),
    "pay_mock_order": ({"orderId": "fixture-order", "confirmationGrantId": "fixture-grant"}, order),
    "request_refund": ({"voucherId": "fixture-voucher", "reason": "测试原因", "confirmationGrantId": "fixture-grant"}, refund),
    "submit_complaint": ({"subject": "测试主题", "description": "测试正文", "confirmationGrantId": "fixture-grant"}, ticket),
}
for tool in tools["tools"]:
    request, response = fixtures[tool["name"]]
    validate(tools, tool["input"], request)
    validate(tools, tool["output"]["schema"], response)
    validate(tools, tool["input"], {**request, "unknownField": True}, False)
    validate(tools, tool["output"]["schema"], {}, False)

action = dict(actionId="fixture-action", actionType="select_voucher", label="测试选择", resourceId="fixture-voucher", enabled=True)
card = dict(schemaVersion="1.1", cardType="voucher", resourceId="fixture-voucher", data=dict(voucherId="fixture-voucher", orderId="fixture-order", dealTitle="测试套餐", storeName="测试门店", status="UNUSED", canRefund=True, expiresAt=stamp), actions=[action])
summary = dict(action="request_refund", resourceId="fixture-voucher", title="测试退款确认", amountCents=1000, ruleVersion="refund-v1", dealTitle="测试套餐", reason="测试原因", expiresAt=stamp)
base = dict(schemaVersion="1.1", eventId="fixture-event", traceId="fixture-trace", runId="fixture-run", sequence=0)
events = {
    "run_started": ("running", {}),
    "intent_decided": ("running", {"intent": "fixture-intent", "isComposite": False}),
    "progress": ("running", {"stepId": "fixture-step", "label": "测试进度", "stepStatus": "running"}),
    "text_delta": ("running", {"delta": "测试文字"}),
    "card": ("running", {"card": card}),
    "input_required": ("waiting_input", {"question": "测试选择问题", "inputKey": "voucherId", "candidates": [card]}),
    "confirmation_required": ("waiting_confirmation", {"summary": summary, "actions": [dict(action, actionType="request_refund")]}),
    "tool_result": ("running", {"toolName": "prepare_refund", "ok": True, "data": {"voucherId": "fixture-voucher"}, "error": None}),
    "run_completed": ("succeeded", {}),
    "error": ("failed", {"code": "DEPENDENCY_UNAVAILABLE", "message": "测试依赖失败", "retryable": True}),
}
for name, (status, payload) in events.items():
    event = dict(base, eventType=name, status=status, payload=payload)
    validate(protocol, {"$ref": "#/agentEvent"}, event)
    validate(protocol, {"$ref": "#/agentEvent"}, dict(event, status="completed"), False)
for status in ("waiting_input", "waiting_confirmation", "failed"):
    validate(protocol, {"$ref": "#/agentEvent"}, dict(base, eventType="run_completed", status=status, payload={}), False)
validate(protocol, {"$ref": "#/agentEvent"}, dict(base, eventType="run_completed", status="cancelled", payload={}))
validate(protocol, {"$ref": "#/confirmationSummary"}, {key: val for key, val in summary.items() if key != "reason"}, False)
validate(protocol, {"$ref": "#/confirmationSummary"}, dict(summary, confirmationGrantId="fixture-grant"), False)
validate(protocol, {"$ref": "#/confirmationSummary"}, dict(summary, expiresAt="2026-10-01T00:00:00"), False)
validate(protocol, {"$ref": "#/toolResultPayload"}, {"toolName": "prepare_refund", "ok": True, "data": {"confirmationGrantId": "fixture-grant"}, "error": None}, False)
validate(protocol, {"$ref": "#/agentCard"}, card)
validate(protocol, {"$ref": "#/agentCard"}, dict(card, data={}), False)
validate(protocol, {"$ref": "#/cardAction"}, {"action": "select_voucher"}, False)

# 请求与结果额外覆盖旧别名、错误类型、确认布尔及等待结果；错误不能冒充成功。
request = dict(schemaVersion="1.1", userId="fixture-user", sessionId="fixture-session", traceId="fixture-trace", runId="fixture-run", query="测试输入", context={"mode": "confirm", "confirmationAccepted": True})
validate(protocol, {"$ref": "#/agentRunRequest"}, request)
validate(protocol, {"$ref": "#/agentRunRequest"}, dict(request, text="旧输入"), False)
validate(protocol, {"$ref": "#/agentRunRequest"}, dict(request, context={"confirmationAccepted": "true"}), False)
validate(protocol, {"$ref": "#/agentRunRequest"}, dict(request, schemaVersion="1.0"), False)
for name in ("input_required", "confirmation_required", "run_completed", "error"):
    status, payload = events[name]
    event = dict(base, eventType=name, status=status, payload=payload)
    result = dict(schemaVersion="1.1", traceId="fixture-trace", runId="fixture-run", status=status, isComposite=False, events=[event])
    if name == "error":
        result["error"] = payload
    validate(protocol, {"$ref": "#/agentRunResult"}, result)
    validate(protocol, {"$ref": "#/agentRunResult"}, dict(result, status="completed"), False)
validate(protocol, {"$ref": "#/confirmationSummary"}, dict(summary, amountCents=-1), False)
validate(protocol, {"$ref": "#/confirmationSummary"}, dict(summary, amountCents=1.5), False)
validate(protocol, {"$ref": "#/confirmationSummary"}, dict(summary, amountCents="1000"), False)
validate(protocol, {"$ref": "#/inputRequiredPayload"}, {"message": "测试问题", "nextAction": "测试操作"}, False)
validate(protocol, {"$ref": "#/progressPayload"}, {"stepId": "fixture-step", "summary": "测试进度", "status": "running"}, False)
validate(protocol, {"$ref": "#/confirmationRequiredPayload"}, {"summary": summary}, False)
grant = dict(confirmationGrantId="fixture-grant", userId="fixture-user", sessionId="fixture-session", action="request_refund", resourceId="fixture-voucher", amountCents=1000, ruleVersion="refund-v1", expiresAt=stamp, consumed=False)
validate(protocol, {"$ref": "#/confirmationGrant"}, grant)
validate(protocol, {"$ref": "#/confirmationGrant"}, dict(grant, grantId="旧字段"), False)
common = dict(schemaVersion="1.1", userId="fixture-user", sessionId="fixture-session", traceId="fixture-trace", runId="fixture-run", taskId="fixture-task", idempotencyKey="fixture-run:fixture-task:1", payload={})
validate(tools, {"$ref": "#/commonRequest"}, common)
validate(tools, {"$ref": "#/commonRequest"}, dict(common, schemaVersion="1.0"), False)
validate(tools, {"$ref": "#/commonResult"}, {"schemaVersion": "1.1", "ok": True, "data": {}, "error": None})
validate(tools, {"$ref": "#/commonResult"}, {"schemaVersion": "1.1", "ok": False, "data": {}, "error": events["error"][1]})
validate(tools, {"$ref": "#/commonResult"}, {"schemaVersion": "1.1", "ok": True, "data": {}, "error": events["error"][1]}, False)
validate(tools, {"$ref": "#/commonResult"}, {"schemaVersion": "1.1", "ok": False, "data": {}, "error": None}, False)
print(f"标准 Draft7 合成载荷校验通过：{checks} 个正反断言；17 工具、10 事件、请求结果、暂停与取消、卡片、确认正文、时间和隐私字段。")
'@ | & 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -
```
