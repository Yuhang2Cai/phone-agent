// 展示仓库：同来源和类型唯一，按身份读取仍由服务核验用户、会话及 epoch。
package com.fandazi.feature.agent;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 会话行锁序列化登记修订，不依赖进程内最近完成请求缓存。 */
interface AgentPresentationRepository extends JpaRepository<AgentPresentation,String> {
    /** 用可信来源与资源类别查找同一列表，重试不能额外生成冲突顺序。 */
    Optional<AgentPresentation> findBySourceIdAndResourceType(String sourceId,String resourceType);
    /** 重查后某类卡片全部消失也必须失效旧列表，不能只修订仍然存在的类别。 */
    java.util.List<AgentPresentation> findAllBySourceId(String sourceId);
}
