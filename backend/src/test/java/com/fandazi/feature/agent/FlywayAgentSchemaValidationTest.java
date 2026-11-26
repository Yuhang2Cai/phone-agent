// 智能助手模块：验证关键行为与边界条件。
package com.fandazi.feature.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent-flyway-schema;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "security.jwt.secret=0123456789012345678901234567890123456789012345678901234567890123",
        "agent.internal-token=test-agent-token"
})
class FlywayAgentSchemaValidationTest {
    @Test
    void productionFlywaySchemaSatisfiesHibernateValidation() {
        // 启动上下文即验证迁移先建表，随后再由持久化层校验。
    }
}
