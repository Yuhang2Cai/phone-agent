// 工具契约模块：直接加载随服务打包的权威 YAML，执行 Draft 7 请求、载荷、结果和格式校验。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;
import java.io.InputStream;
import java.util.*;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 工具白名单来自权威契约；启动失败时拒绝服务，不能退化为部分手工字段规则。 */
@Component
class AgentToolSchemaValidator {
    private final ObjectMapper mapper;
    private final JsonSchema commonRequest;
    private final JsonSchema commonResult;
    // 新传输独立约束栅栏；payload与领域分派沿用同一17工具，不复制业务实现。
    private final JsonSchema v2Request;
    private final JsonSchema v2Result;
    private final Map<String, JsonSchema> inputs = new LinkedHashMap<>();
    private final Map<String, JsonSchema> outputs = new LinkedHashMap<>();
    private final Set<String> writes = new HashSet<>();
    private final Set<String> preparations = new HashSet<>();

    /** 加载对象与所有本地引用；契约只有 17 个工具且每个工具必须具备输入输出 schema。 */
    @SuppressWarnings("unchecked")
    AgentToolSchemaValidator(ObjectMapper mapper) {
        this.mapper = mapper;
        try (InputStream stream = getClass().getResourceAsStream("/contracts/agent-tools-v1.yaml")) {
            if (stream == null) throw new IllegalStateException("工具契约缺失");
            LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(500);
            Map<String, Object> root = new Yaml(new SafeConstructor(options)).load(stream);
            commonRequest = compile(root, Map.of("$ref", "#/commonRequest"));
            commonResult = compile(root, Map.of("$ref", "#/commonResult"));
            try (InputStream newer = getClass().getResourceAsStream("/contracts/agent-tools-v2.yaml")) {
                if (newer == null) throw new IllegalStateException("新版工具契约缺失");
                Map<String, Object> v2 = new Yaml(new SafeConstructor(options)).load(newer);
                // 启动即核对原业务契约，阻止传输升级偷渡工具或放宽payload。
                if (!"2.0".equals(v2.get("schemaVersion")) || !root.get("tools").equals(v2.get("tools")))
                    throw new IllegalStateException("新版必须保持原17工具契约");
                v2Request = compile(v2, Map.of("$ref", "#/commonRequest"));
                v2Result = compile(v2, Map.of("$ref", "#/commonResult"));
            }
            for (Map<String, Object> tool : (List<Map<String, Object>>) root.get("tools")) {
                String name = (String) tool.get("name");
                if (inputs.containsKey(name)) throw new IllegalStateException("工具名称重复");
                inputs.put(name, compile(root, (Map<String, Object>) tool.get("input")));
                outputs.put(name, compile(root, (Map<String, Object>) ((Map<?, ?>) tool.get("output")).get("schema")));
                if (Boolean.TRUE.equals(tool.get("confirmationRequired"))) writes.add(name);
                if ("WRITE_PREVIEW".equals(tool.get("risk"))) preparations.add(name);
            }
            if (inputs.size() != 17) throw new IllegalStateException("工具白名单数量必须为17");
        } catch (Exception exception) { throw new IllegalStateException("无法加载权威工具契约", exception); }
    }
    /** 将选定入口与原 definitions 放在同一文档，确保嵌套引用和日期格式都由标准解析器执行。 */
    private JsonSchema compile(Map<String, Object> root, Map<String, Object> entry) {
        var document = new LinkedHashMap<>(root); document.putAll(entry);
        var config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(mapper.valueToTree(document), config);
    }
    /** 完整 commonRequest 先于 DTO 转换，避免额外字段或非法类型被 Jackson 静默舍弃。 */
    void validateRequest(String tool, Map<String, Object> request) {
        if (!inputs.containsKey(tool)) throw new ToolBoundaryException("NOT_FOUND", "工具不存在");
        check(commonRequest, request, "VALIDATION_ERROR"); check(inputs.get(tool), request.get("payload"), "VALIDATION_ERROR");
    }
    /** 新信封须包含任务栅栏和动作版本；未知工具、身份扩展及额外payload仍拒绝。 */
    void validateV2Request(String tool, Map<String, Object> request) {
        if (!inputs.containsKey(tool)) throw new ToolBoundaryException("NOT_FOUND", "工具不存在");
        check(v2Request, request, "VALIDATION_ERROR");
        check(inputs.get(tool), request.get("payload"), "VALIDATION_ERROR");
    }
    /** 只校验新版结果信封，成功业务data仍须调用原validateData。 */
    void validateV2Result(Map<String, Object> result) { check(v2Result, result, "INTERNAL_ERROR"); }
    /** 成功结果也必须满足契约；非法领域投影会导致事务回滚，不能已写业务后返回伪失败。 */
    void validateData(String tool, Map<String, Object> data) { check(outputs.get(tool), data, "INTERNAL_ERROR"); }
    /** 控制器出口验证标准结果互斥条件，失败时 data 必须为空。 */
    void validateResult(Map<String, Object> result) { check(commonResult, result, "INTERNAL_ERROR"); }
    /** 返回工具是否会实际写业务，用于本轮明确确认的强制判断。 */
    boolean isWrite(String tool) { return writes.contains(tool); }
    /** 返回工具是否签发短时凭证，用于限流与审计。 */
    boolean isPreparation(String tool) { return preparations.contains(tool); }
    /** 错误消息只公开规则路径，不包含原始业务输入；内部结果错误不泄漏字段值。 */
    private void check(JsonSchema schema, Object data, String code) {
        if (!schema.validate(mapper.valueToTree(data)).isEmpty()) throw new ToolBoundaryException(code,
                "VALIDATION_ERROR".equals(code) ? "请求不符合工具契约" : "工具结果不符合契约");
    }
}
