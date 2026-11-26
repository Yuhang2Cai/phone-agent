// 秒杀权威时间：使用当前事务绑定的数据库连接，避免多实例主机或手机时钟偏移。
package com.fandazi.feature.seckill;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** MySQL使用数据库UTC时间；H2仅用于定向测试，真实边界仍须在MySQL验证。 */
@Component
public class SeckillTimeSource {
    private final JdbcTemplate jdbc;
    private final String query;
    /** 检测数据库种类并固定查询；不支持的引擎启动失败，不退回应用主机时间。 */
    public SeckillTimeSource(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        String product=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c->c.getMetaData().getDatabaseProductName());
        if("MySQL".equals(product)) query="SELECT UTC_TIMESTAMP(6)";
        else if("H2".equals(product)) query="SELECT CURRENT_TIMESTAMP(6)";
        else throw new IllegalStateException("秒杀权威时间不支持当前数据库");
    }
    /** 读取UTC权威瞬时；JDBC沿用Spring事务连接，调用者应在取得相关领域行锁后调用。 */
    public Instant now() {
        // MySQL返回无偏移UTC字段，用显式UTC日历解析，避免JVM默认时区再次偏移八小时。
        return jdbc.queryForObject(query,(rows,index)->rows.getTimestamp(1,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant());
    }
}
