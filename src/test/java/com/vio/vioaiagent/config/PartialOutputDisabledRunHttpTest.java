package com.vio.vioaiagent.config;

import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties={"vio.agent.skills.enabled=false","vio.agent.context-assembly.enabled=false",
        "vio.agent.answer-blocks.enabled=false","vio.observability.tracing.enabled=false"})
class PartialOutputDisabledRunHttpTest extends AbstractPartialOutputTerminationHttpTest {
    static final Path DATA=createDirectory("disabled");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){paths(registry,DATA);}
    @Override Path dataDirectory(){return DATA;}
    @Override boolean featuresEnabled(){return false;}
}
