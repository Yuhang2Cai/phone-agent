package com.fandazi.feature.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * AG07版本化安全事件工厂；由阶段适配器提供内部事实，由本机有界接收器落盘。
 * 不保留用户正文、原工具参数、凭证或底层异常；字段缺失显式为null，不推断成功。
 */
public final class AgentOperationsEvent {
    /** 固定字段与取值目录，禁止把任意输入或URL当作指标标签。 */
    private static final List<String> FIELDS = List.of("service", "instanceId", "releaseId",
        "protocolVersion", "stage", "status", "errorCode", "durationMs", "traceKey",
        "parentSpanId", "spanId", "requestKey", "runKey", "taskKey", "executionKeyHash",
        "toolName", "modelId", "usage");
    private static final Set<String> TYPES = Set.of("stage","model","tool","request","bridge","admission","resource","heartbeat");
    private static final Set<String> KEYS = Set.of("traceKey","requestKey","runKey","taskKey","executionKeyHash");
    private static final Set<String> UUID_FIELDS = Set.of("instanceId","spanId","parentSpanId");
    private static final Set<String> USAGE = Set.of("prompt_tokens","completion_tokens","total_tokens","cached_tokens");
    private static final Map<String, Set<String>> ENUMS = Map.of(
        "service", Set.of("agent","spring"),
        "protocolVersion", Set.of("2.0","2.1","2.2","2.3"),
        "stage", Set.of("input.total","run.total","route.local","model.decision","model.fallback","model.task",
            "model.extract","model.plan","model.reply","tool.http","authority.http","snapshot.copy",
            "snapshot.project","snapshot.serialize","snapshot.db","context.append","read.queue","stream.enqueue",
            "spring.task_source","spring.task_interpret","spring.source_history","spring.agent_run",
            "spring.history","spring.presentation","spring.complete"),
        "status", Set.of("ok","error","cancelled","attempted","committed","denied","unknown","absent","accepted","rejected","pending","closed"),
        "errorCode", Set.of("DEPENDENCY_UNAVAILABLE","VALIDATION_ERROR","NOT_FOUND","FORBIDDEN","UNAUTHORIZED","CONFLICT",
            "EXPIRED","RATE_LIMITED","INTERNAL_ERROR","TIMEOUT","CANCELLED","UNKNOWN_WRITE","STATE_CONFLICT","SINK_ERROR"),
        "toolName", Set.of("search_stores","get_store_detail","list_deal_packages","get_deal_detail","search_reviews",
            "list_user_orders","get_order_detail","list_user_vouchers","create_purchase_quote","prepare_refund",
            "prepare_complaint","create_group_buy_order","pay_mock_order","request_refund","query_coupon","query_faq","submit_complaint"),
        "modelId", Set.of("decision","fallback","task","extract","plan","reply","embedding"));

    /** 工具类不创建线程或连接；使用静态工厂获得独立事件。 */
    private AgentOperationsEvent() {}

    /** 对内部随机身份做UTF8 SHA256前24位摘要；调用者不得传正文、手机号等低熵敏感值。 */
    public static String identityKey(String identity) {
        if (identity == null) return null;
        if (identity.isEmpty() || identity.length() > 256) throw new IllegalArgumentException("invalid_internal_identity");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0,24);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable");
        }
    }

    /** 检查32位或标准带横线UUID，与Python事件格式兼容；不接受Java宽松补零解析。 */
    private static boolean validUuid(Object value) {
        return value instanceof String text && (text.matches("[0-9a-fA-F]{32}")
            || text.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"));
    }

    /**
     * 从白名单字段重建事件，原字典/usage不会被直接保存；非法值抛IllegalArgumentException。
     * eventId仅用于重复收集去重，不能证明业务唯一执行；提交结果仍以权威回执为准。
     */
    public static Map<String,Object> make(String eventType, Map<String,?> fields) {
        if (eventType == null || !TYPES.contains(eventType) || fields == null)
            throw new IllegalArgumentException("invalid_event_type");
        Object id = fields.get("eventId");
        if (id == null) id = UUID.randomUUID().toString();
        if (!validUuid(id)) throw new IllegalArgumentException("invalid_event_id");
        Object utc = fields.get("utcTime");
        if (utc == null) utc = OffsetDateTime.now(ZoneOffset.UTC).toString();
        try {
            if (!(utc instanceof String) || !OffsetDateTime.parse((String)utc).getOffset().equals(ZoneOffset.UTC))
                throw new IllegalArgumentException("invalid_utc");
        } catch (java.time.DateTimeException invalid) {
            throw new IllegalArgumentException("invalid_utc");
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("schemaVersion","ag07.ops.v1"); result.put("eventId",id);
        result.put("eventType",eventType); result.put("utcTime",utc);
        List<String> missing = new ArrayList<>();
        // 每类字段独立校验，不让嵌套对象或动态文本经白名单名称穿透。
        for (String key : FIELDS) {
            Object value = fields.get(key);
            // 同Python按事件类型收紧字段，心跳等记录不能夹带模型用量或工具声明。
            if ((key.equals("usage") || key.equals("modelId")) && !Set.of("stage","model").contains(eventType)) value=null;
            if (key.equals("toolName") && !Set.of("stage","tool").contains(eventType)) value=null;
            if (key.equals("stage") && !Set.of("stage","model","tool","request").contains(eventType)) value=null;
            if (value != null) {
                if (ENUMS.containsKey(key) && (!(value instanceof String) || !ENUMS.get(key).contains(value)))
                    throw new IllegalArgumentException("invalid_enum:" + key);
                if (KEYS.contains(key) && (!(value instanceof String text) || !text.matches("[0-9a-f]{24}")))
                    throw new IllegalArgumentException("invalid_identity_key");
                if (UUID_FIELDS.contains(key) && !validUuid(value))
                    throw new IllegalArgumentException("invalid_span_identity");
                if (key.equals("releaseId") && (!(value instanceof String text) || !text.matches("[0-9a-f]{7,64}|unknown")))
                    throw new IllegalArgumentException("invalid_release");
                if (key.equals("durationMs") && (!(value instanceof Number number)
                        || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0))
                    throw new IllegalArgumentException("invalid_duration");
                if (key.equals("usage")) value = cleanUsage(value);
            }
            result.put(key,value);
            if (value == null) missing.add(key);
        }
        result.put("coverage",Map.of("missing",List.copyOf(missing)));
        return result;
    }

    /** usage缺失保持未知，合法整数复制到新字典；布尔/浮点/负数拒绝，未知键过滤。 */
    private static Object cleanUsage(Object value) {
        if (!(value instanceof Map<?,?> raw)) throw new IllegalArgumentException("invalid_usage");
        Map<String,Object> clean = new LinkedHashMap<>();
        for (String key : USAGE) {
            if (!raw.containsKey(key)) continue;
            Object count = raw.get(key);
            if (!(count instanceof Byte || count instanceof Short || count instanceof Integer || count instanceof Long)
                    || ((Number)count).longValue() < 0)
                throw new IllegalArgumentException("invalid_usage_count");
            clean.put(key,count);
        }
        return clean.isEmpty() ? null : clean;
    }
}
