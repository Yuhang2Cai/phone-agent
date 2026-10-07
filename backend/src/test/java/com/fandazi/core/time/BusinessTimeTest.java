// 时间语义回归：历史上海本地时间必须转换成同一 UTC 瞬间，不能依赖启动机器默认时区。
package com.fandazi.core.time;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/** 保护 Android 只格式化 UTC 的边界，明确禁止直接给本地时间追加 Z。 */
class BusinessTimeTest {
    /** 历史 10 点上海时间应为 02 点 UTC，合法空时间原样保持 null。 */
    @Test void convertsDomainLocalTimeToActualUtcInstant() {
        assertThat(BusinessTime.utc(LocalDateTime.of(2026, 9, 29, 10, 0))).isEqualTo("2026-09-29T02:00:00Z");
        assertThat(BusinessTime.utc(null)).isNull();
    }
    /** 更换默认时区仍得到真实当前瞬间，结束时恢复全局时区避免污染其他测试。 */
    @Test void creationTimeIsIndependentOfMachineDefaultZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            // 数据库只保留微秒，比较下界也使用同一精度，避免同一微秒内合法截断被误判为时间倒退。
            Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
            Instant created = Instant.parse(BusinessTime.utc(BusinessTime.now()));
            assertThat(created).isAfterOrEqualTo(before).isBeforeOrEqualTo(Instant.now());
        } finally { TimeZone.setDefault(original); }
    }

    /** 创建时间必须先对齐数据库微秒精度，防止退款首返与持久化回读的 UTC 字符串不同。 */
    @Test void creationTimeMatchesDatabaseMicrosecondPrecision() {
        // 连续取样覆盖系统时钟的纳秒尾数；每次结果都应能原样写入微秒列，而不是依赖数据库再次截断。
        for (int sample = 0; sample < 100; sample++) {
            LocalDateTime created = BusinessTime.now();
            assertThat(created).isEqualTo(created.truncatedTo(ChronoUnit.MICROS));
        }
    }
}
