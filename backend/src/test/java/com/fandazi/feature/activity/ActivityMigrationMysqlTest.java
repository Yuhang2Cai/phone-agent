// 活动迁移升级验收：从旧版结构和既有合成订单升级，验证历史支付时间及用户数据不被改写。
package com.fandazi.feature.activity;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** 只允许显式启用的本任务升级专用空库，不清空或回滚任何现有业务库。 */
@EnabledIfEnvironmentVariable(named="ACTIVITY_UPGRADE_ENABLED",matches="true")
class ActivityMigrationMysqlTest {
    /** 旧结构带数据升级后保持订单事实；活动结构首次建立但没有示例任务或虚构余额。 */
    @Test void upgradePreservesExistingOrderAndDoesNotInventPaymentTime() {
        String url=System.getenv("ACTIVITY_DB_URL");
        assertTrue(url.contains("/fandazi_activity_upgrade_20261029?"));
        var source=new DriverManagerDataSource(url,System.getenv("ACTIVITY_DB_USER"),System.getenv("ACTIVITY_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        assertEquals(0,jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database()",Integer.class),"只允许首次执行空的专用升级库");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("2026101801").load().migrate();
        // 旧数据使用独立合成账号和目录，不复制真实用户；PAID却缺paid_at模拟允许保留的历史事实。
        jdbc.update("insert into app_users(username,email,password_hash) values('activity-upgrade-fixture','upgrade@example.invalid','synthetic-unusable-hash')");
        long user=jdbc.queryForObject("select id from app_users where username='activity-upgrade-fixture'",Long.class);
        String store=UUID.randomUUID().toString();
        jdbc.update("insert into stores(public_id,brand_name,name,city_code,address,phone,category,latitude,longitude,average_spend_amount,open,opening_hours) values(?,'合成品牌','升级门店','TEST','合成地址','000','测试',0,0,10,true,'全天')",store);
        long storeId=jdbc.queryForObject("select id from stores where public_id=?",Long.class,store);
        String deal=UUID.randomUUID().toString();
        jdbc.update("insert into deal_packages(public_id,store_id,title,description,price_amount,original_price_amount,inventory,valid_from,valid_to,active,purchase_limit,version) values(?,?,'升级套餐','合成升级数据',10,12,1,'2026-10-01','2026-12-01',true,1,1)",deal,storeId);
        long dealId=jdbc.queryForObject("select id from deal_packages where public_id=?",Long.class,deal);
        String order=UUID.randomUUID().toString();
        jdbc.update("insert into group_buy_orders(public_id,user_id,store_id,deal_id,idempotency_key,quantity,total_amount,paid_total_amount,paid_at,status,created_at,expires_at,request_fingerprint) values(?,?,?,?,?,1,10,10,null,'PAID','2026-10-01 12:00:00','2026-10-02 12:00:00',?)",order,user,storeId,dealId,UUID.randomUUID().toString(),UUID.randomUUID().toString());
        var before=jdbc.queryForMap("select * from group_buy_orders where public_id=?",order);
        var result=Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        assertEquals(1,result.migrationsExecuted);
        assertEquals(before,jdbc.queryForMap("select * from group_buy_orders where public_id=?",order));
        assertNull(jdbc.queryForMap("select paid_at from group_buy_orders where public_id=?",order).get("paid_at"));
        for(String table:new String[]{"activity_definitions","activity_instances","activity_reward_grants","coin_accounts","coin_transactions"})
            assertEquals(0,jdbc.queryForObject("select count(*) from "+table,Integer.class),"迁移不能发布样例业务数据："+table);
        assertEquals(1,jdbc.queryForObject("select count(*) from information_schema.statistics where table_schema=database() and table_name='group_buy_orders' and index_name='idx_activity_order_paid' and seq_in_index=2 and column_name='paid_at'",Integer.class));
    }
}
