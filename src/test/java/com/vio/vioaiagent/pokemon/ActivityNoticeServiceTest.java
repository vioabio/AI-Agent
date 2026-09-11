package com.vio.vioaiagent.pokemon;

import com.vio.vioaiagent.pokemon.domain.ActivityNotice;
import com.vio.vioaiagent.pokemon.repository.InMemoryActivityNoticeRepository;
import com.vio.vioaiagent.pokemon.service.ActivityNoticeNotFoundException;
import com.vio.vioaiagent.pokemon.service.ActivityNoticeService;
import com.vio.vioaiagent.pokemon.service.ActivityNoticeUnavailableException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActivityNoticeServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

    @Test
    void onlyFreshPublishedNoticeIsVisibleAndFreshnessIsRecomputed() {
        ActivityNotice fresh = notice("fresh", "PUBLISHED", "FRESH",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"));
        ActivityNotice stale = notice("stale", "PUBLISHED", "FRESH",
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"));
        ActivityNotice candidate = notice("candidate", "CANDIDATE", "FRESH",
                Instant.parse("2026-09-02T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"));
        ActivityNoticeService service = service(fresh, stale, candidate);

        ActivityNoticeService.ActivityQueryResult result = service.search(
                "pokemon-scarlet-violet", null, "zh-CN", "CN", 20);

        assertEquals(List.of("fresh"), result.items().stream().map(ActivityNotice::id).toList());
        assertEquals(ActivityNotice.FRESH, result.items().get(0).freshness());
    }

    @Test
    void expiredAndNotYetActiveAreHiddenAndSingleLookupDoesNotLeakContent() {
        ActivityNotice expired = notice("expired", "PUBLISHED", "FRESH",
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-15T00:00:00Z"));
        ActivityNotice future = notice("future", "PUBLISHED", "FRESH",
                Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"));
        ActivityNoticeService service = service(expired, future);

        assertEquals(0, service.listActive("pokemon-scarlet-violet", null, "zh-CN", "CN", 20).size());
        ActivityNoticeUnavailableException expiredError = assertThrows(ActivityNoticeUnavailableException.class,
                () -> service.find("expired"));
        assertEquals(ActivityNotice.EXPIRED, expiredError.reasonCode());
        assertThrows(ActivityNoticeUnavailableException.class, () -> service.find("future"));
    }

    @Test
    void missingNoticeAndVersionFiltersAreExplicit() {
        ActivityNotice base = notice("base", "PUBLISHED", "FRESH",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"));
        ActivityNotice dlc = new ActivityNotice("dlc", "dlc-event", 1, "DLC", "summary",
                "curated-source", "https://example.com/activity", "example-license", "CURATED",
                "pokemon-scarlet-violet", "other-dlc", "zh-CN", "CN", "RAID",
                Instant.parse("2026-09-02T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"),
                Instant.parse("2026-09-20T00:00:00Z"), "FRESH", "PUBLISHED", "checksum-dlc",
                "v1.1", "activity-v1", Instant.parse("2026-09-02T01:00:00Z"));
        ActivityNoticeService service = service(base, dlc);

        assertEquals(List.of("base"), service.listActive("pokemon-scarlet-violet",
                "the-hidden-treasure-of-area-zero", "zh-CN", "CN", 20).stream()
                .map(ActivityNotice::id).toList());
        assertThrows(ActivityNoticeNotFoundException.class, () -> service.find("unknown"));
    }

    @Test
    void malformedSourceIsRejectedAtDomainBoundary() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                new ActivityNotice("bad", "Bad", "summary", "", "file:///tmp/a",
                        "license", "CURATED", "pokemon-scarlet-violet", "zh-CN", NOW,
                        NOW.minusSeconds(1), NOW.plusSeconds(3600), "FRESH", "PUBLISHED", "checksum"));
    }

    @Test
    void listRequiresCanonicalReleaseAndLanguage() {
        ActivityNoticeService service = service();
        assertThrows(IllegalArgumentException.class,
                () -> service.search(null, null, "zh-CN", null, 20));
        assertThrows(IllegalArgumentException.class,
                () -> service.search("pokemon-scarlet-violet", null, "", null, 20));
    }

    private ActivityNoticeService service(ActivityNotice... notices) {
        return new ActivityNoticeService(new InMemoryActivityNoticeRepository(List.of(notices)),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(7));
    }

    private ActivityNotice notice(String id, String status, String freshness,
                                  Instant from, Instant to) {
        return new ActivityNotice(id, "event-" + id, 1, "活动 " + id, "summary",
                "curated-source", "https://example.com/activity/" + id, "example-license", "CURATED",
                "pokemon-scarlet-violet", "the-hidden-treasure-of-area-zero", "zh-CN", "CN", "RAID",
                from, from, to, freshness, status, "checksum-" + id, "v1.1", "activity-v1",
                from.plusSeconds(60));
    }
}
