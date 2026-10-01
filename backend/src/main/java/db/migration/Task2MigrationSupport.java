// Task2 JDBC 迁移共用边界：仅使用固定表名，兼容 MySQL8 与 H2，并避免迁移重试重复换算金额。
package db.migration;

import java.sql.*;
import java.util.*;

/** JDBC 元数据与小型更新助手；无业务 API，不读取密码或用户正文到日志。 */
final class Task2MigrationSupport {
    private Task2MigrationSupport() {}

    /** 读取空结果集元数据判断列是否存在，规避不同数据库大小写和 schema 名称的差异。 */
    static boolean column(Connection connection, String table, String column) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            var metadata = rows.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++)
                if (column.equalsIgnoreCase(metadata.getColumnName(index))) return true;
            return false;
        }
    }

    /** 仅新增缺失列，MySQL DDL 中途失败后重跑时不能覆盖已有快照。 */
    static void add(Connection connection, String table, String column, String definition) throws SQLException {
        if (!column(connection, table, column)) execute(connection, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    /** 修改金额类型与空值约束；H2 标准模式和 MySQL8 的 ALTER 语法不同，不能要求测试强制开启兼容模式。 */
    static void moneyColumn(Connection connection, String table, String column, String type, boolean nullable) throws SQLException {
        if (connection.getMetaData().getDatabaseProductName().equals("H2")) {
            // H2 使用标准 ALTER COLUMN，显式单独修改 nullability，防止类型变更意外丢失原约束。
            execute(connection, "ALTER TABLE " + table + " ALTER COLUMN " + column + " SET DATA TYPE " + type);
            execute(connection, "ALTER TABLE " + table + " ALTER COLUMN " + column + (nullable ? " DROP NOT NULL" : " SET NOT NULL"));
        } else {
            // MySQL8 的 MODIFY 需要完整声明类型和空值约束，legacy 列只放宽为可空，不删除旧金额。
            execute(connection, "ALTER TABLE " + table + " MODIFY COLUMN " + column + " " + type + (nullable ? " NULL" : " NOT NULL"));
        }
    }

    /** 执行固定迁移 SQL，调用方不能传入客户端字段或可变表名。 */
    static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    /** 执行带参数的更新；参数不拼进 SQL，异常保留数据库定位信息但不记录密钥。 */
    static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    /** 把固定迁移查询装载到内存后再写入，避免 MySQL 同连接活动结果集被更新语句关闭。 */
    static List<Map<String, Object>> rows(Connection connection, String sql) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            var metadata = rows.getMetaData();
            while (rows.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int index = 1; index <= metadata.getColumnCount(); index++)
                    row.put(metadata.getColumnLabel(index).toLowerCase(Locale.ROOT), rows.getObject(index));
                result.add(row);
            }
        }
        return result;
    }
}
