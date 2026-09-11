package com.fandazi.feature.automation;
import java.util.Optional; import org.springframework.data.jpa.repository.JpaRepository;
public interface AutomationGrantRepository extends JpaRepository<AutomationGrant,Long>{Optional<AutomationGrant> findByUserIdAndSessionId(Long userId,String sessionId);}
