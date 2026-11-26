// AG04功能门禁：集中决定新任务协议，防止客户端字段或单边部署静默启用目标恢复。
package com.fandazi.feature.agent;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 根据Spring开关、Agent能力和客户端能力选择新任务版本。
 * 已有任务始终沿用持久版本；关闭开关不会把2.3任务降级到2.2执行。
 */
@Component
final class AgentGoalFeatureGate {
    static final String CLIENT_CAPABILITY = "GOAL_RECOVERY_V1";
    private final boolean taskLifecycleEnabled;
    private final boolean goalRecoveryEnabled;

    /** 部署开关只由受控配置注入，HTTP正文和模型输出不能修改。 */
    AgentGoalFeatureGate(
            @Value("${agent.task-lifecycle.enabled:false}") boolean taskLifecycleEnabled,
            @Value("${agent.goal-recovery.enabled:false}") boolean goalRecoveryEnabled) {
        this.taskLifecycleEnabled = taskLifecycleEnabled;
        this.goalRecoveryEnabled = goalRecoveryEnabled;
    }

    /** 只有三方能力齐全才为新任务选择2.3；否则保持已验证的2.2新任务路径。 */
    String protocolForNewTask(boolean agentGoalRecoveryEnabled, Set<String> clientCapabilities) {
        boolean clientEnabled = clientCapabilities != null && clientCapabilities.contains(CLIENT_CAPABILITY);
        return taskLifecycleEnabled && goalRecoveryEnabled && agentGoalRecoveryEnabled && clientEnabled ? "2.3" : "2.2";
    }

    /** 返回已持久任务版本；拒绝未知版本，绝不因当前开关变化中途升级或降级。 */
    String retainPersistedProtocol(String persistedVersion) {
        if (!Set.of("2.2", "2.3").contains(persistedVersion))
            throw new IllegalArgumentException("持久任务协议版本不受支持");
        return persistedVersion;
    }

    /** 2.3恢复和写入要求两端开关仍开启；只读历史展示由后续投影服务独立允许。 */
    boolean allowsGoalMutation(String persistedVersion, boolean agentGoalRecoveryEnabled) {
        return "2.3".equals(persistedVersion) && taskLifecycleEnabled && goalRecoveryEnabled
                && agentGoalRecoveryEnabled;
    }
}
