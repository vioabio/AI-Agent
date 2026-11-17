package com.vio.vioaiagent.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrivateConfigurationPackagingTest {
    @Test void privateLocalConfigurationIsOutsideTheApplicationClasspath() {
        for (String name : new String[]{"application-local.yml", "application-local.yaml", "application-local.properties", ".env"}) {
            assertNull(getClass().getResource("/" + name), "Private configuration must not be a packaged resource");
        }
        assertNotNull(getClass().getResource("/application-local.example.yml"));
    }

    @Test void localOverridesHaveAnExplicitLocalOnlyExternalImport() throws Exception {
        try (var input = getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(input);
            String config = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(config.contains("on-profile: \"local & !prod & !demo\""));
            assertTrue(config.contains("optional:file:./src/main/resources/application-local.yml"));
        }
    }
}
