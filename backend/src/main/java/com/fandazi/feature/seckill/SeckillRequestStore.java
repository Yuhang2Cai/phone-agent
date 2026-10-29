// 秒杀幂等存储：使用唯一键原子占位与锁内当前读，避免MySQL旧快照漏掉已经成功的订单。
package com.fandazi.feature.seckill;
import jakarta.persistence.*;
import com.fandazi.feature.agent.ToolBoundaryException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 调用方先持有用户/报价锁；回执、订单及库存账全部属于同一个购买事务。 */
@Service @Transactional(propagation=Propagation.MANDATORY)
public class SeckillRequestStore {
    @PersistenceContext private EntityManager em;
    /**
     * 返回锁定的原请求回执；相同内容重放可读原订单，不同内容拒绝且不会改写原摘要。
     * 不能先对不存在订单做范围FOR UPDATE：不同新用户可能锁住同一空隙而导致插入死锁。
     */
    public SeckillPurchaseRequest claim(Long user,String key,String fingerprint){
        // 唯一键插入或无内容改写的更新是当前写；同键等待后看到已提交行，不依赖事务旧快照。
        em.createNativeQuery("INSERT INTO seckill_purchase_requests(user_id,request_key,request_fingerprint,created_at) VALUES(:user,:key,:fingerprint,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE user_id=user_id")
                .setParameter("user",user).setParameter("key",key).setParameter("fingerprint",fingerprint).executeUpdate();
        var receipt=em.createQuery("select r from SeckillPurchaseRequest r where r.user.id=:user and r.requestKey=:key",SeckillPurchaseRequest.class)
                .setParameter("user",user).setParameter("key",key).setLockMode(LockModeType.PESSIMISTIC_WRITE).getSingleResult();
        em.refresh(receipt,LockModeType.PESSIMISTIC_WRITE);
        if(!receipt.fingerprint().equals(fingerprint))throw new SeckillRejectedException("REQUEST_CONFLICT","同一请求标识对应不同购买内容");
        return receipt;
    }
}
