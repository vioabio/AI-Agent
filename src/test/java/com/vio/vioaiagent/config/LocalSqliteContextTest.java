package com.vio.vioaiagent.config;

import com.vio.vioaiagent.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.profiles.active=test,deepseek",
        "vio.model.deepseek.api-key=offline-wiring-only",
        "vio.local-history.enabled=true"
})
class LocalSqliteContextTest {
    @TempDir static Path directory;
    @DynamicPropertySource
    static void historyPath(DynamicPropertyRegistry properties) {
        properties.add("vio.local-history.path", () -> directory.resolve("history.sqlite").toString());
    }
    @Autowired ApplicationContext context;

    @Test
    void fullApplicationSelectsSqliteForHistoryWithoutRequiringDatabaseServer() {
        assertEquals(1, context.getBeansOfType(ConversationRepository.class).size());
        assertEquals(1, context.getBeansOfType(ChatMessageRepository.class).size());
        assertInstanceOf(SqliteLocalHistoryRepository.class, context.getBean(ConversationRepository.class));
        assertSame(context.getBean(ConversationRepository.class), context.getBean(ChatMessageRepository.class));
        assertInstanceOf(InMemoryRunRepository.class, context.getBean(RunRepository.class));
        assertFalse(context.containsBean("dataSource"));
    }
}
