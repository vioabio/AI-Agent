package com.vio.vioaiagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestChatModelConfig.class)
class VioAiAgentApplicationTests {

    @Test
    void contextLoads() {
    }

}
