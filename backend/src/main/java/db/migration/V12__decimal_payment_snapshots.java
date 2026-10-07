// V12 金额升级：精确转换历史整数分，持久化订单实付和券分摊；依赖 JDBC/Flyway，禁止删除业务行。
package db.migration;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import static db.migration.Task2MigrationSupport.*;

/** 支持 MySQL8 的隐式 DDL 提交和 H2；按列检查重试状态，新 DECIMAL 列成为实体实际读取的唯一权威。 */
public class V12__decimal_payment_snapshots extends BaseJavaMigration {
    /** 固定金额列映射：旧列整数分，新列十进制元；确认金额是唯一允许 null 的旧金额。 */
    private static final String[][] MONEY = {
            {"stores", "average_spend_cents", "average_spend_amount"},
            {"deal_packages", "price_cents", "price_amount"}, {"deal_packages", "original_price_cents", "original_price_amount"},
            {"purchase_quotes", "subtotal_cents", "subtotal_amount"}, {"purchase_quotes", "discount_cents", "discount_amount"},
            {"purchase_quotes", "total_cents", "total_amount"}, {"group_buy_orders", "total_cents", "total_amount"},
            {"coupons", "threshold_cents", "threshold_amount"}, {"coupons", "discount_cents", "discount_amount"},
            {"confirmation_grants", "amount_cents", "amount"}
    };
    /** MySQL ALTER TABLE 自动提交，关闭 Flyway 事务误导；迁移步骤本身保持可重复执行。 */
    @Override public boolean canExecuteInTransaction() { return false; }

    /** 校验历史数据后升级权威金额与快照；不合法历史数据明确阻止迁移，不清库或静默丢分。 */
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        preflight(connection);
        // 逐列转换使用 BigDecimal 位移；保留旧列供历史核对，新实体只读写新权威列，不能在 Task2 清理历史列。
        for (var field : MONEY) convertMoney(connection, field[0], field[1], field[2]);
        add(connection, "deal_packages", "image_url", "VARCHAR(1000) NULL");
        add(connection, "deal_packages", "image_alt", "VARCHAR(255) NULL");
        add(connection, "group_buy_orders", "paid_total_amount", "DECIMAL(12,2) NULL");
        add(connection, "group_buy_orders", "paid_at", "TIMESTAMP(6) NULL");
        add(connection, "vouchers", "allocated_amount", "DECIMAL(12,2) NULL");
        add(connection, "refund_requests", "amount", "DECIMAL(12,2) NULL");
        // 历史已支付状态能证明成交金额，但没有实际支付时间，因此 paid_at 保留 null 而不伪造。
        execute(connection, "UPDATE group_buy_orders SET paid_total_amount=total_amount WHERE paid_total_amount IS NULL AND status IN ('PAID','PARTIALLY_USED','USED','REFUNDED')");
        allocate(connection);
        execute(connection, "UPDATE refund_requests SET amount=(SELECT v.allocated_amount FROM vouchers v WHERE v.id=refund_requests.voucher_id) WHERE amount IS NULL");
        moneyColumn(connection, "refund_requests", "amount", "DECIMAL(12,2)", false);
        // 券行锁负责服务层并发，数据库唯一键兜住任何其他入口的重复申请，不删除历史重复记录。
        boolean exists = false;
        try (var indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null,
                connection.getMetaData().getDatabaseProductName().equals("H2") ? "REFUND_REQUESTS" : "refund_requests", true, false)) {
            while (indexes.next()) if ("uk_refund_requests_voucher".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) exists = true;
        }
        if (!exists) execute(connection, "CREATE UNIQUE INDEX uk_refund_requests_voucher ON refund_requests(voucher_id)");
    }

    /** 在任何 DDL 前检查负数、非法数量、缺券及重复申请，失败保留所有原始业务行供人工修复。 */
    private void preflight(Connection connection) throws SQLException {
        for (var field : MONEY) {
            if (!column(connection, field[0], field[1])) continue;
            for (var row : rows(connection, "SELECT id," + field[1] + " AS cents FROM " + field[0])) {
                if (row.get("cents") == null) continue;
                int cents = new BigDecimal(row.get("cents").toString()).intValueExact();
                if (cents < 0) throw new SQLException("V12 金额校验失败：" + field[0] + " id=" + row.get("id"));
            }
        }
        if (!rows(connection, "SELECT o.id FROM group_buy_orders o WHERE o.quantity<=0 OR o.quantity<>(SELECT COUNT(*) FROM vouchers v WHERE v.order_id=o.id)").isEmpty())
            throw new SQLException("V12 订单数量与券数量不一致，须修复历史数据后重试");
        if (!rows(connection, "SELECT voucher_id FROM refund_requests GROUP BY voucher_id HAVING COUNT(*)>1").isEmpty())
            throw new SQLException("V12 发现重复退款申请，保留历史记录并停止迁移");
        if (!rows(connection, "SELECT r.id FROM refund_requests r JOIN vouchers v ON v.id=r.voucher_id JOIN group_buy_orders o ON o.id=v.order_id WHERE o.status NOT IN ('PAID','PARTIALLY_USED','USED','REFUNDED')").isEmpty())
            throw new SQLException("V12 历史退款缺少可证明的支付事实，保留记录并停止迁移");
    }

    /** 旧列只精确换算一次；若 MySQL 中途提交后重试，则验证已有新金额相等才继续。 */
    private void convertMoney(Connection connection, String table, String oldColumn, String newColumn) throws SQLException {
        if (!column(connection, table, oldColumn)) return;
        add(connection, table, newColumn, "DECIMAL(12,2) NULL");
        for (var row : rows(connection, "SELECT id," + oldColumn + " AS cents," + newColumn + " AS amount FROM " + table)) {
            if (row.get("cents") == null) continue;
            BigDecimal amount = new BigDecimal(row.get("cents").toString()).movePointLeft(2).setScale(2);
            if (row.get("amount") != null && amount.compareTo(new BigDecimal(row.get("amount").toString())) != 0)
                throw new SQLException("V12 断点金额不一致：" + table + " id=" + row.get("id"));
            update(connection, "UPDATE " + table + " SET " + newColumn + "=? WHERE id=?", amount, row.get("id"));
        }
        if (!table.equals("confirmation_grants")) moneyColumn(connection, table, newColumn, "DECIMAL(12,2)", false);
        // 旧整数分列保留原值，仅放宽可空使新实体无需双写；破坏性清理由后续 Task11 单独验证。
        moneyColumn(connection, table, oldColumn, "INTEGER", true);
    }

    /** 按历史内部券主键稳定分摊订单实付，商加余数保证每张相差至多一分且不遗失余分。 */
    private void allocate(Connection connection) throws SQLException {
        for (var order : rows(connection, "SELECT id,quantity,paid_total_amount FROM group_buy_orders WHERE paid_total_amount IS NOT NULL")) {
            int paid = new BigDecimal(order.get("paid_total_amount").toString()).movePointRight(2).intValueExact();
            int quantity = ((Number) order.get("quantity")).intValue();
            var vouchers = rows(connection, "SELECT id,allocated_amount FROM vouchers WHERE order_id=" + ((Number) order.get("id")).longValue() + " ORDER BY id");
            for (int index = 0; index < vouchers.size(); index++) {
                var voucher = vouchers.get(index);
                BigDecimal amount = BigDecimal.valueOf(paid / quantity + (index < paid % quantity ? 1 : 0)).movePointLeft(2).setScale(2);
                if (voucher.get("allocated_amount") != null && amount.compareTo(new BigDecimal(voucher.get("allocated_amount").toString())) != 0)
                    throw new SQLException("V12 券分摊断点不一致：voucher id=" + voucher.get("id"));
                update(connection, "UPDATE vouchers SET allocated_amount=? WHERE id=?", amount, voucher.get("id"));
            }
        }
    }
}
