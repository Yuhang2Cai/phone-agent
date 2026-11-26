// 秒杀库存预留账：以订单主键唯一标识一次预留，支付提交和超时释放互斥。
package com.fandazi.feature.seckill;
import com.fandazi.feature.grouporder.GroupBuyOrderEntity;
import jakarta.persistence.*;
import java.time.Instant;

/** 状态变化由持有订单锁的统一结算服务执行；退款不改变COMMITTED记录。 */
@Entity @Table(name="seckill_reservations")
public class SeckillReservationEntity {
    @Id @Column(name="order_id") private Long orderId;
    @MapsId @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="order_id") private GroupBuyOrderEntity order;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="quota_id") private SeckillQuotaEntity quota;
    @Column(nullable=false) private int quantity;
    @Column(nullable=false,length=20) private String status;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="settled_at") private Instant settledAt;
    /** 仅供JPA恢复预留账。 */
    protected SeckillReservationEntity(){}
    /** 在订单创建事务中建立唯一未结算预留，不执行独立数据库提交。 */
    SeckillReservationEntity(GroupBuyOrderEntity order,SeckillQuotaEntity quota,Instant now){
        this.order=order;this.quota=quota;quantity=order.getQuantity();status="RESERVED";createdAt=now;
    }
    /** 返回所属用户额度，结算服务须取得其写锁。 */
    SeckillQuotaEntity quota(){return quota;}
    /** 返回预留数量，不能从当前套餐库存推导历史数量。 */
    int quantity(){return quantity;}
    /** 返回是否仍未结算；已提交/已释放均不能重复归还。 */
    boolean reserved(){return "RESERVED".equals(status);}
    /** 将当前未结算预留推进为唯一终态，重复结算属于调用错误。 */
    void settle(boolean paid,Instant now){
        if(!reserved())throw new IllegalStateException("秒杀预留已结算");
        status=paid?"COMMITTED":"RELEASED";settledAt=now;
    }
}
