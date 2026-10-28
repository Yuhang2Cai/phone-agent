// 活动事务入口：规则、幂等请求和奖励持久化在同一个业务数据库中组合。
package com.fandazi.feature.activity;

import java.util.*;
import java.time.LocalDateTime;
import com.fandazi.core.time.BusinessTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 服务端权威活动接口；客户端不能指定用户以外的奖励或完成事实。 */
@Service
public class ActivityService {
    private final ActivityStore store;
    private final TransactionTemplate writes;
    private final ActivityClock clock;
    /** 注入同库事务与可控业务时钟。 */
    public ActivityService(JdbcTemplate jdbc, PlatformTransactionManager transactions, ActivityClock clock) {
        this.store=new ActivityStore(jdbc);this.writes=new TransactionTemplate(transactions);this.clock=clock;
    }
    /** 领取任务，返回冻结的本人实例。 */
    public Map<String,Object> accept(long user, String definition, int version, String request) {
        requireUser(user);String target=uuid(definition);String key=uuid(request);
        if(version<=0)throw new ActivityException(400,"INVALID_REQUEST");
        return writes.execute(transaction->{
            // 输入身份和幂等：账户行是同用户所有活动写入的第一个业务锁，当前读不复用旧事务快照。
            store.lockAccount(user);var prior=store.request(user,key,"ACCEPT",target,version);
            if(prior.isPresent())return view(store.instance(user,prior.get(),true),clock.now(),false);
            var rule=store.definition(target,true);var now=clock.now();
            if(rule.version()!=version)throw new ActivityException(409,"RULE_VERSION_CHANGED");
            String cycle=rule.rule().type()==ActivityRules.Type.DAILY_ORDER_COUNT?now.toLocalDate().toString():rule.campaign();
            var existing=store.cycle(user,target,cycle);
            // 同周期已领取返回原快照，后续停用不能让合法旧请求丢失可回读对象。
            if(existing.isPresent()) {
                store.remember(user,key,"ACCEPT",target,version,existing.get().id());return view(existing.get(),now,false);
            }
            if(!rule.enabled()||now.isBefore(rule.start())||!now.isBefore(rule.end()))throw new ActivityException(409,"TASK_UNAVAILABLE");
            var end=now.toLocalDate().plusDays(rule.rule().days()).atStartOfDay();
            if(end.isAfter(rule.end()))throw new ActivityException(409,"INSUFFICIENT_WINDOW");
            if(rule.rule().type()==ActivityRules.Type.CONSECUTIVE_ORDER_DAYS) {
                // 更换活动周期不能绕过同定义已有的重叠窗口；先按用户互斥，避免两个缺行并发领取。
                var overlap=store.jdbc.queryForList("select instance_id from activity_instances where user_id=? and definition_id=? and window_end>? order by instance_id for update",user,target,now);
                if(!overlap.isEmpty())throw new ActivityException(409,"ACTIVE_INSTANCE_EXISTS");
            }
            String id=UUID.randomUUID().toString();
            store.jdbc.update("insert into activity_instances(instance_id,user_id,definition_id,rule_version,cycle_key,title,task_type,target_count,day_count,reward_coins,accepted_at,window_end,claim_deadline,reward_status) values(?,?,?,?,?,?,?,?,?,?,?,?,?,'NONE')",
                    id,user,target,version,cycle,rule.title(),rule.rule().type().name(),rule.rule().target(),rule.rule().days(),rule.rule().reward(),now,end,end.plusDays(1));
            store.remember(user,key,"ACCEPT",target,version,id);
            return view(store.instance(user,id,true),now,false);
        });
    }
    /** 手动领取奖励，返回唯一账本回执。 */
    public Map<String,Object> claim(long user, String instance, String request) {
        requireUser(user);String id=uuid(instance);String key=uuid(request);
        return writes.execute(transaction->{
            // 先按用户定位不可变归属，避免为不可见对象创建账户或暴露其领奖状态。
            store.instance(user,id,false);int balance=store.lockAccount(user);
            var prior=store.request(user,key,"CLAIM",id,0);var owned=store.instance(user,id,true);
            if(prior.isPresent())return receipt(owned,balance,true);
            // 实例唯一凭据是跨请求键的最终防重屏障，已撤回实例也不会重新发奖。
            if(!owned.rewardStatus().equals("NONE")) {
                store.remember(user,key,"CLAIM",id,0,id);return receipt(owned,balance,true);
            }
            var now=clock.now();var facts=store.facts(user,owned.accepted(),owned.end(),true);
            var progress=ActivityRules.evaluate(user,owned.rule(),owned.accepted(),owned.end(),owned.deadline(),now,facts);
            if(!progress.status().equals("READY"))throw new ActivityException(409,progress.status().equals("IN_PROGRESS")?"NOT_READY":progress.status());
            final int next;
            try {next=Math.addExact(balance,owned.rule().reward());}
            catch(ArithmeticException error){throw new ActivityException(409,"BALANCE_LIMIT");}
            String receipt=UUID.randomUUID().toString();
            // 凭据、完整订单依据、流水、余额和实例状态共用事务；任一步异常均回滚。
            store.jdbc.update("insert into activity_reward_grants(receipt_id,instance_id,user_id,amount,created_at) values(?,?,?,?,?)",receipt,id,user,owned.rule().reward(),now);
            for(String order:progress.orderIds())store.jdbc.update("insert into activity_reward_orders(receipt_id,order_id,paid_at) select ?,id,paid_at from group_buy_orders where user_id=? and public_id=?",receipt,user,order);
            store.jdbc.update("insert into coin_transactions(transaction_id,user_id,receipt_id,kind,amount,created_at) values(?,?,?,'REWARD',?,?)",UUID.randomUUID().toString(),user,receipt,owned.rule().reward(),now);
            store.jdbc.update("update coin_accounts set balance=?,version=version+1 where user_id=?",next,user);
            store.jdbc.update("update activity_instances set reward_status='CLAIMED' where instance_id=? and user_id=?",id,user);
            store.remember(user,key,"CLAIM",id,0,id);
            return receipt(store.instance(user,id,true),next,true);
        });
    }

    /** 本人详情同时返回原奖励回执，超时核查不会创建新写请求。 */
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public Map<String,Object> detail(long user,String instance) {
        requireUser(user);var owned=store.instance(user,uuid(instance),false);var result=view(owned,clock.now(),false);
        if(!owned.rewardStatus().equals("NONE"))result.put("receipt",receipt(owned,balance(user),false));
        return result;
    }

    /** 游客只看公开定义；登录后批量读取本人实例与订单，SQL 数量不随卡片数线性增长。 */
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public Map<String,Object> tasks(Long user) {
        var now=clock.now();var definitions=store.definitions();var instances=user==null?List.<ActivityStore.Instance>of():store.current(user,now);
        var facts=instances.isEmpty()?List.<ActivityRules.OrderFact>of():store.facts(user,
                instances.stream().map(ActivityStore.Instance::accepted).min(LocalDateTime::compareTo).orElseThrow(),
                instances.stream().map(ActivityStore.Instance::end).max(LocalDateTime::compareTo).orElseThrow(),false);
        var cards=new ArrayList<Map<String,Object>>();var shown=new HashSet<String>();
        for(var definition:definitions) {
            String cycle=definition.rule().type()==ActivityRules.Type.DAILY_ORDER_COUNT?now.toLocalDate().toString():definition.campaign();
            var existing=instances.stream().filter(item->item.definition().equals(definition.id())&&item.cycle().equals(cycle)).findFirst();
            if(existing.isPresent()) {cards.add(project(existing.get(),now,facts));shown.add(existing.get().id());continue;}
            if(!definition.enabled()||now.isBefore(definition.start())||!now.isBefore(definition.end()))continue;
            var card=new LinkedHashMap<String,Object>();card.put("definitionId",definition.id());card.put("ruleVersion",definition.version());
            card.put("title",definition.title());card.put("type",definition.rule().type().name());card.put("targetCount",definition.rule().target());
            card.put("dayCount",definition.rule().days());card.put("rewardCoins",definition.rule().reward());card.put("status","AVAILABLE");
            boolean enough=!now.toLocalDate().plusDays(definition.rule().days()).atStartOfDay().isAfter(definition.end());
            boolean overlap=instances.stream().anyMatch(item->item.definition().equals(definition.id())&&item.end().isAfter(now));
            card.put("allowedActions",user==null?List.of("LOGIN"):enough&&!overlap?List.of("ACCEPT"):List.of());
            card.put("reasonCode",!enough?"INSUFFICIENT_WINDOW":overlap?"ACTIVE_INSTANCE_EXISTS":null);cards.add(card);
        }
        // 停用/新周期不能隐藏仍在窗口或宽限期的旧实例，否则用户会失去已领取任务入口。
        for(var instance:instances)if(!shown.contains(instance.id()))cards.add(project(instance,now,facts));
        return Map.of("serverTime",BusinessTime.utc(now),"tasks",cards);
    }

    /** 带原请求键的只读恢复入口；不执行领取，返回列表及原请求对应实例。 */
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public Map<String,Object> tasks(Long user,String request) {
        if(request==null)return tasks(user);
        if(user==null)throw new ActivityException(401,"LOGIN_REQUIRED");requireUser(user);String key=uuid(request);
        // 只读本人原键关联，不按最新版本/今天日期猜测，跨天及配置变更仍可核查。
        var references=store.jdbc.queryForList("select instance_id from activity_requests where user_id=? and request_id=?",String.class,user,key);
        var result=new LinkedHashMap<String,Object>(tasks(user));
        result.put("recoveredTask",references.isEmpty()?null:detail(user,references.get(0)));return result;
    }

    /** 余额由账本账户返回；未发生任何账本写入的用户余额定义为零，不建立虚假流水。 */
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public Map<String,Object> account(long user) {requireUser(user);return Map.of("balance",balance(user),"serverTime",BusinessTime.utc(clock.now()));}

    /** 稳定游标分页只接受本人已有流水 ID；他人游标与未知游标统一拒绝。 */
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public Map<String,Object> transactions(long user,String cursor,int limit) {
        requireUser(user);if(limit<1||limit>100)throw new ActivityException(400,"INVALID_REQUEST");
        long before=Long.MAX_VALUE;
        if(cursor!=null&&!cursor.isBlank()) {
            var ids=store.jdbc.queryForList("select id from coin_transactions where user_id=? and transaction_id=?",Long.class,user,uuid(cursor));
            if(ids.isEmpty())throw new ActivityException(400,"INVALID_CURSOR");before=ids.get(0);
        }
        var rows=store.jdbc.queryForList("select t.id,t.transaction_id,t.kind,t.amount,t.created_at,g.instance_id,i.title from coin_transactions t join activity_reward_grants g on g.receipt_id=t.receipt_id join activity_instances i on i.instance_id=g.instance_id where t.user_id=? and t.id<? order by t.id desc limit ?",user,before,limit+1);
        boolean more=rows.size()>limit;var items=new ArrayList<Map<String,Object>>();
        for(var row:rows.subList(0,Math.min(rows.size(),limit)))items.add(Map.of("transactionId",row.get("transaction_id"),"kind",row.get("kind"),"amount",row.get("amount"),"createdAt",BusinessTime.utc(ActivityStore.local(row.get("created_at"))),"instanceId",row.get("instance_id"),"title",row.get("title")));
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",more?items.get(items.size()-1).get("transactionId"):null);result.put("serverTime",BusinessTime.utc(clock.now()));return result;
    }

    /** 只投影已持久化凭据；重复领奖返回原 receiptId，已撤回不能显示已入账成功。 */
    private Map<String,Object> receipt(ActivityStore.Instance instance,int balance,boolean currentRead) {
        // 写事务可能在等待账户锁前建立 RR 快照；实例为当前读时回执也必须当前读，避免新凭据不可见。
        // 纯详情查询始终使用同一只读事务快照，不能在只读连接上强行获取排他锁。
        var row=store.jdbc.queryForMap("select receipt_id,amount,created_at from activity_reward_grants where user_id=? and instance_id=?"+(currentRead?" for update":""),instance.owner(),instance.id());
        return Map.of("receiptId",row.get("receipt_id"),"instanceId",instance.id(),"amount",row.get("amount"),"balance",balance,
                "status",instance.rewardStatus(),"createdAt",BusinessTime.utc(ActivityStore.local(row.get("created_at"))));
    }
    /** 缺账户是未发生账本，不使用任意默认示例值；整数类型与数据库约束一致。 */
    private int balance(long user) {var rows=store.jdbc.queryForList("select balance from coin_accounts where user_id=?",Integer.class,user);return rows.isEmpty()?0:rows.get(0);}

    /** 内部退款终态协调入口；仅接受可信适配器回调，没有公共 HTTP 路由。 */
    public void coordinateRefundCompletion(long user,String order,Runnable terminalUpdate) {
        requireUser(user);String id=uuid(order);Objects.requireNonNull(terminalUpdate);
        writes.executeWithoutResult(transaction->{
            // 归属在任何写入前核查；时间是支付不可变快照，之后仍重新锁定权威订单/券/退款。
            var targets=store.jdbc.queryForList("select paid_at from group_buy_orders where user_id=? and public_id=?",user,id);
            if(targets.isEmpty())throw new ActivityException(404,"NOT_FOUND");
            if(targets.get(0).get("paid_at")==null)throw new ActivityException(409,"PAYMENT_FACT_MISSING");
            var paid=ActivityStore.local(targets.get(0).get("paid_at"));int balance=store.lockAccount(user);
            var instances=store.affectedGrants(user,paid);
            var start=instances.stream().map(ActivityStore.Instance::accepted).min(LocalDateTime::compareTo).orElse(paid);
            var end=instances.stream().map(ActivityStore.Instance::end).max(LocalDateTime::compareTo).orElse(paid.plusNanos(1000));
            store.facts(user,start,end,true,false);
            // 回调仅供将来可信退款处理器加入同事务；这里不联系渠道，也不提供模拟退款公共接口。
            terminalUpdate.run();
            var completed=store.jdbc.queryForList("select v.id from vouchers v join group_buy_orders o on o.id=v.order_id left join refund_requests r on r.voucher_id=v.id where o.user_id=? and o.public_id=? and (v.status='REFUNDED' or r.status='COMPLETED') order by v.id for update",user,id);
            if(completed.isEmpty())throw new ActivityException(409,"REFUND_NOT_COMPLETED");
            var facts=store.facts(user,start,end,true,false);var now=clock.now();
            for(var instance:instances) {
                var progress=ActivityRules.evaluate(user,instance.rule(),instance.accepted(),instance.end(),instance.deadline(),now,facts);
                // 截止后的历史奖励只检查原窗口是否仍达标，不因领奖期限已过而撤销正确奖励。
                if(progress.completed()>=progress.required())continue;
                var grant=receipt(instance,balance,true);int amount=((Number)grant.get("amount")).intValue();
                int next=Math.subtractExact(balance,amount);
                if(next<0)throw new ActivityException(409,"LEDGER_INCONSISTENT");
                store.jdbc.update("insert into coin_transactions(transaction_id,user_id,receipt_id,kind,amount,created_at) values(?,?,?,'REVERSAL',?,?)",UUID.randomUUID().toString(),user,grant.get("receiptId"),-amount,now);
                store.jdbc.update("update coin_accounts set balance=?,version=version+1 where user_id=?",next,user);
                store.jdbc.update("update activity_instances set reward_status='REVERSED' where instance_id=? and user_id=?",instance.id(),user);
                balance=next;
            }
        });
    }

    /** 真实退款申请进入券行锁前取得同用户活动互斥，防止申请与领奖穿透；必须处于原退款事务。 */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockBeforeRefundRequest(long user) {requireUser(user);store.lockAccount(user);}

    /** 构造实例响应：业务状态由规则与持久奖励覆盖，按钮仅来自服务端允许动作。 */
    private Map<String,Object> view(ActivityStore.Instance instance,LocalDateTime now,boolean lock) {
        return project(instance,now,store.facts(instance.owner(),instance.accepted(),instance.end(),lock));
    }
    /** 批量订单集合可被多个任务复用，规则计算仍按各自领取时间和窗口过滤。 */
    private Map<String,Object> project(ActivityStore.Instance instance,LocalDateTime now,List<ActivityRules.OrderFact> facts) {
        var progress=ActivityRules.evaluate(instance.owner(),instance.rule(),instance.accepted(),instance.end(),instance.deadline(),now,facts);
        String state=instance.rewardStatus().equals("NONE")?progress.status():instance.rewardStatus();
        var result=new LinkedHashMap<String,Object>();
        result.put("instanceId",instance.id());result.put("definitionId",instance.definition());result.put("ruleVersion",instance.version());
        result.put("title",instance.title());result.put("type",instance.rule().type().name());result.put("targetCount",instance.rule().target());
        result.put("dayCount",instance.rule().days());result.put("rewardCoins",instance.rule().reward());result.put("status",state);
        result.put("completed",progress.completed());result.put("required",progress.required());result.put("days",progress.days());
        result.put("acceptedAt",BusinessTime.utc(instance.accepted()));result.put("windowEnd",BusinessTime.utc(instance.end()));
        result.put("claimDeadline",BusinessTime.utc(instance.deadline()));result.put("serverTime",BusinessTime.utc(now));
        result.put("allowedActions",state.equals("READY")?List.of("CLAIM","DETAIL"):state.equals("IN_PROGRESS")?List.of("COMPLETE","DETAIL"):List.of("DETAIL"));
        return result;
    }
    /** 拒绝缺失身份；HTTP 身份来自认证主体，调用者不能传请求体中的用户字段。 */
    private static void requireUser(long user){if(user<=0)throw new ActivityException(401,"LOGIN_REQUIRED");}
    /** 接受完整规范 UUID，不允许截短 UUID、任意路径或额外业务载荷。 */
    private static String uuid(String value) {
        try {if(value==null||value.length()!=36)throw new IllegalArgumentException();return UUID.fromString(value).toString();}
        catch(IllegalArgumentException error){throw new ActivityException(400,"INVALID_REQUEST");}
    }
}
