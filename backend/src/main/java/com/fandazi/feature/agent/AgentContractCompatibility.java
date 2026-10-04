// Agent 契约兼容模块：在工具边界集中归一旧字段，业务工作流只使用 1.1 权威命名。
package com.fandazi.feature.agent;

import com.fandazi.feature.DomainException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 无状态字段适配器；不改变业务金额、资源归属、确认资格或写入结果。 */
final class AgentContractCompatibility {
    /** 内部凭证与鉴权字段不属于公开事件；包含历史别名以保护已有数据库事件的重放。 */
    private static final java.util.Set<String> PRIVATE_FIELDS = java.util.Set.of(
            "confirmationgrantid", "confirmationgrant", "grantid", "token", "accesstoken", "refreshtoken",
            "authorization", "password", "secret", "apikey", "jwt", "redemptiontoken", "paymentconfirmationgrantid",
            "autoauthorize", "autoauthorization", "istaskauto", "taskautogrant");
    /** 禁止实例化，避免在每个工具分支建立各自的兼容规则。 */
    private AgentContractCompatibility() {}

    /** 将旧工具输入转为权威字段；同一语义的新旧字段冲突时拒绝执行。 */
    static Map<String, Object> toolPayload(String toolName, Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        rename(result, "confirmationGrant", "confirmationGrantId");
        // keyword 在评价搜索中仍是权威字段，只有 FAQ 才允许转为 question。
        if ("query_faq".equals(toolName)) rename(result, "keyword", "question");
        return result;
    }

    /** 将领域旧 API 的确认凭证名称归一；普通页面 API 不受此工具出口适配影响。 */
    static Map<String, Object> toolData(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        rename(result, "confirmationGrant", "confirmationGrantId");
        return result;
    }

    /** 统一提取公开摘要；进度标签和追问内容不能被旧 message 默认值吞掉。 */
    static String summary(Map<String, Object> payload) {
        for (String key : java.util.List.of("question", "label", "message", "delta")) {
            if (payload.get(key) instanceof String text && !text.isBlank()) return text;
        }
        if (payload.get("summary") instanceof Map<?, ?> value && value.get("title") instanceof String title) return title;
        return "本轮处理已结束";
    }

    /** 将权威暂停字段适配为现有 Android 的动作名称；取消只结束任务，不触发业务写入。 */
    static String nextAction(AgentResult.Status status, Map<String, Object> payload) {
        if (status == AgentResult.Status.NEEDS_INPUT) {
            return "voucherId".equals(payload.get("inputKey")) ? "select_voucher" : "input_required";
        }
        if (status == AgentResult.Status.AWAITING_CONFIRMATION && payload.get("summary") instanceof Map<?, ?> summary) {
            return switch (String.valueOf(summary.get("action"))) {
                case "request_refund" -> "confirm_refund";
                case "submit_complaint" -> "confirm_complaint";
                default -> "confirmation_required";
            };
        }
        return switch (status) {
            case FAILED -> "retry";
            case CANCELLED -> "cancelled";
            default -> "completed";
        };
    }

    /** 兼容旧公开响应中的售后摘要位置，仅复制已由契约允许的公开字段，不复制确认凭证。 */
    static Map<String, Object> publicSummary(Map<String, Object> payload) {
        if (!(payload.get("summary") instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> summary = new LinkedHashMap<>();
        source.forEach((key, value) -> summary.put(String.valueOf(key), value));
        // 旧端按具体资源键显示摘要；资源值仍来自 Spring 工具签发的公开标识。
        String action = String.valueOf(summary.get("action"));
        if ("request_refund".equals(action)) summary.put("voucherId", summary.get("resourceId"));
        if ("submit_complaint".equals(action)) summary.put("orderId", summary.get("resourceId"));
        return summary;
    }

    /** 公开事件只保留业务摘要；内部工具仍返回原凭证，不能因脱敏而破坏后续确认。 */
    static Map<String, Object> publicEventPayload(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) return result;
        // 逐层处理嵌套对象和候选列表；私有键先统一分隔符与大小写，历史别名不能绕过凭证边界。
        source.forEach((key, value) -> {
            if (!PRIVATE_FIELDS.contains(key.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT))) result.put(key, publicValue(value));
        });
        return result;
    }

    /** 按 JSON 数据结构递归复制；保留合法空值、数值与布尔，不通过字符串替换清洗正文。 */
    private static Object publicValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> nested.put(String.valueOf(key), item));
            return publicEventPayload(nested);
        }
        if (value instanceof java.util.List<?> list) return list.stream().map(AgentContractCompatibility::publicValue).toList();
        return value;
    }

    /** 保留字段原值，不把两个不同凭证静默选成其中一个，防止确认错绑。 */
    private static void rename(Map<String, Object> data, String oldName, String canonicalName) {
        if (!data.containsKey(oldName)) return;
        Object previous = data.remove(oldName);
        if (data.containsKey(canonicalName) && !java.util.Objects.equals(previous, data.get(canonicalName))) {
            throw new DomainException("VALIDATION_ERROR:新旧协议字段冲突 " + canonicalName);
        }
        data.putIfAbsent(canonicalName, previous);
    }
}
