// 秒杀活动领域测试：验证从后端持久化到用户展示前必须成立的字段和活动窗口约束。
package com.fandazi.feature.seckill;

import com.fandazi.feature.catalog.DealPackageEntity;
import com.fandazi.feature.catalog.StoreEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

/** 真实活动对象的行为断言；不使用模拟仓储，不把构造失败或编译错误当成业务通过。 */
class SeckillActivityDomainTest {
    /** 构造无个人资料的合成套餐；价格、使用日期均显式给定，避免从当前日期猜测预期。 */
    private DealPackageEntity deal(int price, int original, int stock, int limit, String title, String rules) {
        var store = new StoreEntity("sk-test-store", "测试品牌", "测试门店", "test", "测试地址", "000", "中餐",
                BigDecimal.ZERO, BigDecimal.ZERO, 1000, true, "全天", null);
        var deal = new DealPackageEntity("sk-test-deal", store, title, "测试套餐描述", price, original, stock,
                LocalDate.parse("2026-10-29"), LocalDate.parse("2026-11-29"), true, limit, 1);
        deal.replaceCompareFacts(2, null, rules);
        return deal;
    }

    @ParameterizedTest
    @CsvSource({"0,7900,100,1", "8000,7900,100,1", "3990,7900,0,1", "3990,7900,100001,1",
            "3990,7900,100,0", "3990,7900,100,100", "3990,7900,1,2"})
    void invalidPriceStockOrLimitCannotBecomeActivity(int price, int original, int stock, int limit) {
        // 若构造器漏掉任一领域边界，该活动会进入目录；必须在关联创建前拒绝，不能留半成品。
        assertThatThrownBy(() -> new SeckillActivityEntity("sk-activity", deal(price,original,stock,limit,"双人套餐","午市堂食"),
                Instant.parse("2026-10-29T04:00:00Z"), Instant.parse("2026-10-29T06:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundariesUseInclusiveStartAndExclusiveEnd() {
        // 固定UTC时刻独立推导预期，证明开抢等号开放而结束等号关闭。
        var activity = new SeckillActivityEntity("sk-activity", deal(3990,7900,100,1,"双人套餐","午市堂食"),
                Instant.parse("2026-10-29T04:00:00Z"), Instant.parse("2026-10-29T06:00:00Z"));
        assertThat(activity.stateAt(Instant.parse("2026-10-29T03:59:59.999Z"))).isEqualTo("UPCOMING");
        assertThat(activity.stateAt(Instant.parse("2026-10-29T04:00:00Z"))).isEqualTo("LIVE");
        assertThat(activity.stateAt(Instant.parse("2026-10-29T05:59:59.999Z"))).isEqualTo("LIVE");
        assertThat(activity.stateAt(Instant.parse("2026-10-29T06:00:00Z"))).isEqualTo("ENDED");
    }

    @Test
    void invalidTimeAndMissingRulesAreRejected() {
        // 活动结束必须晚于开始；最后订单的300秒期限不能越过券使用截止。
        var valid = deal(3990,7900,100,1,"双人套餐","午市堂食");
        var start=Instant.parse("2026-10-29T04:00:00Z");
        assertThatThrownBy(() -> new SeckillActivityEntity("sk-equal",valid,start,start)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SeckillActivityEntity("sk-expiry",valid,start,Instant.parse("2026-11-29T15:55:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SeckillActivityEntity("sk-rules",deal(3990,7900,100,1,"双人套餐",null),start,start.plusSeconds(3600)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unicodeLimitCountsCodePointsAndAcceptsValidMaximum() {
        // 补充平面字符占两个UTF-16单元，但按一个Unicode字符计数；同时检验总库存和单价合法上界。
        var start=Instant.parse("2026-10-29T04:00:00Z");
        assertThatCode(() -> new SeckillActivityEntity("sk-max",deal(99999999,99999999,100000,99,"𠮷".repeat(40),"规则"),start,start.plusSeconds(3600)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new SeckillActivityEntity("sk-long",deal(3990,7900,100,1,"𠮷".repeat(41),"规则"),start,start.plusSeconds(3600)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
