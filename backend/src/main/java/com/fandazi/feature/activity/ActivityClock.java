// 活动时间边界：生产依赖真实系统时钟，测试可注入固定 Clock；请求中不接收业务时间。
package com.fandazi.feature.activity;

import com.fandazi.core.time.BusinessTime;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** 将系统瞬间统一转换为上海微秒时间，避免数据库精度与跨日判断漂移。 */
@Component
public class ActivityClock {
    private final Clock clock;
    /** 生产构造器仅采用系统时钟，不能由 HTTP 参数替换。 */
    public ActivityClock() { this(Clock.systemUTC()); }
    /** 包内测试构造器固定业务时间，覆盖午夜和宽限期边界。 */
    ActivityClock(Clock clock) { this.clock = clock; }
    /** 返回数据库兼容的当前业务时间，不产生副作用。 */
    public LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), BusinessTime.ZONE).truncatedTo(ChronoUnit.MICROS); }
}
