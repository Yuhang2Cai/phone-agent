// 活动规则定向测试：固定业务时间与合成订单，防止领取前支付、数量膨胀和跨日拼接误发奖励。
package com.fandazi.feature.activity;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.fandazi.feature.activity.ActivityRules.*;

/** 无 HTTP、数据库或机器当前时钟依赖；这些测试不替代真实 MySQL 并发及端到端验收。 */
class ActivityRulesTest {
    private static final LocalDateTime START = LocalDateTime.parse("2026-10-28T10:00:00");
    private static final LocalDateTime END = LocalDateTime.parse("2026-10-29T00:00:00");
    private static final Rule DAILY = new Rule(Type.DAILY_ORDER_COUNT, 1, 1, 10);

    /** 付款边界包含领取瞬间，排除领取前付款；一单十份与重复查询行仍只贡献一次。 */
    @Test void acceptBeforePayAndDistinctOrders() {
        var paid = new OrderFact("one", 1, START, 10, true, false);
        var result = evaluate(1, DAILY, START, END, END.plusDays(1), START.plusHours(1), List.of(
                new OrderFact("before", 1, START.minusNanos(1000), 1, true, false), paid, paid,
                new OrderFact("other-owner", 2, START, 1, true, false)));
        assertEquals("READY", result.status());
        assertEquals(1, result.completed());
        assertEquals(List.of("one"), result.orderIds());
    }

    /** 每个自然日独立达标，跨日边界不能被服务器进程默认时区改变。 */
    @Test void consecutiveDaysFollowCalendarBoundaries() {
        var rule = new Rule(Type.CONSECUTIVE_ORDER_DAYS, 1, 3, 50);
        var result = evaluate(1, rule, START, END.plusDays(2), END.plusDays(3), START.plusDays(2), List.of(
                fact("day1", END.minusSeconds(1)), fact("day2", END), fact("day3", END.plusDays(1))));
        assertEquals("READY", result.status());
        assertEquals(3, result.completed());
        assertEquals(List.of(true, true, true), result.days().stream().map(Day::completed).toList());
    }

    /** 前一自然日缺失即中断，后面多下单不能跳过缺失日期重新拼接连续天数。 */
    @Test void endedMissingDayInterruptsChallenge() {
        var result = evaluate(1, new Rule(Type.CONSECUTIVE_ORDER_DAYS, 1, 3, 50), START, END.plusDays(2), END.plusDays(3),
                START.plusDays(2), List.of(fact("day1", START), fact("day3", START.plusDays(2))));
        assertEquals("INTERRUPTED", result.status());
    }

    /** 状态文字不能取代支付时间事实；只有具有持久化付款时间的有效订单才计数。 */
    @Test void missingPaymentAndPendingRefundAreExcluded() {
        var result = evaluate(1, DAILY, START, END, END.plusDays(1), END, List.of(
                new OrderFact("missing", 1, null, 1, true, false),
                new OrderFact("pending", 1, START, 1, false, false),
                new OrderFact("refund", 1, START, 1, true, true)));
        assertEquals("EXPIRED", result.status());
        assertTrue(result.orderIds().isEmpty());
    }

    /** 窗口结束后才回读仍可在宽限期领奖；领奖截止瞬间不可再领，迟到支付也不能补单。 */
    @Test void deadlineUsesHalfOpenWindowAndDoesNotEraseEarlierCompletion() {
        var orders = List.of(fact("valid", START), fact("late", END));
        var grace = evaluate(1, DAILY, START, END, END.plusDays(1), END.plusHours(1), orders);
        assertEquals("READY", grace.status());
        assertEquals(List.of("valid"), grace.orderIds());
        assertEquals("CLAIM_EXPIRED", evaluate(1, DAILY, START, END, END.plusDays(1), END.plusDays(1), orders).status());
    }

    /** 退款冲正必须复核全部窗口订单；原证明订单退款但有冗余合格单时仍然达标。 */
    @Test void remainingOrdersCanStillQualifyAfterRefund() {
        var result = evaluate(1, DAILY, START, END, END.plusDays(1), START.plusHours(1), List.of(
                new OrderFact("original", 1, START, 1, true, true), fact("replacement", START.plusMinutes(1))));
        assertEquals("READY", result.status());
        assertEquals(List.of("replacement"), result.orderIds());
    }

    /** 统一产生本人已支付订单，避免每个测试独立改变无关输入条件。 */
    private OrderFact fact(String id, LocalDateTime paidAt) { return new OrderFact(id, 1, paidAt, 1, true, false); }
}
