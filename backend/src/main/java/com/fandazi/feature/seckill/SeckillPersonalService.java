// 秒杀本人查询：资格与原请求核查只读权威持久状态，不因重连或查询自动发起购买。
package com.fandazi.feature.seckill;
import com.fandazi.feature.grouporder.GroupBuyOrderService;
import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 本人身份只来自认证主体；公开活动、私人额度与订单始终分开授权。 */
@Service @Transactional(readOnly=true)
public class SeckillPersonalService {
    @PersistenceContext private EntityManager em;
    private final SeckillActivityRepository activities;
    private final SeckillQuotePolicy policy;
    private final GroupBuyOrderService orders;
    /** 注入活动事实、统一时间/额度规则和本人订单读取，不依赖客户端缓存。 */
    public SeckillPersonalService(SeckillActivityRepository activities,SeckillQuotePolicy policy,GroupBuyOrderService orders){
        this.activities=activities;this.policy=policy;this.orders=orders;
    }
    /** 返回一次读事务中的状态、时间锚点与本人额度，库存和额度仍须在最终下单事务复核。 */
    public Map<String,Object> eligibility(Long user,String activityId){
        var activity=activities.findByPublicId(activityId).orElseThrow(()->new SeckillRejectedException("NOT_FOUND","活动不存在"));
        var now=policy.now();int remaining=policy.remainingQuota(activity,user);
        String reason="AVAILABLE";
        try{policy.requireOpen(activity,now,1);if(remaining<1)reason="LIMIT_REACHED";}
        catch(SeckillRejectedException rejected){reason=rejected.getCode();}
        // 时间/停止→售罄→本人限购优先级由统一规则保证，状态未知不能从旧缓存放行。
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("activityId",activity.getPublicId());result.put("activityVersion",activity.getActivityVersion());
        result.put("serverTime",now.toString());result.put("startsAt",activity.getStartsAt().toString());result.put("endsAt",activity.getEndsAt().toString());
        result.put("state",activity.stateAt(now));result.put("availableStock",activity.getDeal().getInventory());
        result.put("remainingQuota",remaining);result.put("canPurchase","AVAILABLE".equals(reason));result.put("reasonCode",reason);
        return result;
    }
    /**
     * 按本人原幂等键恢复成功订单；不存在回执也不能证明另一个尚未提交的请求未执行。
     * 因此缺失返回RESULT_UNKNOWN，只供客户端继续原键核查，不授权重新创建交易。
     */
    public Map<String,Object> originalRequest(Long user,String key){
        var rows=em.createQuery("select r from SeckillPurchaseRequest r where r.user.id=:user and r.requestKey=:key",SeckillPurchaseRequest.class)
                .setParameter("user",user).setParameter("key",key).getResultList();
        if(rows.isEmpty()||rows.get(0).order()==null)return Map.of("state","RESULT_UNKNOWN","reasonCode","RESULT_UNKNOWN");
        return Map.of("state","FOUND","order",orders.detail(user,rows.get(0).order().getPublicId()));
    }
}
