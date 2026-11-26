// Task2 升级回归：直接从 V11 带业务数据升级，避免 Hibernate 建表掩盖实际迁移问题。
package com.fandazi.feature.grouporder;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** 使用真实 JDBC 与 Flyway 检查金额、行数量、归属、引用和随机别名；不连接开发 MySQL。 */
class GroupBuyingMigrationTest {
    /** 旧订单 10001 分三券的支付快照、申请和确认资源都必须完整保留。 */
    @Test
    void upgradePreservesLegacyRowsOwnershipRemaindersAndReferences() throws Exception {
        String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("11").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            // 最小历史数据包含真实外键和一条待处理申请，公开编号特意保持旧可枚举格式。
            execute(connection, "INSERT INTO app_users(id,username,email,password_hash) VALUES(1,'migration-owner','migration@example.invalid','不可登录测试哈希')");
            execute(connection, "INSERT INTO stores(id,public_id,brand_name,name,city_code,address,phone,category,latitude,longitude,average_spend_cents,open,opening_hours) VALUES(1,'store-legacy','测试品牌','测试门店','310000','测试地址','000','测试',31,121,12345,true,'全天')");
            execute(connection, "INSERT INTO deal_packages(id,public_id,store_id,title,description,price_cents,original_price_cents,inventory,valid_from,valid_to,active,purchase_limit,version) VALUES(1,'deal-legacy',1,'历史套餐','历史说明',5000,6000,10,'2026-01-01','2027-12-31',true,4,1)");
            execute(connection, "INSERT INTO group_buy_orders(id,public_id,user_id,store_id,deal_id,idempotency_key,quantity,total_cents,status,created_at,expires_at,request_fingerprint) VALUES(1,'order-legacy',1,1,1,'legacy-key',3,10001,'PAID','2026-09-29 10:00:00','2027-12-31 23:59:59','deal-legacy:1:3')");
            for (int index = 1; index <= 3; index++) execute(connection,
                    "INSERT INTO vouchers(id,public_id,order_id,user_id,deal_id,status,qr_token,created_at,expires_at) VALUES(" + index + ",'voucher-legacy-" + index + "',1,1,1,'UNUSED','qr-legacy-" + index + "','2026-09-29 10:00:00','2027-12-31 23:59:59')");
            execute(connection, "INSERT INTO refund_requests(id,public_id,voucher_id,user_id,reason,status,created_at) VALUES(1,'refund-legacy',1,1,'行程变化','REQUESTED','2026-09-29 11:00:00')");
            execute(connection, "INSERT INTO confirmation_grants(grant_id,user_id,session_id,action,resource_id,amount_cents,rule_version,expires_at,consumed) VALUES('grant-legacy',1,'session','REQUEST_REFUND','voucher-legacy-1',3333,'refund-v1','2027-12-31 23:59:59',false)");
            execute(connection, "INSERT INTO purchase_quotes(id,public_id,user_id,deal_id,quantity,subtotal_cents,discount_cents,total_cents,expires_at,created_at,status,request_fingerprint) VALUES(1,'quote-legacy',1,1,3,10001,0,10001,'2027-12-31 23:59:59','2026-09-29 10:00:00','ACTIVE','deal-legacy:1:3:10001')");
            execute(connection, "ALTER TABLE agent_sessions ADD COLUMN pending_resource_id VARCHAR(80)");
            execute(connection, "INSERT INTO agent_sessions(id,user_id,session_id,mode,trace_id,confirmed,confirmation_consumed,created_order_settled,active_run_id,active_run_status,pending_resource_id,pending_confirmation) VALUES(1,1,'session','CONFIRM','trace',true,false,false,'run-legacy','waiting_confirmation','voucher-legacy-1','{\"action\":\"request_refund\",\"resourceId\":\"voucher-legacy-1\",\"reason\":\"保留用户正文\"}')");
            execute(connection, "INSERT INTO agent_tool_executions(execution_key,user_id,session_id,tool_name,fingerprint,created_at,result_json) VALUES('historical-key',1,'session','request_refund','original-fingerprint','2026-09-29 10:00:00','{\"requestId\":\"refund-legacy\",\"voucherId\":\"voucher-legacy-1\",\"orderId\":\"order-legacy\",\"reason\":\"voucher-legacy-1\"}')");
            execute(connection, "INSERT INTO agent_messages(public_id,user_id,session_id,trace_id,run_id,role,content,created_at) VALUES('message-legacy',1,'session','trace','run-legacy','assistant','{\"cardType\":\"order\",\"resourceId\":\"order-legacy\",\"data\":{\"storeId\":\"store-legacy\",\"dealId\":\"deal-legacy\"},\"content\":\"order-legacy\"}','2026-09-29 10:00:00')");
        }
        var upgraded = Flyway.configure().dataSource(url, "sa", "").load();
        upgraded.migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            assertThat(decimal(connection, "SELECT total_amount FROM group_buy_orders WHERE id=1")).isEqualByComparingTo("100.01");
            // Task2 只新增权威列，旧列完整保留历史整数分，清理必须等后续独立恢复验证。
            assertThat(number(connection, "SELECT total_cents FROM group_buy_orders WHERE id=1")).isEqualTo(10001);
            assertThat(number(connection, "SELECT price_cents FROM deal_packages WHERE id=1")).isEqualTo(5000);
            assertThat(number(connection, "SELECT average_spend_cents FROM stores WHERE id=1")).isEqualTo(12345);
            assertThat(decimal(connection, "SELECT subtotal_amount FROM purchase_quotes WHERE id=1")).isEqualByComparingTo("100.01");
            assertThat(number(connection, "SELECT subtotal_cents FROM purchase_quotes WHERE id=1")).isEqualTo(10001);
            assertThat(decimal(connection, "SELECT paid_total_amount FROM group_buy_orders WHERE id=1")).isEqualByComparingTo("100.01");
            assertThat(decimal(connection, "SELECT SUM(allocated_amount) FROM vouchers WHERE order_id=1")).isEqualByComparingTo("100.01");
            assertThat(decimal(connection, "SELECT allocated_amount FROM vouchers WHERE id=1")).isEqualByComparingTo("33.34");
            assertThat(decimal(connection, "SELECT amount FROM refund_requests WHERE id=1")).isEqualByComparingTo("33.34");
            assertThat(number(connection, "SELECT COUNT(*) FROM vouchers WHERE user_id=1 AND order_id=1 AND deal_id=1")).isEqualTo(3);
            assertThat(number(connection, "SELECT COUNT(*) FROM app_users")).isEqualTo(1);
            String order = string(connection, "SELECT public_id FROM group_buy_orders WHERE id=1");
            String voucher = string(connection, "SELECT public_id FROM vouchers WHERE id=1");
            assertThat(UUID.fromString(order).version()).isEqualTo(4);
            assertThat(UUID.fromString(voucher).version()).isEqualTo(4);
            assertThat(string(connection, "SELECT public_id FROM public_id_aliases WHERE resource_kind='order' AND legacy_id='order-legacy'")).isEqualTo(order);
            // 旧确认金额在分摊升级后可能失配，必须失效并重新准备，不能静默扩大用户授权金额。
            assertThat(string(connection, "SELECT resource_id FROM confirmation_grants")).isEqualTo(voucher);
            assertThat(number(connection, "SELECT COUNT(*) FROM confirmation_grants WHERE consumed=true")).isEqualTo(1);
            String deal = string(connection, "SELECT public_id FROM deal_packages WHERE id=1");
            assertThat(string(connection, "SELECT request_fingerprint FROM group_buy_orders WHERE id=1")).isEqualTo(deal + ":1:3");
            assertThat(string(connection, "SELECT request_fingerprint FROM purchase_quotes WHERE id=1")).isEqualTo(deal + ":1:3:10001");
            assertThat(string(connection, "SELECT status FROM purchase_quotes WHERE id=1")).isEqualTo("INVALIDATED");
            assertThat(string(connection, "SELECT active_run_status FROM agent_sessions WHERE id=1")).isEqualTo("cancelled");
            assertThat(string(connection, "SELECT pending_confirmation FROM agent_sessions WHERE id=1")).isNull();
            assertThat(string(connection, "SELECT pending_resource_id FROM agent_sessions WHERE id=1")).isEqualTo(voucher);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var result = mapper.readTree(string(connection, "SELECT result_json FROM agent_tool_executions"));
            assertThat(result.path("voucherId").asText()).isEqualTo(voucher);
            assertThat(result.path("orderId").asText()).isEqualTo(order);
            assertThat(result.path("reason").asText()).isEqualTo("voucher-legacy-1");
            assertThat(string(connection, "SELECT fingerprint FROM agent_tool_executions")).isEqualTo("original-fingerprint");
            var card = mapper.readTree(string(connection, "SELECT content FROM agent_messages"));
            assertThat(card.path("resourceId").asText()).isEqualTo(order);
            assertThat(card.path("data").path("dealId").asText()).isEqualTo(deal);
            assertThat(card.path("content").asText()).isEqualTo("order-legacy");
            upgraded.migrate();
            assertThat(string(connection, "SELECT public_id FROM group_buy_orders WHERE id=1")).isEqualTo(order);
            // 显式再次运行新增迁移模拟 MySQL 中途 DDL 提交后修复重试，映射和余分不得二次变化。
            org.flywaydb.core.api.migration.Context context = new org.flywaydb.core.api.migration.Context() {
                public org.flywaydb.core.api.configuration.Configuration getConfiguration() { return upgraded.getConfiguration(); }
                public Connection getConnection() { return connection; }
            };
            new db.migration.V12__decimal_payment_snapshots().migrate(context);
            new db.migration.V13__random_public_ids_and_legacy_aliases().migrate(context);
            assertThat(string(connection, "SELECT public_id FROM group_buy_orders WHERE id=1")).isEqualTo(order);
            assertThat(decimal(connection, "SELECT SUM(allocated_amount) FROM vouchers")).isEqualByComparingTo("100.01");
            assertThat(number(connection, "SELECT total_cents FROM group_buy_orders WHERE id=1")).isEqualTo(10001);
        }
    }

    /** 执行单条固定测试 SQL；测试没有来自用户的拼接内容。 */
    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
    /** 读取单个 DECIMAL，保留数据库十进制精度验证金额转换。 */
    private BigDecimal decimal(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { rows.next(); return rows.getBigDecimal(1); }
    }
    /** 读取数量检查升级没有删除用户、券或改变归属。 */
    private long number(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { rows.next(); return rows.getLong(1); }
    }
    /** 读取公开标识或引用，检查别名在重复执行后保持稳定。 */
    private String string(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { rows.next(); return rows.getString(1); }
    }
}
