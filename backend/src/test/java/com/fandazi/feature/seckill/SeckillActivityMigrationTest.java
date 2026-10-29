// 秒杀持久化迁移测试：从既有完整Flyway基线升级，确认活动表约束真实生效。
package com.fandazi.feature.seckill;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 使用JDBC执行实际迁移，不让Hibernate自动建表掩盖缺少升级脚本。 */
class SeckillActivityMigrationTest {
    @Test
    void migrationCreatesActivityAndKeepsDealExclusive() throws Exception {
        String url="jdbc:h2:mem:seckill-migration-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","")) {
            // 先用可定位业务断言确认表存在，缺表属于指定失败而不是后续SQL执行异常。
            try(var rows=c.getMetaData().getTables(null,null,"SECKILL_ACTIVITIES",null)){
                assertThat(rows.next()).as("秒杀活动迁移必须建立活动表").isTrue();
            }
            // 合成门店与套餐仅存在于本次内存库；正式MySQL验收必须另跑，不混写其结果。
            execute(c,"INSERT INTO stores(public_id,brand_name,name,city_code,address,phone,category,latitude,longitude,average_spend_amount,open,opening_hours) VALUES('sk-store','测试','测试门店','test','测试','000','中餐',0,0,10,true,'全天')");
            execute(c,"INSERT INTO deal_packages(public_id,store_id,title,description,price_amount,original_price_amount,inventory,valid_from,valid_to,active,purchase_limit,version) VALUES('sk-deal',1,'双人套餐','测试描述',39.90,79.00,100,'2026-10-29','2026-11-29',true,1,1)");
            String insert="INSERT INTO seckill_activities(public_id,deal_id,starts_at,ends_at,total_stock,per_user_limit,status,activity_version) VALUES('%s',1,'2026-10-29 04:00:00','2026-10-29 06:00:00',100,1,'PUBLISHED',1)";
            execute(c,insert.formatted("sk-activity"));
            // 一个套餐只能作为一个秒杀活动的库存源，重复绑定必须由数据库拒绝。
            assertThatThrownBy(()->execute(c,insert.formatted("sk-duplicate"))).isInstanceOf(SQLException.class);
            try(var rows=c.createStatement().executeQuery("SELECT a.total_stock,d.inventory,d.price_amount FROM seckill_activities a JOIN deal_packages d ON a.deal_id=d.id")){
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(100);
                assertThat(rows.getInt(2)).isEqualTo(100);
                assertThat(rows.getBigDecimal(3)).isEqualByComparingTo("39.90");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    /** 在隔离连接执行固定夹具SQL，异常交给测试断言，不吞掉事务错误。 */
    private static void execute(Connection c,String sql) throws SQLException {
        try(var statement=c.createStatement()){statement.execute(sql);}
    }
}
