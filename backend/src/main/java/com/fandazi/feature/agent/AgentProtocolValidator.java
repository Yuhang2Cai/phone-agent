// Agent 协议校验模块：加载随 Spring 打包的权威 YAML，并执行标准 Draft 7 结构约束。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 只校验传输结构；任务身份、事件次序和资源授权由调用方与领域服务另外检查。 */
final class AgentProtocolValidator {
    private final ObjectMapper mapper;
    // 两版分别使用完整权威 Schema；不能去掉版本约束后共用宽松结构。
    private final Map<String, JsonSchema> eventSchemas = new LinkedHashMap<>();
    private final Map<String, JsonSchema> requestSchemas = new LinkedHashMap<>();
    // 固定入口防止调用者通过任意Schema引用绕过受控任务边界。
    private final Map<String, JsonSchema> taskSchemas = new LinkedHashMap<>();
    private static final java.util.Set<String> TASK_ENTRIES = java.util.Set.of(
            "taskScope", "taskFence", "turnAdmission", "taskDirectory", "publicTask",
            "taskCommandRequest", "taskCommandView", "taskControlRequest", "controlAck", "taskDecision",
            "turnCompletionRequest", "taskPaymentPreparationRequest", "taskPaymentPreparationResult", "taskEvent");

    /** 启动时完整加载契约；配置缺失或不合法必须阻止启动，不能以部分规则继续接收事件。 */
    AgentProtocolValidator(ObjectMapper mapper) {
        this.mapper = mapper;
        loadVersion("1.1", "agent-protocol-v1.yaml");
        loadVersion("2.0", "agent-protocol-v2.yaml");
        // 上下文来源扩展独立加载，旧版本继续拒绝这些新增字段。
        loadVersion("2.1", "agent-protocol-v2.1.yaml");
        // 独立加载任务协议；只提供结构能力，启用入口另由功能门禁控制。
        loadVersion("2.2", "agent-protocol-v2.2.yaml");
    }

    /** 仅加载随构建固定的版本；任一契约缺失都阻止启动，不静默降级。 */
    private void loadVersion(String version, String file) {
        try (InputStream input = getClass().getResourceAsStream("/contracts/" + file)) {
            if (input == null) throw new IllegalStateException("缺少权威 Agent 协议配置");
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            // 受控契约复用大量字段类型，适当提高默认 50 的别名上限，但不取消防膨胀限制。
            options.setMaxAliasesForCollections(500);
            // 安全构造器只允许普通数据；所有文档内引用保留在同一根对象中交给标准解析器。
            Object loaded = new Yaml(new SafeConstructor(options)).load(input);
            if (!(loaded instanceof Map<?, ?> source)) throw new IllegalStateException("Agent 契约必须为对象");
            Map<String, Object> document = new LinkedHashMap<>();
            source.forEach((key, value) -> document.put(String.valueOf(key), value));
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            if (!version.equals(document.get("schemaVersion"))) throw new IllegalStateException("契约版本与文件不一致");
            eventSchemas.put(version, schema(factory, document, "agentEvent"));
            requestSchemas.put(version, schema(factory, document, "agentRunRequest"));
            if ("2.2".equals(version)) {
                // 所有引用在同一权威根解析，避免把嵌套字段降为宽松Map校验。
                for (String entry : TASK_ENTRIES) taskSchemas.put(entry, schema(factory, document, entry));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载 Agent 权威契约", exception);
        }
    }

    /** 保留文档内的引用目标，仅指定本次需要执行的请求或事件入口。 */
    private JsonSchema schema(JsonSchemaFactory factory, Map<String, Object> source, String entry) {
        Map<String, Object> document = new LinkedHashMap<>(source);
        document.put("$ref", "#/" + entry);
        return factory.getSchema(mapper.valueToTree(document));
    }

    /** 请求发送前检查可信网关字段，防止错误版本或不支持的上下文到达新服务。 */
    void validateRequest(Map<String, Object> request) { validate(select(requestSchemas, request), request); }

    /** 事件进入审计和展示之前检查嵌套载荷、金额和动作，不静默丢弃未知字段。 */
    void validateEvent(Map<String, Object> event) { validate(select(eventSchemas, event), event); }

    /** 验证固定任务载荷；结构合法不代表身份归属、版本、代次或执行资格合法。 */
    void validateTaskPayload(String entry, Map<String, Object> payload) {
        JsonSchema selected = taskSchemas.get(entry);
        if (selected == null) throw new AgentServiceUnavailableException("未知任务协议入口");
        validate(selected, payload);
    }

    /** 严格按载荷版本取 Schema；缺版本和未知版本都不能借旧版规则通过。 */
    private JsonSchema select(Map<String, JsonSchema> schemas, Map<String, Object> value) {
        JsonSchema selected = schemas.get(value.get("schemaVersion"));
        if (selected == null) throw new AgentServiceUnavailableException("Agent 协议版本不受支持");
        return selected;
    }

    /** 日志只保留校验路径与规则名称，不把可能包含隐私的完整载荷放入错误消息。 */
    private void validate(JsonSchema schema, Map<String, Object> value) {
        var violations = schema.validate(mapper.valueToTree(value));
        if (!violations.isEmpty()) {
            String paths = violations.stream().limit(5)
                    .map(item -> item.getInstanceLocation() + ":" + item.getType())
                    .sorted().collect(java.util.stream.Collectors.joining(", "));
            throw new AgentServiceUnavailableException("Agent 协议结构不合法：" + paths);
        }
    }
}
