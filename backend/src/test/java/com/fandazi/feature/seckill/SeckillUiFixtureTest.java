// 秒杀UI专用合成数据：只存在于测试源码和显式隔离MySQL环境，生产JAR不包含发布/管理入口。
package com.fandazi.feature.seckill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fandazi.feature.catalog.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** 本测试只建立可回读的真实活动；通过不代表Android、300秒或性能验收完成。 */
@EnabledIfEnvironmentVariable(named="SECKILL_UI_FIXTURE_OUT",matches=".+")
@SpringBootTest(properties={"seed.enabled=false","group.seed.enabled=false","spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","spring.jpa.properties.hibernate.jdbc.time_zone=UTC","seckill.expiry.enabled=false"})
class SeckillUiFixtureTest {
    @Autowired EntityManager em;@Autowired PlatformTransactionManager transactions;@Autowired SeckillTimeSource time;
    /** 只允许已登记独占端口与库，随机运行签名材料不进入源码或证据。 */
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){
        String url=System.getenv("DB_URL");if(url==null||!url.startsWith("jdbc:mysql://127.0.0.1:13381/dianping_intent?"))throw new IllegalStateException("UI夹具必须连接本任务隔离MySQL");
        p.add("spring.datasource.url",()->url);p.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));p.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
        String signing=UUID.randomUUID().toString()+UUID.randomUUID();p.add("security.jwt.secret",()->signing);
    }
    /** 每次创建独立资源身份；过往失败夹具保留，不清空共享库或重写历史测试结果。 */
    @Test void createRealUiFixtures() throws Exception {
        Path output=Path.of(System.getenv("SECKILL_UI_FIXTURE_OUT")).toAbsolutePath().normalize();
        assertThat(output.toString().replace('\\','/')).contains("/.runtime/");assertThat(Files.exists(output)).isFalse();
        var tx=new TransactionTemplate(transactions);Instant now=time.now();var data=new LinkedHashMap<String,Object>();
        data.put("createdAt",now.toString());
        data.put("live",tx.execute(status->create("秒杀体验小馆",now.minusSeconds(60),now.plusSeconds(14400),100,2,true)));
        data.put("upcoming",tx.execute(status->create("秒杀预告小馆",now.plusSeconds(3600),now.plusSeconds(14400),100,1,true)));
        data.put("missingImage",tx.execute(status->create("秒杀无图小馆",now.minusSeconds(60),now.plusSeconds(14400),10,1,false)));
        data.put("expiry",tx.execute(status->create("秒杀超时小馆",now.minusSeconds(60),now.plusSeconds(14400),1,1,true)));
        Files.createDirectories(output.getParent());new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),data);
        assertThat(Files.size(output)).isGreaterThan(0);
    }
    /** 夹具图片引用仓库现有审核图片，客户端仍通过后端字段加载，不内置设计示例图。 */
    private Map<String,Object> create(String name,Instant start,Instant end,int stock,int limit,boolean image){
        // 公开目录消费者严格校验标准UUID；用途标签只进入名称，不能拼进资源身份。
        String storeId=UUID.randomUUID().toString(),dealId=UUID.randomUUID().toString(),activityId=UUID.randomUUID().toString();
        var store=new StoreEntity(storeId,"秒杀体验",name,"310000","合成验收地址","000","中餐",new BigDecimal("31.2"),new BigDecimal("121.4"),3990,true,"11:00—21:00",null);em.persist(store);
        var date=time.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        var deal=new DealPackageEntity(dealId,store,"双人点心套餐券","小笼点心 · 时令配菜 · 双人饮品",3990,7900,stock,date.plusDays(1),date.plusDays(30),true,limit,1);
        deal.replaceCompareFacts(2,null,"到店堂食；每份供2人使用；使用前请留意有效日期。");
        if(image)deal.replaceImage("/catalog-images/v1/full/dim-sum-7b62b7c9c192.jpg","双人点心套餐实物图");
        em.persist(deal);var activity=new SeckillActivityEntity(activityId,deal,start,end);em.persist(activity);em.flush();
        // 在输出设备输入前核对数据库回读身份，避免再次用后端可存但客户端拒绝的夹具冒充业务失败。
        assertThat(UUID.fromString(store.getPublicId()).toString()).isEqualTo(storeId);
        assertThat(UUID.fromString(deal.getPublicId()).toString()).isEqualTo(dealId);
        return Map.of("storeId",store.getPublicId(),"storeName",name,"dealId",deal.getPublicId(),"activityId",activity.getPublicId(),"startsAt",start.toString(),"endsAt",end.toString());
    }
}
