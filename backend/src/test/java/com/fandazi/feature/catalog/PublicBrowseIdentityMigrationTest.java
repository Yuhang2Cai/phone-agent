// 稳定公开身份迁移回归：先建立V17已有历史数据，再真实执行V18，检查同名资源、金额及再次迁移不变。
package com.fandazi.feature.catalog;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 不依赖实体新构造器覆盖历史缺ID情形；禁止改旧迁移或清库达到升级效果。 */
class PublicBrowseIdentityMigrationTest {
    /** UUID由新前向迁移为历史行补齐，内容相同的行依然独立，迁移再次运行不能换ID。 */
    @Test void existingRowsGainDistinctStableIdentityWithoutChangingFacts() throws Exception {
        String url="jdbc:h2:mem:public-identity-migration;MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("17").load().migrate();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()) {
            // 仅合成历史数据，两个同名同价菜品和两条同正文评价由独立行承载。
            statement.executeUpdate("INSERT INTO merchants(id,name,category,minimum_order,delivery_fee,delivery_minutes,open,discount_threshold,discount_amount,public_id) VALUES(1,'合成商家','合成类别',0,0,0,true,0,0,'00000000-0000-0000-0000-000000000001')");
            statement.executeUpdate("INSERT INTO dishes(id,merchant_id,category,name,description,price,stock,listed,image_url) VALUES(1,1,'合成','同名菜','同说明',12.34,9,true,'/fixture/photo'),(2,1,'合成','同名菜','同说明',12.34,9,true,'/fixture/photo')");
            statement.executeUpdate("INSERT INTO stores(id,public_id,brand_name,name,city_code,address,phone,category,latitude,longitude,average_spend_cents,average_spend_amount,open,opening_hours,merchant_id) VALUES(1,'00000000-0000-0000-0000-000000000002','合成品牌','合成门店','fixture','合成地址','合成电话','合成',0,0,100,1.00,true,'合成时间',1)");
            statement.executeUpdate("INSERT INTO store_reviews(id,store_id,reviewer_name,rating,content,created_at) VALUES(1,1,'同作者',4,'同正文','2026-10-01 00:00:00'),(2,1,'同作者',4,'同正文','2026-10-02 00:00:00')");
        }
        var migration=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load();migration.migrate();
        java.util.Map<String,String> identities=new java.util.HashMap<>();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()) {
            for(String table:java.util.List.of("dishes","store_reviews")) {
                var seen=new java.util.HashSet<String>();
                try(var result=statement.executeQuery("SELECT id,public_id FROM "+table+" ORDER BY id")) {
                    while(result.next()){String id=result.getString("public_id");assertEquals(id,UUID.fromString(id).toString());assertTrue(seen.add(id));identities.put(table+result.getLong("id"),id);}
                }
                assertEquals(2,seen.size());
            }
            try(var result=statement.executeQuery("SELECT price,stock,name FROM dishes WHERE id=1")){assertTrue(result.next());assertEquals(new java.math.BigDecimal("12.34"),result.getBigDecimal("price"));assertEquals(9,result.getInt("stock"));assertEquals("同名菜",result.getString("name"));}
        }
        migration.migrate();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()) {
            for(String table:java.util.List.of("dishes","store_reviews"))try(var result=statement.executeQuery("SELECT id,public_id FROM "+table)){while(result.next())assertEquals(identities.get(table+result.getLong("id")),result.getString("public_id"));}
        }
    }
}
