// Agent 安全模块：封装确认凭证的公开标识查询。
package com.fandazi.feature.agent;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 一次性确认凭证仓储。 */
public interface ConfirmationGrantRepository extends JpaRepository<ConfirmationGrantEntity, Long> {
    Optional<ConfirmationGrantEntity> findByGrantId(String grantId);
}
