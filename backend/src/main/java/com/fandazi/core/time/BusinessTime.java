// 时间边界：历史 LocalDateTime 按上海业务时间保存，页面统一获得 UTC 瞬间；依赖 java.time。
package com.fandazi.core.time;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** 固定业务时区使进程默认时区变化不会改变下单、核销与退款时间含义。 */
public final class BusinessTime {
    /** 数据库旧本地时间与到店有效期均以 Asia/Shanghai 为业务语义。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private BusinessTime() {}

    /** 返回明确上海时区、微秒精度的当前本地时间，供现有 DATETIME/TIMESTAMP 领域接口持久化。 */
    public static LocalDateTime now() {
        // 在业务创建边界统一截断到数据库精度，避免退款首返保留纳秒、回读只剩微秒而产生时间漂移。
        return LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.MICROS);
    }

    /** 将真实业务时间转成带 Z 的 UTC ISO 字符串；未核销等 null 事实仍为 null。 */
    public static String utc(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant().toString();
    }
}
