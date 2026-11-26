// 秒杀维护备份验证：在真实MySQL与实际调度线程中停写排空，导出完整业务库并核对前后全部表摘要。
package com.fandazi.feature.seckill;

import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.catalog.*;
import com.fandazi.feature.grouporder.GroupBuyOrderService;
import com.fandazi.feature.purchase.PurchaseQuoteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** 无HTTP入口的独占维护组合；调用者必须停止本任务其他Spring进程，秘密不得进入命令行或报告。 */
@EnabledIfEnvironmentVariable(named="SECKILL_MYSQL_BACKUP_TEST",matches="true")
@SpringBootTest(properties={"seed.enabled=false","group.seed.enabled=false","seckill.expiry.enabled=true",
    "agent.task-lifecycle.enabled=false","spring.jpa.hibernate.ddl-auto=validate",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","spring.jpa.properties.hibernate.jdbc.time_zone=UTC"})
class SeckillMysqlBackupTest {
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired SeckillTimeSource time;
    @Autowired SeckillExpiryScheduler scheduler;
    @Autowired PurchaseQuoteService quotes;
    @Autowired GroupBuyOrderService orders;

    /** 仅允许既有隔离端口13381；不根据外部任意连接串选择生产目标。 */
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties){SeckillMysqlSmokeTest.database(properties);}

    /** 注册写者、停写排空、完整导出与前后水位一致缺一不可；恢复后仍按原期限释放唯一预留。 */
    @Test void pausedRegisteredWriterCannotChangeAnyTableDuringRealBackup() throws Exception {
        assertThat(scheduler.snapshot().get("writerId")).isEqualTo("seckill-expiry");
        assertThat(scheduler.pauseAndDrain(5000)).isTrue();
        var tx=new TransactionTemplate(transactions);
        var activity=tx.execute(status->createActivity());
        Long user=tx.execute(status->{String id="sk-backup-"+UUID.randomUUID();var value=new UserEntity(id,id+"@example.invalid","synthetic-hash");em.persist(value);em.flush();return value.getId();});
        String session=UUID.randomUUID().toString();var quote=quotes.createForPage(user,session,activity.getDeal().getPublicId(),1);
        var order=orders.createFromQuote(user,session,quotes.owned(user,(String)quote.get("quoteId")),(String)quote.get("confirmationGrant"),UUID.randomUUID().toString());
        // 构造维护期内已到期订单，保留严格300秒差值；不把该夹具当成真实等待300秒。
        assertThat(jdbc.update("UPDATE group_buy_orders SET payment_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND,created_at=UTC_TIMESTAMP(6)+INTERVAL 8 HOUR-INTERVAL 301 SECOND WHERE public_id=?",order.get("orderId"))).isOne();
        Path evidence=Path.of(System.getenv("SECKILL_BACKUP_EVIDENCE")).toAbsolutePath().normalize();
        Path allowed=Path.of("..").toAbsolutePath().normalize().resolve(".runtime/acceptance/seckill-coupon");
        assertThat(evidence.startsWith(allowed)).isTrue();Files.createDirectory(evidence);
        try {
            assertPaused();var before=tableDigests();
            assertThat(before.keySet()).contains("seckill_activities","seckill_quotas","seckill_reservations","seckill_purchase_requests","purchase_quotes","group_buy_orders","vouchers","flyway_schema_history");
            Path dump=evidence.resolve("private-spring.sql"),error=evidence.resolve("private-mysqldump.log");
            // 密码仅在本任务子进程环境传递；mysqldump只读13381，导出包含全部表/过程/触发器/事件。
            String root="/home/caid0/.local/share/codex-seckill-mysql-20261028";
            var command=new ProcessBuilder("wsl.exe","-d","Ubuntu","--","env","LD_LIBRARY_PATH="+root+"/root/usr/lib/x86_64-linux-gnu",
                root+"/root/usr/bin/mysqldump","--no-defaults","--protocol=TCP","--host=127.0.0.1","--port=13381",
                "--user="+System.getenv("DB_USERNAME"),"--single-transaction","--skip-lock-tables","--no-tablespaces",
                "--routines","--events","--triggers","--set-gtid-purged=OFF","dianping_intent");
            command.environment().put("MYSQL_PWD",System.getenv("DB_PASSWORD"));
            String inherited=command.environment().getOrDefault("WSLENV","");
            command.environment().put("WSLENV",inherited.isBlank()?"MYSQL_PWD":inherited+":MYSQL_PWD");
            var process=command.redirectOutput(dump.toFile()).redirectError(error.toFile()).start();
            // 截止只终止本测试创建的导出客户端；失败保留私有文件，不输出SQL及认证信息。
            boolean finished=process.waitFor(120,TimeUnit.SECONDS);
            if(!finished)process.destroyForcibly();
            assertThat(finished).as("完整备份须在120秒内完成").isTrue();
            assertThat(process.exitValue()).as("真实mysqldump退出码").isZero();
            assertThat(Files.size(dump)).isBetween(1L,256L*1024*1024);
            Thread.sleep(3100);assertPaused();var after=tableDigests();
            assertThat(after).as("跨真实扫描周期及导出前后，全部表的持久事实保持不变").isEqualTo(before);
            var mapper=new ObjectMapper();
            Files.writeString(evidence.resolve("backup-result.json"),mapper.writeValueAsString(Map.of(
                "writer",scheduler.snapshot(),"tableDigests",after,"dumpSha256",sha(Files.readAllBytes(dump)),
                "dumpBytes",Files.size(dump),"exitCode",process.exitValue(),"sourceUnchanged",true)),StandardOpenOption.CREATE_NEW);
            scheduler.resume();long deadline=System.nanoTime()+5_000_000_000L;
            while(System.nanoTime()<deadline&&!"CLOSED_TIMEOUT".equals(jdbc.queryForObject("SELECT status FROM group_buy_orders WHERE public_id=?",String.class,order.get("orderId"))))Thread.sleep(25);
            assertThat(jdbc.queryForObject("SELECT status FROM group_buy_orders WHERE public_id=?",String.class,order.get("orderId"))).isEqualTo("CLOSED_TIMEOUT");
            assertThat(jdbc.queryForObject("SELECT inventory FROM deal_packages WHERE id=?",Integer.class,activity.getDeal().getId())).isOne();
            assertThat(jdbc.queryForObject("SELECT reserved_count+committed_count FROM seckill_quotas WHERE activity_id=? AND user_id=?",Integer.class,activity.getId(),user)).isZero();
            Files.writeString(evidence.resolve("resume-result.json"),"{\"passed\":true,\"inventory\":1,\"quota\":0,\"status\":\"CLOSED_TIMEOUT\"}",StandardOpenOption.CREATE_NEW);
        } finally {scheduler.pauseAndDrain(5000);}
    }

    /** 核查真实门禁与实际活动扫描数；配置已关闭或此刻无到期单均不能代替停写事实。 */
    private void assertPaused(){
        var worker=(Map<?,?>)scheduler.snapshot().get("worker");
        assertThat(worker.get("accepting")).isEqualTo(false);
        assertThat(worker.get("activeScans")).isEqualTo(0);
        assertThat(worker.get("backgroundWriters")).isEqualTo(0);
    }

    /** 每表完整排序行的摘要只驻留内存；输出仅表名、行数和SHA256，不泄露业务明文。 */
    private Map<String,String> tableDigests() throws Exception {
        var result=new TreeMap<String,String>();var mapper=new ObjectMapper();
        var tables=jdbc.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME",String.class);
        assertThat(tables.size()).isLessThanOrEqualTo(256);
        for(String table:tables){
            assertThat(table).matches("[A-Za-z0-9_]+");
            var rows=jdbc.queryForList("SELECT * FROM `"+table+"`");assertThat(rows.size()).isLessThanOrEqualTo(100000);
            var encoded=new ArrayList<String>();for(var row:rows)encoded.add(mapper.writeValueAsString(row));Collections.sort(encoded);
            result.put(table,rows.size()+":"+sha(String.join("\n",encoded).getBytes(StandardCharsets.UTF_8)));
        }
        return result;
    }

    /** 计算证据完整字节摘要，调用方控制其为私有导出或内存中规范化行。 */
    private static String sha(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}

    /** 独立合成活动只用于本轮维护期到期单，不改UI活动及其他任务的库存。 */
    private SeckillActivityEntity createActivity(){
        var now=time.now();var date=now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        var store=new StoreEntity(UUID.randomUUID().toString(),"维护验收","维护备份小馆","310000","合成地址","000","中餐",BigDecimal.ZERO,BigDecimal.ZERO,3990,true,"全天",null);em.persist(store);
        var deal=new DealPackageEntity(UUID.randomUUID().toString(),store,"维护专用券","合成维护说明",3990,7900,1,date.plusDays(1),date.plusDays(30),true,1,1);deal.replaceCompareFacts(2,null,"仅隔离验收");em.persist(deal);
        var activity=new SeckillActivityEntity(UUID.randomUUID().toString(),deal,now.minusSeconds(10),now.plusSeconds(3600));em.persist(activity);em.flush();return activity;
    }
}
