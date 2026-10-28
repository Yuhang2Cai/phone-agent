// 活动持久化适配：集中预编译 SQL、同用户锁和当前支付/退款事实，避免视图计算散落数据库访问。
package com.fandazi.feature.activity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 包内仓储只在服务事务中使用；锁序为账户、实例、订单、券、退款、凭据/流水。 */
final class ActivityStore {
    final JdbcTemplate jdbc;
    /** 实例与定义共用的不可变配置投影；版本更新通过新增行发布。 */
    record Definition(String id,int version,String title,ActivityRules.Rule rule,String campaign,
                      LocalDateTime start,LocalDateTime end,boolean enabled) {}
    /** 实例携带领取快照，奖励状态只从数据库读取。 */
    record Instance(String id,long owner,String definition,int version,String cycle,String title,ActivityRules.Rule rule,
                    LocalDateTime accepted,LocalDateTime end,LocalDateTime deadline,String rewardStatus) {}
    /** 构造器只绑定同一数据源，不建立额外连接池。 */
    ActivityStore(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    /** 原子建行后获取排他锁；缺行并发使用唯一主键协调，不先读空行形成升级死锁。 */
    int lockAccount(long user) {
        jdbc.update("insert into coin_accounts(user_id,balance,version) values(?,0,0) on duplicate key update user_id=user_id",user);
        return jdbc.queryForObject("select balance from coin_accounts where user_id=? for update",Integer.class,user);
    }
    /** 最新规则即使停用也不会回退到较旧启用版本；领取时锁版本行，避免与配置修改并行。 */
    Definition definition(String id,boolean lock) {
        var rows=jdbc.query("select * from activity_definitions where definition_id=? order by rule_version desc limit 1"+(lock?" for update":""),this::definition,id);
        if(rows.isEmpty())throw new ActivityException(404,"NOT_FOUND");return rows.get(0);
    }
    /** 一次返回所有最新定义，列表计算不按卡片逐一请求定义。 */
    List<Definition> definitions() {
        return jdbc.query("select d.* from activity_definitions d where not exists (select 1 from activity_definitions n where n.definition_id=d.definition_id and n.rule_version>d.rule_version) order by d.task_type,d.target_count,d.day_count,d.definition_id",this::definition);
    }
    /** 本人实例按公开 ID 查询，越权与不存在使用同一异常。 */
    Instance instance(long user,String id,boolean lock) {
        var rows=jdbc.query("select * from activity_instances where user_id=? and instance_id=?"+(lock?" for update":""),this::instance,user,id);
        if(rows.isEmpty())throw new ActivityException(404,"NOT_FOUND");return rows.get(0);
    }
    /** 领取后重入/同周期不同键共同返回原实例，当前读不受外层 RR 历史快照影响。 */
    Optional<Instance> cycle(long user,String definition,String cycle) {
        return jdbc.query("select * from activity_instances where user_id=? and definition_id=? and cycle_key=? for update",this::instance,user,definition,cycle).stream().findFirst();
    }
    /** 返回近七天结束记录及当前活动周期的连续实例；同周期失败不能被误投影为可重新领取。 */
    List<Instance> current(long user,LocalDateTime now) {
        return jdbc.query("select i.* from activity_instances i where i.user_id=? and (i.claim_deadline>=? or (i.task_type='CONSECUTIVE_ORDER_DAYS' and exists (select 1 from activity_definitions d where d.definition_id=i.definition_id and d.campaign_id=i.cycle_key and not exists (select 1 from activity_definitions n where n.definition_id=d.definition_id and n.rule_version>d.rule_version)))) order by i.accepted_at desc,i.instance_id",this::instance,user,now.toLocalDate().minusDays(7).atStartOfDay());
    }
    /** 校验原请求的完整绑定；成功写入后保存关联实例，网络中断仍能回读。 */
    Optional<String> request(long user,String request,String action,String target,int version) {
        var rows=jdbc.queryForList("select * from activity_requests where user_id=? and request_id=? for update",user,request);
        if(rows.isEmpty())return Optional.empty();var row=rows.get(0);
        if(!action.equals(row.get("action"))||!target.equals(row.get("target_id"))||version!=((Number)row.get("expected_version")).intValue())
            throw new ActivityException(409,"IDEMPOTENCY_CONFLICT");
        return Optional.of((String)row.get("instance_id"));
    }
    /** 只为已持久化结果保存请求键；资格拒绝不会伪造成功记录。 */
    void remember(long user,String request,String action,String target,int version,String instance) {
        jdbc.update("insert into activity_requests(user_id,request_id,action,target_id,expected_version,instance_id) values(?,?,?,?,?,?)",user,request,action,target,version,instance);
    }

    /** 批量读取区间内本人支付事实；写事务采用三个升序当前读，避免等待退款锁后仍读旧快照。 */
    List<ActivityRules.OrderFact> facts(long user,LocalDateTime start,LocalDateTime end,boolean lock) {
        return facts(user,start,end,lock,true);
    }
    /** 已发奖的终态补偿只排除成功退款；其他尚在申请的订单不能提前触发不可逆撤回。 */
    List<ActivityRules.OrderFact> facts(long user,LocalDateTime start,LocalDateTime end,boolean lock,boolean excludePending) {
        var orders=jdbc.queryForList("select id,public_id,user_id,paid_at,quantity,status,paid_total_amount from group_buy_orders where user_id=? and paid_at>=? and paid_at<? order by id"+(lock?" for update":""),user,start,end);
        if(orders.isEmpty())return List.of();
        // 订单集合一次生成占位符，券和退款各一条批量查询；不随任务卡片数增加 SQL 次数。
        var ids=orders.stream().map(row->row.get("id")).toArray();
        String marks=String.join(",",Collections.nCopies(ids.length,"?"));
        var vouchers=jdbc.queryForList("select id,order_id,status from vouchers where order_id in ("+marks+") order by id"+(lock?" for update":""),ids);
        var excluded=new HashSet<Long>();var voucherOrders=new HashMap<Long,Long>();
        for(var voucher:vouchers) {
            long order=((Number)voucher.get("order_id")).longValue();voucherOrders.put(((Number)voucher.get("id")).longValue(),order);
            if("REFUNDED".equals(voucher.get("status")))excluded.add(order);
        }
        if(!vouchers.isEmpty()) {
            String voucherMarks=String.join(",",Collections.nCopies(vouchers.size(),"?"));
            var refunds=jdbc.queryForList("select voucher_id,status from refund_requests where voucher_id in ("+voucherMarks+") order by id"+(lock?" for update":""),vouchers.stream().map(v->v.get("id")).toArray());
            // 申请/批准均冻结资格，拒绝后自动恢复；任一券完成退款即排除整个订单。
            var excludedStates=excludePending?Set.of("REQUESTED","APPROVED","COMPLETED"):Set.of("COMPLETED");
            for(var refund:refunds)if(excludedStates.contains(refund.get("status")))
                excluded.add(voucherOrders.get(((Number)refund.get("voucher_id")).longValue()));
        }
        return orders.stream().map(row->new ActivityRules.OrderFact((String)row.get("public_id"),user,
                local(row.get("paid_at")),((Number)row.get("quantity")).intValue(),
                Set.of("PAID","PARTIALLY_USED","USED").contains(row.get("status"))&&row.get("paid_total_amount")!=null,
                excluded.contains(((Number)row.get("id")).longValue()))).toList();
    }
    /** 只锁受退款订单支付时刻影响的已发奖实例，过往已结束窗口仍可冲正。 */
    List<Instance> affectedGrants(long user,LocalDateTime paidAt) {
        return jdbc.query("select * from activity_instances where user_id=? and reward_status='CLAIMED' and accepted_at<=? and window_end>? order by instance_id for update",this::instance,user,paidAt,paidAt);
    }
    /** 统一数据库驱动时间投影；MySQL DATETIME 返回 LocalDateTime，TIMESTAMP 可能返回 Timestamp。 */
    static LocalDateTime local(Object value) {
        return value instanceof java.sql.Timestamp timestamp?timestamp.toLocalDateTime():(LocalDateTime)value;
    }
    /** 读取定义时复用规则输入校验，非法运营数据阻止请求而不制造零门槛任务。 */
    private Definition definition(ResultSet row,int index)throws SQLException {
        return new Definition(row.getString("definition_id"),row.getInt("rule_version"),row.getString("title"),rule(row),
                row.getString("campaign_id"),row.getTimestamp("starts_at").toLocalDateTime(),row.getTimestamp("ends_at").toLocalDateTime(),row.getBoolean("enabled"));
    }
    /** 实例映射只读快照，不连接当前定义覆盖已领取内容。 */
    private Instance instance(ResultSet row,int index)throws SQLException {
        return new Instance(row.getString("instance_id"),row.getLong("user_id"),row.getString("definition_id"),row.getInt("rule_version"),row.getString("cycle_key"),row.getString("title"),rule(row),row.getTimestamp("accepted_at").toLocalDateTime(),row.getTimestamp("window_end").toLocalDateTime(),row.getTimestamp("claim_deadline").toLocalDateTime(),row.getString("reward_status"));
    }
    /** 规则类型为固定枚举；未知类型不能降级为每日任务。 */
    private ActivityRules.Rule rule(ResultSet row)throws SQLException {
        return new ActivityRules.Rule(ActivityRules.Type.valueOf(row.getString("task_type")),row.getInt("target_count"),row.getInt("day_count"),row.getInt("reward_coins"));
    }
}
