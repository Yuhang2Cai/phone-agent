// AG04功能门禁测试：冻结新任务版本选择条件及默认关闭配置，避免混合部署静默升级。
package com.fandazi.feature.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 新协议可被模型对象表达，但只有部署门禁和客户端能力齐全时才能用于新任务。 */
class AgentGoalFeatureGateTest {
    @Test void runRequestAcceptsExplicitV23AndKeepsV22() {
        // 版本由可信任务网关固定，用户正文和普通context都不能自行切换。
        assertDoesNotThrow(()->new AgentRunRequest("u","s","继续","recommend","trace","run",Map.of(),"2.3"));
        assertDoesNotThrow(()->new AgentRunRequest("u","s","继续","recommend","trace","run",Map.of(),"2.2"));
    }

    @Test void goalRecoveryIsDisabledByDefaultInDeploymentConfiguration() throws Exception {
        /** 直接读取主源码配置；测试资源含隔离数据库配置，不能用类路径覆盖部署文件。 */
        Path deploymentConfig=Path.of("src","main","resources","application.yml");
        assertTrue(Files.isRegularFile(deploymentConfig),"缺少实际部署配置");
        String yaml=Files.readString(deploymentConfig,StandardCharsets.UTF_8);
        assertTrue(yaml.contains("goal-recovery:"),"缺少AG04独立功能门禁");
        assertTrue(yaml.contains("enabled: ${AGENT_GOAL_RECOVERY_ENABLED:false}"),"AG04必须默认关闭");
    }

    @Test void newTaskNeedsAllThreeCapabilitiesAndExistingTaskKeepsItsVersion() {
        /** 任一部署端或客户端缺能力都保持2.2；已有2.3关闭后也不能降级执行。 */
        var enabled=new AgentGoalFeatureGate(true,true);
        assertEquals("2.3",enabled.protocolForNewTask(true,Set.of(AgentGoalFeatureGate.CLIENT_CAPABILITY)));
        assertEquals("2.2",enabled.protocolForNewTask(false,Set.of(AgentGoalFeatureGate.CLIENT_CAPABILITY)));
        assertEquals("2.2",enabled.protocolForNewTask(true,Set.of()));
        assertEquals("2.2",new AgentGoalFeatureGate(true,false)
                .protocolForNewTask(true,Set.of(AgentGoalFeatureGate.CLIENT_CAPABILITY)));
        assertEquals("2.3",new AgentGoalFeatureGate(true,false).retainPersistedProtocol("2.3"));
        assertFalse(new AgentGoalFeatureGate(true,false).allowsGoalMutation("2.3",true));
        assertThrows(IllegalArgumentException.class,()->enabled.retainPersistedProtocol("latest"));
    }
}
