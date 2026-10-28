// 活动规则模块：按上海业务时间计算任务进度，输入为服务端支付/退款事实，不接收客户端完成次数。
package com.fandazi.feature.activity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Objects;

/** 无数据库副作用的规则计算器；持久化、身份解析、锁和奖励事务由活动服务负责。 */
public final class ActivityRules {
    private ActivityRules() {}
    /** 仅开放首期两类规则，避免通过自由表达式执行未知运营逻辑。 */
    public enum Type { DAILY_ORDER_COUNT, CONSECUTIVE_ORDER_DAYS }
    /** 规则快照；target 为每天订单数，days 为连续天数，reward 为整数金币。 */
    public record Rule(Type type, int target, int days, int reward) {
        /** 规则由服务端配置，非法定义必须拒绝，不能默认为零门槛或负奖励。 */
        public Rule {
            Objects.requireNonNull(type, "任务类型不能为空");
            if (target <= 0 || days <= 0 || reward <= 0 || (type == Type.DAILY_ORDER_COUNT && days != 1))
                throw new IllegalArgumentException("任务规则配置无效");
        }
    }
    /** 一个订单的权威事实；quantity 用于证明多份仍只计一单，退款排除由数据库适配层决定。 */
    public record OrderFact(String id, long owner, LocalDateTime paidAt, int quantity, boolean paid, boolean refundExcluded) {}
    /** 日进度来自业务日期聚合，completed 与颜色无关，供客户端无障碍文案使用。 */
    public record Day(LocalDate date, int count, int target, boolean completed) {}
    /** 资格结果只描述未发奖状态；已领取和已冲正必须覆盖为数据库奖励事实。 */
    public record Progress(String status, int completed, int required, List<Day> days, List<String> orderIds) {}

    /** 输入本人订单、冻结窗口与当前服务端时间，返回可回读进度；非法时间快照抛异常，不自行修补。 */
    public static Progress evaluate(long owner, Rule rule, LocalDateTime acceptedAt, LocalDateTime windowEnd,
                                    LocalDateTime claimDeadline, LocalDateTime now, List<OrderFact> orders) {
        // 输入校验：窗口和宽限期必须来自领取时的完整规则快照，禁止刷新时顺延截止。
        Objects.requireNonNull(rule); Objects.requireNonNull(acceptedAt); Objects.requireNonNull(now);
        Objects.requireNonNull(orders); Objects.requireNonNull(windowEnd); Objects.requireNonNull(claimDeadline);
        if (owner <= 0 || !windowEnd.equals(acceptedAt.toLocalDate().plusDays(rule.days()).atStartOfDay())
                || !claimDeadline.equals(windowEnd.plusDays(1)) || now.isBefore(acceptedAt))
            throw new IllegalArgumentException("任务时间快照无效");

        // 数据规范化：订单数按公开订单唯一标识去重，数量不参与累计；他人及退款中订单直接排除。
        var distinct = new LinkedHashMap<String, OrderFact>();
        orders.stream().filter(order -> order.owner() == owner && order.paid() && !order.refundExcluded()
                        && order.paidAt() != null && !order.paidAt().isBefore(acceptedAt)
                        && order.paidAt().isBefore(windowEnd) && !order.paidAt().isAfter(now))
                .sorted(Comparator.comparing(OrderFact::paidAt).thenComparing(OrderFact::id))
                .forEach(order -> distinct.putIfAbsent(order.id(), order));
        var days = new ArrayList<Day>();
        boolean interrupted = false;
        int completedDays = 0;
        for (int index = 0; index < rule.days(); index++) {
            var date = acceptedAt.toLocalDate().plusDays(index);
            int count = (int) distinct.values().stream().filter(order -> order.paidAt().toLocalDate().equals(date)).count();
            boolean completed = count >= rule.target();
            days.add(new Day(date, count, rule.target(), completed));
            if (completed) completedDays++;
            // 只检查已经结束的自然日；今天尚未完成应允许继续，历史缺口不能靠之后多下单弥补。
            if (date.isBefore(now.toLocalDate()) && !completed) interrupted = true;
        }

        // 规则判断：先识别窗口内已完成事实，再判断领奖期限；宽限期回读不会抹掉历史达标。
        boolean daily = rule.type() == Type.DAILY_ORDER_COUNT;
        int completed = daily ? distinct.size() : completedDays;
        int required = daily ? rule.target() : rule.days();
        String status;
        if (completed >= required) status = now.isBefore(claimDeadline) ? "READY" : "CLAIM_EXPIRED";
        else if (!daily && interrupted) status = "INTERRUPTED";
        else if (!now.isBefore(windowEnd)) status = "EXPIRED";
        else status = "IN_PROGRESS";
        // 结果构造：保留全部仍合格订单，供初次发放和退款后重算共同使用，不只截取最初证明单。
        return new Progress(status, completed, required, List.copyOf(days), List.copyOf(distinct.keySet()));
    }
}
