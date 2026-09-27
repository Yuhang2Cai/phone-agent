// 自动下单授权模块：封装持久化查询与写入。
package com.fandazi.feature.automation;
import java.util.Optional; import org.springframework.data.jpa.repository.JpaRepository;
public interface AutomationPolicyRepository extends JpaRepository<AutomationPolicy,Long>{Optional<AutomationPolicy> findByUserId(Long userId);}
