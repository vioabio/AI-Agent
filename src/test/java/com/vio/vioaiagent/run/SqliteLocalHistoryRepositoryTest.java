package com.vio.vioaiagent.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SqliteLocalHistoryRepositoryTest {
    @TempDir Path directory;
    private SqliteLocalHistoryRepository open() {
        return new SqliteLocalHistoryRepository(directory.resolve("history.sqlite").toString(), new ObjectMapper());
    }

    @Test
    void enabledLocalStoreReplacesBothMemoryRepositories() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withInitializer(c -> c.getEnvironment().setActiveProfiles("local"))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("vio.local-history.enabled=true",
                        "vio.local-history.path=" + directory.resolve("wiring.sqlite"))
                .withUserConfiguration(SqliteLocalHistoryRepository.class,
                        InMemoryConversationRepository.class, InMemoryChatMessageRepository.class)
                .run(c -> {
                    org.assertj.core.api.Assertions.assertThat(c).hasSingleBean(ConversationRepository.class)
                            .hasSingleBean(ChatMessageRepository.class)
                            .doesNotHaveBean(InMemoryConversationRepository.class)
                            .doesNotHaveBean(InMemoryChatMessageRepository.class);
                    assertSame(c.getBean(ConversationRepository.class), c.getBean(ChatMessageRepository.class));
                });
    }

    @Test
    void historySurvivesRepositoryRestartWithOwnerIsolationAndCascadeDeletion() {
        var first = open();
        Instant now = Instant.now();
        first.save(new Conversation("c", "owner", "杰尼龟", now, now));
        var message = new ChatMessage("m", "c", "old-run", "owner", "assistant", "水属性", now, Map.of("source", "local"));
        first.save(message);
        first.save(message);
        assertThrows(IllegalStateException.class, () -> first.save(new ChatMessage("m", "c", "old-run",
                "owner", "assistant", "冲突内容", now, Map.of("source", "local"))));
        var restarted = open();
        assertEquals("杰尼龟", restarted.findByIdForUser("c", "owner").orElseThrow().title());
        assertEquals(1, restarted.findByConversationForUser("c", "owner").size());
        assertEquals("水属性", restarted.findByConversationForUser("c", "owner").getFirst().content());
        assertTrue(restarted.findByConversationForUser("c", "other").isEmpty());
        assertThrows(IllegalStateException.class, () -> restarted.save(new Conversation("c", "other", "覆盖", now, now)));
        restarted.deleteForUser("c", "other");
        assertTrue(restarted.findById("c").isPresent());
        assertTrue(restarted.updateTitleForUser("c", "owner", "新标题"));
        restarted.deleteForUser("c", "owner");
        assertTrue(open().findById("c").isEmpty());
        assertTrue(open().findByConversationForUser("c", "owner").isEmpty());
        var recreated = open();
        recreated.save(new Conversation("c", "owner", "重新创建", now, now));
        assertTrue(recreated.findByConversationForUser("c", "owner").isEmpty(),
                "deletion must physically cascade, not just hide rows behind an absent parent");
    }

    @Test
    void rejectsMessagesWithWrongConversationOwner() {
        var store = open();
        Instant now = Instant.now();
        store.save(new Conversation("c", "owner", "title", now, now));
        assertThrows(IllegalStateException.class, () -> store.save(new ChatMessage("bad", "c", null,
                "other", "user", "foreign", now, Map.of())));
        assertTrue(store.findForUser("other", 20).isEmpty());
    }
}
