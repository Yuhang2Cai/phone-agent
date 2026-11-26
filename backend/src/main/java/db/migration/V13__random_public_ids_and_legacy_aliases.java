// V13 身份升级：随机公开 UUID 与持久化旧引用映射，外键仍保留内部 id；依赖 JDBC、Jackson 和 Flyway。
package db.migration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import static db.migration.Task2MigrationSupport.*;

/** 公开端不解析旧目录编号；迁移只按资源类型更新历史引用，旧准备授权全部失效后重新取得用户确认。 */
public class V13__random_public_ids_and_legacy_aliases extends BaseJavaMigration {
    /** 类型与表名为固定白名单，旧别名永远不能推导出随机 UUID。 */
    private static final Map<String, String> RESOURCES = Map.ofEntries(
            Map.entry("store", "stores"), Map.entry("deal", "deal_packages"), Map.entry("order", "group_buy_orders"),
            Map.entry("voucher", "vouchers"), Map.entry("refund", "refund_requests"), Map.entry("quote", "purchase_quotes"),
            Map.entry("coupon", "coupons"), Map.entry("user_coupon", "user_coupons"), Map.entry("complaint", "complaint_tickets"));
    /** 只替换这些协议字段，用户正文中相同文本保持原样，防止无差别字符串替换篡改证据。 */
    private static final Map<String, String> FIELD_KINDS = Map.ofEntries(
            Map.entry("storeId", "store"), Map.entry("storePublicId", "store"), Map.entry("dealId", "deal"), Map.entry("dealPublicId", "deal"),
            Map.entry("orderId", "order"), Map.entry("orderPublicId", "order"), Map.entry("voucherId", "voucher"), Map.entry("requestId", "refund"),
            Map.entry("quoteId", "quote"), Map.entry("couponId", "coupon"), Map.entry("claimId", "user_coupon"));

    /** MySQL DDL 隐式提交，因此映射先写后改实体；重试沿用映射，绝不再随机分配另一编号。 */
    @Override public boolean canExecuteInTransaction() { return false; }

    /** 建立稳定映射、修复资源引用、关闭旧授权；不更改用户归属、业务状态或删除任何记录。 */
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        execute(connection, "CREATE TABLE IF NOT EXISTS public_id_aliases (id BIGINT AUTO_INCREMENT PRIMARY KEY, resource_kind VARCHAR(40) NOT NULL, legacy_id VARCHAR(80) NOT NULL, public_id VARCHAR(80) NOT NULL, CONSTRAINT uk_public_alias_legacy UNIQUE(resource_kind,legacy_id), CONSTRAINT uk_public_alias_uuid UNIQUE(resource_kind,public_id))");
        Map<String, Map<String, String>> mappings = new HashMap<>();
        for (var entry : RESOURCES.entrySet()) {
            String kind = entry.getKey(), table = entry.getValue();
            Map<String, String> aliases = new HashMap<>();
            for (var row : rows(connection, "SELECT legacy_id,public_id FROM public_id_aliases WHERE resource_kind='" + kind + "'"))
                aliases.put((String) row.get("legacy_id"), (String) row.get("public_id"));
            for (var row : rows(connection, "SELECT id,public_id FROM " + table)) {
                String oldId = (String) row.get("public_id");
                if (randomUuid(oldId)) continue;
                String newId = aliases.get(oldId);
                if (newId == null) {
                    newId = UUID.randomUUID().toString();
                    update(connection, "INSERT INTO public_id_aliases(resource_kind,legacy_id,public_id) VALUES(?,?,?)", kind, oldId, newId);
                    aliases.put(oldId, newId);
                }
                update(connection, "UPDATE " + table + " SET public_id=? WHERE id=?", newId, row.get("id"));
            }
            mappings.put(kind, aliases);
        }
        // 幂等订单指纹只更新其套餐段，保留数量、版本和金额，不能把不同请求映射到同一请求。
        fingerprints(connection, "group_buy_orders", mappings.get("deal"));
        fingerprints(connection, "purchase_quotes", mappings.get("deal"));
        Map<String, String> unambiguous = unambiguous(mappings);
        for (var row : rows(connection, "SELECT id,resource_id,action FROM confirmation_grants")) {
            String resource = (String) row.get("resource_id");
            String replacement = unambiguous.get(resource);
            if (replacement != null) update(connection, "UPDATE confirmation_grants SET resource_id=? WHERE id=?", replacement, row.get("id"));
        }
        // 所有历史确认都关闭，余分修正可能改变单券金额；不能静默把原授权扩大一分钱。
        execute(connection, "UPDATE confirmation_grants SET consumed=true WHERE consumed=false");
        execute(connection, "UPDATE purchase_quotes SET status='INVALIDATED' WHERE status='ACTIVE'");
        execute(connection, "UPDATE agent_sessions SET pending_confirmation=NULL,confirmed=false,confirmation_consumed=true,active_run_status='cancelled' WHERE pending_confirmation IS NOT NULL OR active_run_status IN ('running','waiting_confirmation','waiting_input')");
        // 当前 V11 没有该列；兼容曾部署扩展列的库时只修复资源值，不生成新的授权。
        if (column(connection, "agent_sessions", "pending_resource_id")) {
            for (var row : rows(connection, "SELECT id,pending_resource_id FROM agent_sessions")) {
                String replacement = unambiguous.get(row.get("pending_resource_id"));
                if (replacement != null) update(connection, "UPDATE agent_sessions SET pending_resource_id=? WHERE id=?", replacement, row.get("id"));
            }
        }
        // 已执行动作保留原请求哈希和审计行。重放旧任务被 cancelled 拒绝，新载荷复用旧键会指纹冲突。
        rewriteJson(connection, "agent_tool_executions", "execution_key", "result_json", mappings, unambiguous, true);
        rewriteJson(connection, "agent_events", "id", "payload", mappings, unambiguous, false);
        rewriteJson(connection, "agent_messages", "id", "content", mappings, unambiguous, false);
    }

    /** 仅完整第四版 UUID 已满足目标，历史前缀或可推导 nameUUID 必须换成随机编号。 */
    private boolean randomUuid(String value) {
        try { var uuid = UUID.fromString(value); return uuid.version() == 4 && uuid.toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }

    /** 原指纹的首段恰为套餐 ID 才转换，正文或未知历史指纹保持不变，后续创建会明确冲突。 */
    private void fingerprints(Connection connection, String table, Map<String, String> aliases) throws SQLException {
        for (var row : rows(connection, "SELECT id,request_fingerprint FROM " + table)) {
            String value = (String) row.get("request_fingerprint");
            int end = value.indexOf(':');
            if (end < 0) continue;
            String replacement = aliases.get(value.substring(0, end));
            if (replacement != null) update(connection, "UPDATE " + table + " SET request_fingerprint=? WHERE id=?", replacement + value.substring(end), row.get("id"));
        }
    }

    /** 无类型的 resourceId/publicId 只转换跨类型无冲突的别名；歧义旧引用不会授权给另一类型。 */
    private Map<String, String> unambiguous(Map<String, Map<String, String>> mappings) {
        Map<String, String> result = new HashMap<>(); Set<String> conflicts = new HashSet<>();
        for (var aliases : mappings.values()) aliases.forEach((key, value) -> {
            if (result.containsKey(key) && !result.get(key).equals(value)) conflicts.add(key);
            result.put(key, value);
        });
        conflicts.forEach(result::remove);
        return result;
    }

    /** JSON 历史卡片按协议字段解析；非 JSON 的聊天正文保留，内部动作非法 JSON 则阻止迁移。 */
    private void rewriteJson(Connection connection, String table, String key, String field,
                             Map<String, Map<String, String>> mappings, Map<String, String> common, boolean required) throws Exception {
        var mapper = new ObjectMapper();
        for (var row : rows(connection, "SELECT " + key + "," + field + " FROM " + table)) {
            String text = (String) row.get(field);
            if (text == null || text.isBlank()) continue;
            JsonNode json;
            try { json = mapper.readTree(text); }
            catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                if (required) throw new SQLException("V13 内部结果 JSON 非法：" + table + " key=" + row.get(key), exception);
                continue;
            }
            if (rewrite(json, mappings, common)) update(connection, "UPDATE " + table + " SET " + field + "=? WHERE " + key + "=?", mapper.writeValueAsString(json), row.get(key));
        }
    }

    /** 递归转换明确资源键，数组顺序、金额、原因、正文和历史事件记录全部保留。 */
    private boolean rewrite(JsonNode json, Map<String, Map<String, String>> mappings, Map<String, String> common) {
        boolean changed = false;
        if (json instanceof ObjectNode object) {
            var fields = new ArrayList<String>(); object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                var value = object.get(field);
                String kind = FIELD_KINDS.get(field);
                String replacement = value.isTextual() ? (kind == null
                        ? (Set.of("resourceId", "publicId").contains(field) ? common.get(value.asText()) : null)
                        : mappings.get(kind).get(value.asText())) : null;
                if (replacement != null) { object.put(field, replacement); changed = true; }
                else if (value.isContainerNode()) changed |= rewrite(value, mappings, common);
            }
        } else if (json.isArray()) for (var value : json) changed |= rewrite(value, mappings, common);
        return changed;
    }
}
