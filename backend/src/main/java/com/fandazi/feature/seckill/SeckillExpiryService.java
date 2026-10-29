// 秒杀超时结算入口：由后台扫描和受控恢复调用，按订单单独事务执行。
package com.fandazi.feature.seckill;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.*;
import com.fandazi.feature.grouporder.GroupBuyOrderEntity;
/** 内部补偿入口，不作为公共接口；按订单单独事务重查，失败不提交部分库存变更。 */
@Service
public class SeckillExpiryService {
    @PersistenceContext private EntityManager em;
    private final SeckillLedger ledger;
    /** 注入唯一结算服务，定时与恢复调用不得另外实现一套释放逻辑。 */
    public SeckillExpiryService(SeckillLedger ledger){this.ledger=ledger;}
    /** 返回本次是否实际关闭；重复调用不得重复释放库存。 */
    @Transactional
    public boolean expire(String orderId){
        var rows=em.createQuery("select o from GroupBuyOrderEntity o where o.publicId=:id",GroupBuyOrderEntity.class)
                .setParameter("id",orderId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if(rows.isEmpty())return false;
        var order=rows.get(0);em.refresh(order,LockModeType.PESSIMISTIC_WRITE);
        return ledger.expire(order);
    }
}
