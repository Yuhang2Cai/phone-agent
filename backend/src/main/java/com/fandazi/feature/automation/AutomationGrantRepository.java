package com.fandazi.feature.automation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface AutomationGrantRepository extends JpaRepository<AutomationGrant,Long>{
    Optional<AutomationGrant> findByUserIdAndSessionId(Long userId,String sessionId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from AutomationGrant g where g.user.id = :userId and g.sessionId = :sessionId")
    Optional<AutomationGrant> findByUserIdAndSessionIdForUpdate(@Param("userId") Long userId,@Param("sessionId") String sessionId);
}
