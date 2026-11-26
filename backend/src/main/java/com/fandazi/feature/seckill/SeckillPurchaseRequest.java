// 秒杀请求回执：将用户幂等键、完整报价指纹和唯一订单绑定为同一事务事实。
package com.fandazi.feature.seckill;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.grouporder.GroupBuyOrderEntity;
import jakarta.persistence.*;
import java.time.Instant;

/** 回执先由原子插入或同键锁定取得；失败事务回滚时不会留下虚假成功回执。 */
@Entity @Table(name="seckill_purchase_requests",uniqueConstraints=@UniqueConstraint(columnNames={"user_id","request_key"}))
public class SeckillPurchaseRequest {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="user_id") private UserEntity user;
    @Column(name="request_key",nullable=false,length=255) private String requestKey;
    @Column(name="request_fingerprint",nullable=false,length=64) private String fingerprint;
    @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="order_id",unique=true) private GroupBuyOrderEntity order;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    /** 由JPA读取数据库原子创建的回执行，禁止应用绕过锁定流程自由创建。 */
    protected SeckillPurchaseRequest(){}
    /** 返回完整规范请求指纹，不包含凭据，也不向用户下发内部摘要。 */
    public String fingerprint(){return fingerprint;}
    /** 返回原成功订单，null只允许存在于尚未提交的建单事务内。 */
    public GroupBuyOrderEntity order(){return order;}
    /** 将本次成功订单写回同事务回执；已绑定回执不可覆盖到另一订单。 */
    public void complete(GroupBuyOrderEntity value){
        if(order!=null && !order.getId().equals(value.getId()))throw new IllegalStateException("秒杀原请求订单不可覆盖");
        order=value;
    }
}
