// Agent 安全模块：封装确认凭证的公开标识查询。
package com.fandazi.feature.agent;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 一次性确认凭证仓储。 */
public interface ConfirmationGrantRepository extends JpaRepository<ConfirmationGrantEntity, Long> {
    Optional<ConfirmationGrantEntity> findByGrantId(String grantId);
    /** 消费前锁住凭证行，跨会话或业务入口并发时只能有一次成功消费。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select g from ConfirmationGrantEntity g where g.grantId = :grantId")
    Optional<ConfirmationGrantEntity> findForUpdate(@org.springframework.data.repository.query.Param("grantId") String grantId);
    /** 确认摘要只能引用本会话已准备的动作；按签发顺序匹配真实金额和规则。 */
    java.util.List<ConfirmationGrantEntity> findByUserIdAndSessionIdAndActionAndResourceIdOrderByIdDesc(
            Long userId, String sessionId, String action, String resourceId);
}
