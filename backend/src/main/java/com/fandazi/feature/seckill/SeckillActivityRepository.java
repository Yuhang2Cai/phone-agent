// 秒杀活动仓储：按公开身份和门店读取活动，关联套餐仍是库存唯一来源。
package com.fandazi.feature.seckill;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
/** 只暴露本期消费者需要的查询，不提供商家管理Controller。 */
public interface SeckillActivityRepository extends JpaRepository<SeckillActivityEntity,Long> {
    /** 读取活动及套餐/门店，避免在事务外恢复懒关联。 */
    @EntityGraph(attributePaths={"deal","deal.store"})
    Optional<SeckillActivityEntity> findByPublicId(String publicId);
    /** 用套餐主键识别秒杀限制，既有报价和订单入口也必须走此查询。 */
    Optional<SeckillActivityEntity> findByDealId(Long dealId);
    /** 查询指定门店活动；过滤状态时不按可用库存删除售罄预告。 */
    @EntityGraph(attributePaths={"deal","deal.store"})
    List<SeckillActivityEntity> findByDealStorePublicIdOrderByStartsAtAsc(String storePublicId);
}
