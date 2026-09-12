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
        // Context startup is the assertion: Flyway creates the schema before Hibernate validates it.
    }
}
