package com.vio.vioaiagent.config;

import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties={"vio.agent.skills.enabled=true","vio.agent.context-assembly.enabled=true",
        "vio.agent.answer-blocks.enabled=true","vio.observability.tracing.enabled=true"})
class PartialOutputEnabledRunHttpTest extends AbstractPartialOutputTerminationHttpTest {
    static final Path DATA=createDirectory("enabled");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){paths(registry,DATA);}
    @Override Path dataDirectory(){return DATA;}
    @Override boolean featuresEnabled(){return true;}
}
