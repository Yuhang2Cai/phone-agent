// 跨仓库协议开发自测：用 SnakeYAML 解析权威文件，核对字段、状态与工具安全边界。
package com.fandazi.feature.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验手机权威契约的 1.1 声明，不创建 Spring Bean，也不依赖数据库或模型服务。
 * 断言来源于 Task1 与规格第 7/8 节；通过仅表示开发自测，不代表运行时独立验收。
 */
class AgentContractSchemaTest {
    // 需求白名单不能从被测文件反推，否则缺失工具会自动通过。
    private static final Set<String> TOOLS = Set.of("search_stores", "get_store_detail", "list_deal_packages",
            "get_deal_detail", "search_reviews", "list_user_orders", "get_order_detail", "list_user_vouchers",
            "create_purchase_quote", "prepare_refund", "prepare_complaint", "create_group_buy_order",
            "pay_mock_order", "request_refund", "query_coupon", "query_faq", "submit_complaint");
    // 业务写入与凭证准备分开核查：准备有签发副作用，但不提前提交业务。
    private static final Set<String> WRITES = Set.of("create_group_buy_order", "pay_mock_order", "request_refund", "submit_complaint");
    private static final Set<String> PREPARES = Set.of("create_purchase_quote", "prepare_refund", "prepare_complaint");

    /** 防止版本、输入及任务归属退回旧协议；补充与确认必须沿用同一任务。 */
    @Test
    void requestUsesQueryAndSpringOwnsRunIdentity() throws IOException {
        Map<String, Object> protocol = load("agent-protocol-v1.yaml");
        assertThat(protocol.get("schemaVersion")).isEqualTo("1.1");
        Map<String, Object> request = objectSchema(protocol, map(protocol.get("agentRunRequest")),
                Set.of("schemaVersion", "userId", "sessionId", "traceId", "runId", "query", "context"));
        assertThat(map(request.get("properties"))).doesNotContainKey("text");
        assertThat(map(map(request.get("properties")).get("schemaVersion")).get("const")).isEqualTo("1.1");
        Map<String, Object> identity = map(protocol.get("runIdentity"));
        assertThat(map(identity.get("runId")).get("allocatedBy")).isEqualTo("SPRING");
        assertThat(list(map(identity.get("runId")).get("reusedOn"))).containsExactlyInAnyOrder("input_resume", "confirmation_resume");
        assertThat(map(identity.get("traceId"))).containsEntry("allocatedBy", "SPRING").containsEntry("newOnEveryInput", true);
    }

    /** 等待事件只结束本轮流；成功、取消及失败分别使用明确终态，不能误报 completed。 */
    @Test
    void pausesEndTheRoundAndOnlyTerminalEventsEndTheRun() throws IOException {
        Map<String, Object> protocol = load("agent-protocol-v1.yaml");
        Map<String, Object> event = objectSchema(protocol, map(protocol.get("agentEvent")),
                Set.of("schemaVersion", "eventId", "eventType", "traceId", "runId", "sequence", "status", "payload"));
        assertThat(list(event.get("eventTypes"))).containsExactlyInAnyOrder("run_started", "intent_decided", "progress",
                "text_delta", "card", "input_required", "confirmation_required", "tool_result", "run_completed", "error");
        Map<String, Object> lifecycle = map(protocol.get("runLifecycle"));
        for (var entry : Map.of("input_required", "waiting_input", "confirmation_required", "waiting_confirmation").entrySet()) {
            // 暂停可以恢复，因此保留任务，同时结束当前输入的传输与活动进度。
            Map<String, Object> semantics = map(map(protocol.get("eventSemantics")).get(entry.getKey()));
            assertThat(semantics).containsEntry("status", entry.getValue()).containsEntry("endsRound", true)
                    .containsEntry("endsRun", false).containsEntry("roundDisposition", "paused");
            assertThat(map(lifecycle.get(entry.getValue())).get("terminal")).isEqualTo(false);
            assertThat(map(map(eventBranch(event, entry.getKey()).get("properties")).get("status")).get("const")).isEqualTo(entry.getValue());
        }
        assertThat(list(map(map(eventBranch(event, "run_completed").get("properties")).get("status")).get("enum")))
                .containsExactlyInAnyOrder("succeeded", "cancelled");
        assertThat(map(map(eventBranch(event, "error").get("properties")).get("status")).get("const")).isEqualTo("failed");
        for (String state : List.of("succeeded", "cancelled", "failed")) {
            assertThat(map(lifecycle.get(state)).get("terminal")).isEqualTo(true);
        }
        Map<String, Object> result = objectSchema(protocol, map(protocol.get("agentRunResult")), Set.of("status", "events", "isComposite"));
        assertThat(list(map(map(result.get("properties")).get("status")).get("enum")))
                .containsExactlyInAnyOrder("waiting_input", "waiting_confirmation", "succeeded", "failed", "cancelled");
    }

    /** 卡片、候选输入与确认摘要有可校验字段；凭证不成为公开展示数据。 */
    @Test
    void publicCardsDefineActionsAndKeepConfirmationGrantsPrivate() throws IOException {
        Map<String, Object> protocol = load("agent-protocol-v1.yaml");
        Map<String, Object> card = objectSchema(protocol, map(protocol.get("agentCard")), Set.of("schemaVersion", "cardType", "resourceId", "data", "actions"));
        assertThat(list(card.get("cardTypes"))).containsExactlyInAnyOrder("store", "deal", "order", "voucher", "coupon", "review", "confirmation", "error");
        Map<String, Object> action = objectSchema(protocol, map(protocol.get("cardAction")), Set.of("actionId", "actionType", "label", "resourceId", "enabled"));
        assertThat(list(map(map(action.get("properties")).get("actionType")).get("enum")))
                .contains("select_voucher", "request_refund", "submit_complaint", "cancel", "retry");
        objectSchema(protocol, map(protocol.get("inputRequiredPayload")), Set.of("question", "inputKey", "candidates"));
        objectSchema(protocol, map(protocol.get("confirmationRequiredPayload")), Set.of("summary", "actions"));
        objectSchema(protocol, map(protocol.get("confirmationSummary")), Set.of("action", "resourceId", "title", "amountCents", "ruleVersion"));
        for (String name : List.of("agentCard", "cardAction", "confirmationSummary", "confirmationRequiredPayload")) {
            assertThat(map(map(protocol.get(name)).get("properties"))).doesNotContainKey("confirmationGrantId");
        }
        Map<String, Object> grant = objectSchema(protocol, map(protocol.get("confirmationGrant")),
                Set.of("confirmationGrantId", "userId", "sessionId", "action", "resourceId", "amountCents", "ruleVersion", "expiresAt", "consumed"));
        assertThat(map(grant.get("properties"))).doesNotContainKey("grantId");
    }

    /** 逐工具核对结构和关键输入输出，而不是依靠正则提取工具名。 */
    @Test
    void everyApprovedToolDefinesTypedInputsOutputsAndSideEffects() throws IOException {
        Map<String, Object> document = load("agent-tools-v1.yaml");
        assertThat(document.get("schemaVersion")).isEqualTo("1.1");
        objectSchema(document, map(document.get("commonRequest")), Set.of("schemaVersion", "userId", "sessionId", "traceId", "runId", "taskId", "idempotencyKey", "payload"));
        objectSchema(document, map(document.get("commonResult")), Set.of("schemaVersion", "ok", "data", "error"));
        List<Map<String, Object>> tools = list(document.get("tools")).stream().map(AgentContractSchemaTest::map).toList();
        assertThat(tools).hasSize(17);
        assertThat(tools.stream().map(tool -> (String) tool.get("name")).collect(Collectors.toSet())).isEqualTo(TOOLS);
        for (Map<String, Object> tool : tools) {
            String name = (String) tool.get("name");
            Map<String, Object> input = objectSchema(document, map(tool.get("input")), expectedInput(name));
            Map<String, Object> output = objectSchema(document, map(map(tool.get("output")).get("schema")), expectedOutput(name));
            assertThat(map(input.get("properties"))).doesNotContainKey("confirmationGrant");
            assertThat(map(output.get("properties"))).doesNotContainKey("confirmationGrant");
            assertThat(tool.get("confirmationRequired")).isEqualTo(WRITES.contains(name));
            assertThat(tool.get("businessCommitted")).isEqualTo(WRITES.contains(name));
            if (PREPARES.contains(name)) {
                // 准备签发短期凭证并记审计，但不会创建订单、退款申请或投诉工单。
                assertThat(tool.get("risk")).isEqualTo("WRITE_PREVIEW");
                assertThat(list(tool.get("sideEffects"))).contains("issue_confirmation_grant");
                assertThat(map(tool.get("guards"))).containsEntry("authenticated", true).containsEntry("resourceOwnership", true)
                        .containsEntry("rateLimited", true).containsEntry("audited", true);
            } else if (WRITES.contains(name)) {
                assertThat(tool.get("risk")).isEqualTo("WRITE");
                assertThat(list(tool.get("sideEffects"))).contains("consume_confirmation_grant");
            } else {
                assertThat(tool.get("risk")).isEqualTo("READ_ONLY");
                assertThat(list(tool.get("sideEffects"))).isEmpty();
            }
        }
    }

    /** 数组元素必须覆盖资源、金额和资格，避免只有包装层名字的空输出。 */
    @Test
    void readToolItemsCoverPublicBusinessFields() throws IOException {
        Map<String, Object> document = load("agent-tools-v1.yaml");
        Map<String, Object> definitions = map(document.get("definitions"));
        objectSchema(document, map(definitions.get("store")), Set.of("publicId", "name", "address", "averageSpendCents", "open"));
        objectSchema(document, map(definitions.get("deal")), Set.of("publicId", "storeId", "title", "priceCents", "inventory", "validFrom", "validTo"));
        objectSchema(document, map(definitions.get("order")), Set.of("orderId", "dealId", "quantity", "totalCents", "status", "vouchers"));
        objectSchema(document, map(definitions.get("voucher")), Set.of("publicId", "orderId", "dealTitle", "storeName", "status", "canRefund", "expiresAt"));
        objectSchema(document, map(definitions.get("review")), Set.of("storeId", "reviewerName", "rating", "content", "createdAt"));
        objectSchema(document, map(definitions.get("coupon")), Set.of("couponId", "claimId", "status", "thresholdCents", "discountCents", "validTo"));
        objectSchema(document, map(definitions.get("faq")), Set.of("id", "question", "answer"));
    }

    /** 真实运行时校验器接受无定位目录查询，但继续拒绝越界坐标和模型身份字段。 */
    @Test
    void storeSearchAllowsMissingCityWithoutWeakeningOtherBoundaries() {
        var validator = new AgentToolSchemaValidator(new com.fasterxml.jackson.databind.ObjectMapper());
        var request = new java.util.LinkedHashMap<String, Object>(Map.of(
                "schemaVersion", "1.1", "userId", "fixture-user", "sessionId", "fixture-session",
                "traceId", "fixture-trace", "runId", "fixture-run", "taskId", "fixture-task",
                "idempotencyKey", "fixture-run:fixture-task:1", "payload", Map.of("sort", "RECOMMENDED")));
        // 省略城市不等于传入空串或编造默认城市；请求必须通过生产同一校验入口。
        validator.validateRequest("search_stores", request);
        request.put("payload", Map.of("latitude", 100));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> validator.validateRequest("search_stores", request))
                .isInstanceOf(ToolBoundaryException.class);
        request.put("payload", Map.of("userId", "another-user"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> validator.validateRequest("search_stores", request))
                .isInstanceOf(ToolBoundaryException.class);
    }

    /** 输入期望独立维护；未知工具抛异常，避免默认无约束放行。 */
    private static Set<String> expectedInput(String name) {
        return switch (name) {
            // 通用门店推荐允许缺省城市，其他资源工具仍保留必需身份。
            case "search_stores" -> Set.of();
            case "get_store_detail", "list_deal_packages", "search_reviews" -> Set.of("storeId");
            case "get_deal_detail" -> Set.of("dealId");
            case "get_order_detail" -> Set.of("orderId");
            case "create_purchase_quote" -> Set.of("dealId", "quantity");
            case "prepare_refund" -> Set.of("voucherId");
            case "create_group_buy_order" -> Set.of("quoteId", "confirmationGrantId");
            case "pay_mock_order" -> Set.of("orderId", "confirmationGrantId");
            case "request_refund" -> Set.of("voucherId", "reason", "confirmationGrantId");
            case "query_faq" -> Set.of("question");
            case "submit_complaint" -> Set.of("subject", "description", "confirmationGrantId");
            case "list_user_orders", "list_user_vouchers", "query_coupon", "prepare_complaint" -> Set.of();
            default -> throw new IllegalArgumentException("未声明工具输入: " + name);
        };
    }

    /** 输出期望覆盖实际包装层和权威字段；金额、状态与凭证是跨仓互通必需信息。 */
    private static Set<String> expectedOutput(String name) {
        return switch (name) {
            case "search_stores" -> Set.of("stores"); case "get_store_detail" -> Set.of("store");
            case "list_deal_packages" -> Set.of("deals"); case "get_deal_detail" -> Set.of("deal");
            case "search_reviews" -> Set.of("reviews"); case "list_user_orders" -> Set.of("orders");
            case "get_order_detail" -> Set.of("order"); case "list_user_vouchers" -> Set.of("vouchers");
            case "query_coupon" -> Set.of("coupons"); case "query_faq" -> Set.of("faqs");
            case "create_purchase_quote" -> Set.of("quoteId", "dealId", "quantity", "totalCents", "expiresAt", "confirmationGrantId");
            case "prepare_refund" -> Set.of("voucherId", "orderId", "dealTitle", "amountCents", "confirmationGrantId");
            case "prepare_complaint" -> Set.of("orderId", "confirmationGrantId");
            case "create_group_buy_order", "pay_mock_order" -> Set.of("orderId", "dealId", "totalCents", "status");
            case "request_refund" -> Set.of("requestId", "voucherId", "status", "reason", "createdAt");
            case "submit_complaint" -> Set.of("ticketId", "status", "subject", "createdAt");
            default -> throw new IllegalArgumentException("未声明工具输出: " + name);
        };
    }

    /** 从 Maven basedir 定位权威文件；安全构造器禁止自定义对象与重复键。 */
    private static Map<String, Object> load(String name) throws IOException {
        Path backend = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        // 权威 YAML 通过别名复用公开标识、金额和时间类型，已超过默认的 50 次集合引用。
        // 文档来自受控仓库，放宽到 500 以支持完整 schema，但仍保留上限防止异常引用膨胀。
        options.setMaxAliasesForCollections(500);
        try (InputStream stream = Files.newInputStream(backend.getParent().resolve("contracts").resolve(name))) {
            return map(new Yaml(new SafeConstructor(options)).load(stream));
        }
    }

    /** 先断言节点类型再转换，缺配置时给出断言失败而非空指针异常。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).as("契约节点必须是对象").isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    /** 数组读取先检查类型，防止 YAML 标量混入白名单或 required。 */
    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        assertThat(value).as("契约节点必须是数组").isInstanceOf(List.class);
        return (List<Object>) value;
    }

    /** 解析文档内引用并核对对象字段；输入是解析树，输出为已解引用的 schema。 */
    private static Map<String, Object> objectSchema(Map<String, Object> document, Map<String, Object> schema, Set<String> expected) {
        if (schema.containsKey("$ref")) {
            String reference = (String) schema.get("$ref");
            assertThat(reference).startsWith("#/");
            Map<String, Object> node = document;
            for (String key : reference.substring(2).split("/")) node = map(node.get(key));
            schema = node;
        }
        // 必需字段必须既有名字又有类型；关闭额外字段后旧别名不能成为第二个权威名称。
        assertThat(schema).containsEntry("type", "object").containsEntry("additionalProperties", false);
        Map<String, Object> properties = map(schema.get("properties"));
        assertThat(list(schema.get("required"))).containsAll(expected);
        assertThat(properties.keySet()).containsAll(list(schema.get("required")).stream().map(String::valueOf).toList());
        for (Object raw : properties.values()) {
            Map<String, Object> field = map(raw);
            assertThat(field.containsKey("type") || field.containsKey("$ref") || field.containsKey("oneOf")).isTrue();
            if ("array".equals(field.get("type"))) assertThat(field).containsKey("items");
        }
        return schema;
    }

    /** 查找唯一事件条件，确保暂停与终态语义写在结构中而非注释里。 */
    private static Map<String, Object> eventBranch(Map<String, Object> event, String name) {
        List<Map<String, Object>> matches = list(event.get("allOf")).stream().map(AgentContractSchemaTest::map)
                .filter(rule -> name.equals(map(map(map(rule.get("if")).get("properties")).get("eventType")).get("const"))).toList();
        assertThat(matches).hasSize(1);
        return map(matches.get(0).get("then"));
    }
}
