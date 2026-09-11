package com.vio.vioaiagent.pokemon;

import com.vio.vioaiagent.pokemon.domain.ActivityNotice;
import com.vio.vioaiagent.pokemon.repository.ActivityNoticeRepository;
import com.vio.vioaiagent.pokemon.repository.InMemoryActivityNoticeRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActivityNoticeRepositoryTest {
    @Test
    void queryUsesHalfOpenEffectiveWindowAndCanonicalFilters() {
        Instant at = Instant.parse("2026-09-03T00:00:00Z");
        ActivityNotice inWindow = notice("in", "pokemon-scarlet-violet", "sv-dlc",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-03T00:00:01Z"));
        ActivityNotice atEnd = notice("end", "pokemon-scarlet-violet", "sv-dlc",
                Instant.parse("2026-09-01T00:00:00Z"), at);
        ActivityNotice other = notice("other", "other-game", "sv-dlc",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"));
        InMemoryActivityNoticeRepository repository = new InMemoryActivityNoticeRepository(
                List.of(inWindow, atEnd, other));

        List<ActivityNotice> values = repository.find(new ActivityNoticeRepository.ActivityNoticeQuery(
                "pokemon-scarlet-violet", "sv-dlc", "zh-CN", "CN", at, 50));

        assertEquals(List.of("in"), values.stream().map(ActivityNotice::id).toList());
    }

    @Test
    void sameVersionCannotBeSilentlyOverwritten() {
        ActivityNotice original = notice("immutable", "pokemon-scarlet-violet", "sv-dlc",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"));
        InMemoryActivityNoticeRepository repository = new InMemoryActivityNoticeRepository(List.of(original));
        ActivityNotice changed = new ActivityNotice(original.id(), original.noticeKey(), original.versionNo(),
                "changed", original.summary(), original.sourceId(), original.sourceUrl(), original.license(),
                original.trustLevel(), original.gameReleaseId(), original.dlcId(), original.language(),
                original.region(), original.eventType(), original.retrievedAt(), original.effectiveFrom(),
                original.effectiveTo(), original.freshness(), original.status(), original.checksum(),
                original.schemaVersion(), original.contentVersion(), original.publishedAt(),
                original.createdAt(), original.updatedAt());

        assertThrows(IllegalStateException.class, () -> repository.save(changed));
    }

    private ActivityNotice notice(String id, String game, String dlc, Instant from, Instant to) {
        return new ActivityNotice(id, "event-" + id, 1, "活动", "summary", "source",
                "https://example.com/activity", "license", "CURATED", game, dlc, "zh-CN", "CN",
                "RAID", from, from, to, "FRESH", "PUBLISHED", "checksum-" + id,
                "v1.1", "activity-v1", from);
    }
}
