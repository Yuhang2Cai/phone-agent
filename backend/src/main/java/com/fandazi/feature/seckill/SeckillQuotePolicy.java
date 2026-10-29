// 秒杀报价规则：集中识别专属套餐、权威时间窗口和价格快照，不在准备阶段预占库存。
package com.fandazi.feature.seckill;

import com.fandazi.feature.agent.ToolBoundaryException;
import com.fandazi.feature.catalog.DealPackageEntity;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** 供既有三种报价入口共用，普通套餐返回空活动并保留历史有效期规则。 */
@Service
public class SeckillQuotePolicy {
    private final SeckillActivityRepository activities;
    private final SeckillTimeSource time;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

    /** 注入活动映射与数据库时钟；只读资格，所有写入仍由订单事务重新核验。 */
    public SeckillQuotePolicy(SeckillActivityRepository activities,SeckillTimeSource time) {
        this.activities=activities;this.time=time;
    }

    /** 返回套餐对应活动或null；只查询映射，不因库存0丢失秒杀限制。 */
    public SeckillActivityEntity activityFor(DealPackageEntity deal) {
        return activities.findByDealId(deal.getId()).orElse(null);
    }

    /** 返回数据库UTC瞬时，供报价与订单锁内检查；不读取手机或应用主机时钟。 */
    public Instant now(){return time.now();}

    /** 从唯一个人计数行读取剩余额度，查询不创建计数行或占库存，最终下单仍在锁内重查。 */
    public int remainingQuota(SeckillActivityEntity activity,Long user){
        var rows=em.createQuery("select q from SeckillQuotaEntity q where q.activity.id=:activity and q.user.id=:user",SeckillQuotaEntity.class)
                .setParameter("activity",activity.getId()).setParameter("user",user).getResultList();
        return rows.isEmpty()?activity.getPerUserLimit():rows.get(0).remaining();
    }

    /**
     * 将套餐、活动版本、数量和金额绑定为幂等指纹；普通团购保持原字符串以兼容历史订单。
     * 秒杀使用固定长度SHA-256，避免两种最长公开ID拼接后超过现有128字符列。
     */
    public static String fingerprint(DealPackageEntity deal,SeckillActivityEntity activity,int quantity,int total){
        String base=deal.getPublicId()+":"+deal.getVersion()+":"+quantity+":"+total;
        if(activity==null)return base;
        String snapshot="seckill:"+base+":"+activity.getPublicId()+":"+activity.getActivityVersion();
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("运行环境缺少SHA-256",error);}
    }

    /**
     * 校验当前活动时间和单次可购买数量，不修改任何业务事实。
     * @throws ToolBoundaryException 非开放窗口、已下架、库存不足或数量超过活动上限
     */
    public void requireOpen(SeckillActivityEntity activity,Instant now,int quantity) {
        // 时间与停止状态优先，售罄活动也必须正确显示尚未开始或已结束。
        switch(activity.stateAt(now)) {
            case "UPCOMING" -> throw new SeckillRejectedException("NOT_STARTED","活动尚未开始");
            case "ENDED" -> throw new SeckillRejectedException("ENDED","活动已结束");
            case "STOPPED" -> throw new SeckillRejectedException("STOPPED","活动已终止");
            default -> { /* LIVE继续检查可分配库存；未知持久状态已在实体投影中按停止处理。 */ }
        }
        var deal=activity.getDeal();
        if(!deal.isActive()) throw new SeckillRejectedException("STOPPED","活动套餐已下架");
        if(quantity>deal.getInventory()) throw new SeckillRejectedException("SOLD_OUT","剩余库存不足");
        if(quantity>activity.getPerUserLimit()) throw new SeckillRejectedException("LIMIT_REACHED","超过活动限购数量");
    }
}
