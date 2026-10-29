// 秒杀账本服务：在既有订单事务中维护库存预留与个人额度，不建立第二份剩余库存。
package com.fandazi.feature.seckill;
import com.fandazi.feature.agent.ToolBoundaryException;
import com.fandazi.feature.catalog.DealPackageEntity;
import com.fandazi.feature.grouporder.GroupBuyOrderEntity;
import jakarta.persistence.*;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 所有方法依赖调用方事务；创建按用户→报价→套餐，结算按订单→套餐，均不反锁用户。 */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class SeckillLedger {
    @PersistenceContext private EntityManager em;
    private final SeckillTimeSource time;
    /** 注入数据库时钟，支付资格必须在取得全部相关写锁后重新读取。 */
    public SeckillLedger(SeckillTimeSource time){this.time=time;}

    /** 调用方已锁套餐和活动；计数行不存在时在同一套餐锁保护下创建，不用订单聚合扫描。 */
    public void reserve(GroupBuyOrderEntity order,SeckillActivityEntity activity,Instant now){
        var rows=em.createQuery("select q from SeckillQuotaEntity q where q.activity.id=:activity and q.user.id=:user",SeckillQuotaEntity.class)
                .setParameter("activity",activity.getId()).setParameter("user",order.getUser().getId())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        SeckillQuotaEntity quota;
        if(rows.isEmpty()){quota=new SeckillQuotaEntity(activity,order.getUser());em.persist(quota);}
        else {quota=rows.get(0);em.refresh(quota,LockModeType.PESSIMISTIC_WRITE);}
        // 规则判断：先累计验证；任何异常都由外层回滚已创建订单、确认消费和套餐扣减。
        quota.reserve(order.getQuantity());
        order.bindSeckill(activity,now);
        em.persist(new SeckillReservationEntity(order,quota,now));
    }

    /**
     * 已持有订单写锁时结算秒杀支付，返回true表示已处理；普通团购返回false。
     * 到期拒绝并由统一后台关闭路径补偿，不在抛异常的事务中谎称已释放。
     */
    public boolean pay(GroupBuyOrderEntity order){
        if(order.getSeckillActivity()==null)return false;
        var reservation=lockSettlement(order);
        Instant now=time.now();
        if(!now.isBefore(order.getPaymentExpiresAt()))throw new SeckillRejectedException("PAYMENT_EXPIRED","支付期限已到，订单将自动关闭");
        if(!reservation.reserved())throw new SeckillRejectedException("ORDER_CLOSED","订单预留已关闭");
        // 原子转换预留类别，库存不再扣减；实付时间来自同一次锁内数据库时刻。
        reservation.quota().commit(reservation.quantity());reservation.settle(true,now);order.markPaidAt(now);
        return true;
    }

    /** 已持订单锁时关闭到期秒杀预留；已支付或已关闭保持原事实，返回false且不改库存。 */
    public boolean expire(GroupBuyOrderEntity order){
        if(order.getSeckillActivity()==null || order.getStatus()!=com.fandazi.feature.grouporder.GroupBuyOrderStatus.PENDING_PAYMENT)return false;
        var reservation=lockSettlement(order);
        Instant now=time.now();
        if(now.isBefore(order.getPaymentExpiresAt()))return false;
        if(!reservation.reserved())throw new IllegalStateException("待支付秒杀订单预留终态不一致");
        var deal=order.getDeal();
        // 先检查守恒上界再释放，损坏账本不能靠重复回加库存被掩盖。
        if(deal.getInventory()+reservation.quantity()>order.getSeckillActivity().getTotalStock())
            throw new IllegalStateException("秒杀释放将超过初始总库存");
        reservation.quota().release(reservation.quantity());reservation.settle(false,now);
        em.createQuery("update DealPackageEntity d set d.inventory=d.inventory+:quantity where d.id=:id")
                .setParameter("quantity",reservation.quantity()).setParameter("id",deal.getId()).executeUpdate();
        em.refresh(deal,LockModeType.PESSIMISTIC_WRITE);
        // 预留、额度、库存和关闭事实只在同一事务提交；进程中断不能留下部分成功。
        order.closeSeckillAt(now);
        return true;
    }

    /** 统一取得套餐→预留→额度锁；不取得用户/报价/确认锁，避免与新购买形成反向等待。 */
    private SeckillReservationEntity lockSettlement(GroupBuyOrderEntity order){
        var deal=em.find(DealPackageEntity.class,order.getDeal().getId(),LockModeType.PESSIMISTIC_WRITE);
        em.refresh(deal,LockModeType.PESSIMISTIC_WRITE);
        var reservation=em.find(SeckillReservationEntity.class,order.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(reservation==null)throw new IllegalStateException("秒杀订单缺少唯一预留记录");
        em.refresh(reservation,LockModeType.PESSIMISTIC_WRITE);
        // 金额/状态等既有读取可能已建立一致性快照；结算计数始终使用锁内当前读。
        em.refresh(reservation.quota(),LockModeType.PESSIMISTIC_WRITE);
        return reservation;
    }
}
