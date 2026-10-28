// 活动终态UI前置：只在显式指定的本任务合成库/用户上执行受控终态适配，页面随后读取真实接口。
package com.fandazi.feature.activity;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** 非自动全量夹具：必须明确提供UI专用连接、合成用户和已完成模拟支付的订单。 */
@EnabledIfEnvironmentVariable(named="ACTIVITY_UI_FIXTURE_ENABLED", matches="true")
class ActivityUiStateMysqlTest {
    /** 实际调用内部退款协调器验证撤回投影，并建立明确标记的历史领奖过期展示夹具。 */
    @Test void prepareReversalAndClaimExpiredForRealUiRead() {
        String url=System.getenv("ACTIVITY_DB_URL");
        assertTrue(url.contains("/fandazi_activity_ui_20261028?"),"禁止访问其他数据库");
        var source=new DriverManagerDataSource(url,System.getenv("ACTIVITY_DB_USER"),System.getenv("ACTIVITY_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        long user=Long.parseLong(System.getenv("ACTIVITY_UI_USER_ID"));
        String order=UUID.fromString(System.getenv("ACTIVITY_UI_ORDER_ID")).toString();
        assertTrue(jdbc.queryForObject("select username from app_users where id=?",String.class,user).startsWith("activity-http-"));
        assertEquals(1,jdbc.queryForObject("select count(*) from group_buy_orders where user_id=? and public_id=? and status='PAID'",Integer.class,user,order));
        // 首次进入必须是十金币；驱动修复后只允许已验证的零余额/两条冲正账本继续，不重新发奖。
        int before=jdbc.queryForObject("select balance from coin_accounts where user_id=?",Integer.class,user);
        assertTrue(before==10 || (before==0 && jdbc.queryForObject("select count(*) from coin_transactions where user_id=?",Integer.class,user)==2));
        var service=new ActivityService(jdbc,new DataSourceTransactionManager(source),new ActivityClock());
        // 退款执行尚未接入：只在此测试回调置受控券终态，真正的重算和冲正使用生产事务入口。
        Runnable terminal=()->jdbc.update("update vouchers v join group_buy_orders o on o.id=v.order_id set v.status='REFUNDED' where o.user_id=? and o.public_id=?",user,order);
        service.coordinateRefundCompletion(user,order,terminal);
        service.coordinateRefundCompletion(user,order,terminal);
        assertEquals(0,service.account(user).get("balance"));
        assertEquals(2,jdbc.queryForObject("select count(*) from coin_transactions where user_id=?",Integer.class,user));
        assertEquals(1,jdbc.queryForObject("select count(*) from coin_transactions where user_id=? and kind='REVERSAL'",Integer.class,user));
        // 独立历史合成订单只用于时间状态UI；不修改真实主流程订单的支付时间，也不伪装为API支付。
        var past=LocalDateTime.now(com.fandazi.core.time.BusinessTime.ZONE).toLocalDate().minusDays(3).atTime(12,0);
        var histories=jdbc.queryForList("select public_id from group_buy_orders where user_id=? and request_fingerprint like 'activity-ui-history-%'",String.class,user);
        assertTrue(histories.size()<=1,"历史夹具不能重复创建");
        String historyOrder=histories.isEmpty()?UUID.randomUUID().toString():histories.get(0);
        if(histories.isEmpty())jdbc.update("insert into group_buy_orders(public_id,user_id,store_id,deal_id,idempotency_key,quantity,total_amount,paid_total_amount,paid_at,status,created_at,expires_at,request_fingerprint) select ?,user_id,store_id,deal_id,?,1,112,112,?,'PAID',?,?,? from group_buy_orders where user_id=? and public_id=?",historyOrder,UUID.randomUUID().toString(),past,past.minusMinutes(1),past.plusDays(1),"activity-ui-history-"+UUID.randomUUID(),user,order);
        // 原生订单契约要求每份套餐有一张券；历史前置也必须保留完整数量与金额守恒。
        int voucherCount=jdbc.queryForObject("select count(*) from vouchers v join group_buy_orders o on o.id=v.order_id where o.user_id=? and o.public_id=?",Integer.class,user,historyOrder);
        assertTrue(voucherCount<=1);
        if(voucherCount==0)jdbc.update("insert into vouchers(public_id,order_id,user_id,deal_id,status,qr_token,expires_at,created_at,allocated_amount) select ?,id,user_id,deal_id,'UNUSED',?,?,?,112 from group_buy_orders where user_id=? and public_id=?",UUID.randomUUID().toString(),UUID.randomUUID().toString(),past.plusDays(10),past,user,historyOrder);
        String definition=jdbc.queryForObject("select definition_id from activity_definitions where task_type='DAILY_ORDER_COUNT' and target_count=1 and rule_version=1",String.class);
        var prior=jdbc.queryForList("select instance_id from activity_instances where user_id=? and definition_id=? and cycle_key=?",String.class,user,definition,past.toLocalDate().toString());
        String instance=prior.isEmpty()?UUID.randomUUID().toString():prior.get(0);
        var end=past.toLocalDate().plusDays(1).atStartOfDay();
        if(prior.isEmpty())jdbc.update("insert into activity_instances(instance_id,user_id,definition_id,rule_version,cycle_key,title,task_type,target_count,day_count,reward_coins,accepted_at,window_end,claim_deadline,reward_status) values(?,?,?,1,?,'历史每日任务（合成验收）','DAILY_ORDER_COUNT',1,1,10,?,?,?,'NONE')",instance,user,definition,past.toLocalDate().toString(),past.minusMinutes(2),end,end.plusDays(1));
        assertEquals("CLAIM_EXPIRED",service.detail(user,instance).get("status"));
        assertEquals(0,service.account(user).get("balance"));
    }
}
