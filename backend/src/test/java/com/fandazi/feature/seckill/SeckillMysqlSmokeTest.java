// 秒杀真实环境冒烟：在专用MySQL上运行完整Flyway/实体校验，再经真实HTTP登录和读取目录。
package com.fandazi.feature.seckill;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** 默认不连接外部数据库；仅在显式专用环境开关和私有连接变量齐备时执行，不用H2替代。 */
@EnabledIfEnvironmentVariable(named="SECKILL_MYSQL_TEST",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"seed.enabled=false","group.seed.enabled=false","spring.jpa.hibernate.ddl-auto=validate",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","spring.jpa.properties.hibernate.jdbc.time_zone=UTC"})
class SeckillMysqlSmokeTest {
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SeckillTimeSource time;

    /** 只准使用本任务专用监听端口和库名，凭据在进程环境提供且不输出到报告。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String url=System.getenv("DB_URL");
        if(url==null || !url.startsWith("jdbc:mysql://127.0.0.1:13381/dianping_intent?"))
            throw new IllegalStateException("真实验收只能连接已登记的秒杀隔离MySQL");
        properties.add("spring.datasource.url",()->url);
        properties.add("spring.datasource.username",()->required("DB_USERNAME"));
        properties.add("spring.datasource.password",()->required("DB_PASSWORD"));
        // 每次服务只使用本进程随机签名材料；不写源码常量、不复制其他任务密钥。
        String signing=UUID.randomUUID().toString()+UUID.randomUUID();
        properties.add("security.jwt.secret",()->signing);
    }

    /** 缺少私有凭据时中止前置，错误只包含环境变量名称。 */
    private static String required(String name) {
        String value=System.getenv(name);
        if(value==null || value.isBlank()) throw new IllegalStateException("缺少私有环境变量："+name);
        return value;
    }

    @Test
    void realMysqlMigrationLoginCatalogAndRollback() {
        // 前置事实：必须实际连接MySQL8，并核对数据库权威时间与本次时刻处于合理范围。
        assertThat(jdbc.queryForObject("SELECT VERSION()",String.class)).startsWith("8.");
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("dianping_intent");
        Instant now=time.now();
        assertThat(Duration.between(now,Instant.now()).abs()).isLessThan(Duration.ofSeconds(2));
        var tx=new TransactionTemplate(transactions);
        var activity=tx.execute(status->SeckillTestFixture.create(em,now.plusSeconds(7200),now.plusSeconds(14400),100,1));

        // 真实HTTP注册、登录和本人读取；不把令牌或密码写入断言消息、日志及证据。
        String identity="sk-smoke-"+UUID.randomUUID();
        String password=UUID.randomUUID()+"!Aa1";
        var registered=http.postForEntity("/api/auth/register",Map.of("username",identity,"email",identity+"@example.invalid","password",password),JsonNode.class);
        assertThat(registered.getStatusCode().value()).isEqualTo(201);
        var loggedIn=http.postForEntity("/api/auth/login",Map.of("username",identity,"password",password),JsonNode.class);
        assertThat(loggedIn.getStatusCode().value()).isEqualTo(200);
        String token=loggedIn.getBody().path("token").asText();
        assertThat(token.isBlank()).as("登录须返回访问令牌，令牌值不输出").isFalse();
        var headers=new HttpHeaders();headers.setBearerAuth(token);
        var me=http.exchange("/api/users/me",HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(me.getBody().path("username").asText()).isEqualTo(identity);

        // 预告读取使用实际端口，明确检查无缓存、价格、状态、活动身份；业务数据来自真实持久化。
        var listing=http.getForEntity("/api/stores/"+activity.getDeal().getStore().getPublicId()+"/seckill-activities",JsonNode.class);
        assertThat(listing.getStatusCode().value()).isEqualTo(200);
        assertThat(listing.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(listing.getBody().get(0).path("activityId").asText()).isEqualTo(activity.getPublicId());
        assertThat(listing.getBody().get(0).path("deal").path("priceCents").asInt()).isEqualTo(3990);
        assertThat(listing.getBody().get(0).path("state").asText()).isEqualTo("UPCOMING");
        var detail=http.getForEntity("/api/seckill-activities/"+activity.getPublicId(),JsonNode.class);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        assertThat(detail.getBody().path("availableStock").asInt()).isEqualTo(100);

        // 数据库回读确认关联/时间/库存；非法数据在插入门店和套餐后失败也必须整体回滚。
        assertThat(jdbc.queryForObject("SELECT inventory FROM deal_packages WHERE id=?",Integer.class,activity.getDeal().getId())).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seckill_activities WHERE public_id=? AND deal_id=?",Integer.class,activity.getPublicId(),activity.getDeal().getId())).isEqualTo(1);
        long before=jdbc.queryForObject("SELECT count(*) FROM deal_packages",Long.class);
        assertThatThrownBy(()->tx.execute(status->SeckillTestFixture.create(em,now.plusSeconds(7200),now.plusSeconds(14400),0,1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deal_packages",Long.class)).isEqualTo(before);
    }
}
