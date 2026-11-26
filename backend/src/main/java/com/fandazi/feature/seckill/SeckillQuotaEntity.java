// 秒杀个人额度：按活动与用户唯一保存未支付占用和已购买数量，热路径不扫描历史订单。
package com.fandazi.feature.seckill;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.agent.ToolBoundaryException;
import jakarta.persistence.*;

/** 调用者必须先锁专属套餐，再锁本额度；所有计数变化与订单和预留同事务提交。 */
@Entity
@Table(name="seckill_quotas",uniqueConstraints=@UniqueConstraint(columnNames={"activity_id","user_id"}))
public class SeckillQuotaEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="activity_id") private SeckillActivityEntity activity;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="user_id") private UserEntity user;
    @Column(name="reserved_count",nullable=false) private int reservedCount;
    @Column(name="committed_count",nullable=false) private int committedCount;
    /** 仅供JPA恢复；新额度由领域服务在套餐锁保护下创建。 */
    protected SeckillQuotaEntity(){}
    /** 创建零占用计数，不代表已经抢到库存。 */
    SeckillQuotaEntity(SeckillActivityEntity activity,UserEntity user){this.activity=activity;this.user=user;}
    /** 返回内部计数行身份，仅供同事务持久化关联。 */
    public Long getId(){return id;}
    /** 返回该用户本活动剩余额度；退款后的committed不被扣回。 */
    public int remaining(){return Math.max(0,activity.getPerUserLimit()-reservedCount-committedCount);}
    /** 锁内预占数量；超限抛稳定业务错误，外层事务回滚订单和库存。 */
    void reserve(int quantity){
        if(quantity<1 || quantity>remaining()) throw new SeckillRejectedException("LIMIT_REACHED","已达到活动累计限购数量");
        reservedCount+=quantity;
    }
    /** 已付款只转换占用类别，不恢复可购资格；预留不足属于账本异常，拒绝提交。 */
    void commit(int quantity){requireReserved(quantity);reservedCount-=quantity;committedCount+=quantity;}
    /** 超时只释放未付款占用，不修改已购买计数。 */
    void release(int quantity){requireReserved(quantity);reservedCount-=quantity;}
    /** 账本异常不能用负数或归零掩盖，必须整体回滚并保留可定位错误。 */
    private void requireReserved(int quantity){if(quantity<1 || reservedCount<quantity)throw new IllegalStateException("秒杀个人额度与预留账不一致");}
}
