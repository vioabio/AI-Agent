package com.vio.vioaiagent.pokemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.config.ActivityNoticeFixtureInitializer;
import com.vio.vioaiagent.pokemon.repository.InMemoryActivityNoticeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActivityNoticeFixtureInitializerTest {
    @Test
    void loadsRedactedFixtureWithoutActivatingRepositoryByItself() {
        InMemoryActivityNoticeRepository repository = new InMemoryActivityNoticeRepository();
        ActivityNoticeFixtureInitializer initializer = new ActivityNoticeFixtureInitializer(repository,
                new ObjectMapper(), new DefaultResourceLoader(),
                "file:data/v1.1-fixture/activity-notices.json");

        initializer.load();

        assertEquals(1, repository.find(new com.vio.vioaiagent.pokemon.repository.ActivityNoticeRepository.ActivityNoticeQuery(
                "pokemon-scarlet-violet", null, "zh-CN", "CN",
                java.time.Instant.parse("2026-09-03T00:00:00Z"), 50)).size());
        assertEquals("PUBLISHED", repository.findById("sv-raid-charizard-2026-09-v1").orElseThrow().status());
    }
}
