package com.fandazi.feature.automation;
import java.util.Optional; import org.springframework.data.jpa.repository.JpaRepository;
public interface AutomationPolicyRepository extends JpaRepository<AutomationPolicy,Long>{Optional<AutomationPolicy> findByUserId(Long userId);}
