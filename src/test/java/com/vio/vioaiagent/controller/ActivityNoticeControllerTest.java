package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.pokemon.domain.ActivityNotice;
import com.vio.vioaiagent.pokemon.repository.InMemoryActivityNoticeRepository;
import com.vio.vioaiagent.pokemon.service.ActivityNoticeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActivityNoticeControllerTest {
    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

    @Test
    void listReturnsVersionAndFreshnessMetadata() {
        ActivityNotice notice = new ActivityNotice("n1", "raid-charizard", 2, "活动", "summary",
                "official", "https://example.com/activity", "official-license", "OFFICIAL",
                "pokemon-scarlet-violet", "the-hidden-treasure-of-area-zero", "zh-CN", "CN", "RAID",
                NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(3)),
                "STALE", "PUBLISHED", "sha256-n1", "v1.1", "activity-v2", NOW.minusSeconds(1));
        ActivityNoticeController controller = new ActivityNoticeController(new ActivityNoticeService(
                new InMemoryActivityNoticeRepository(List.of(notice)),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(7)));

        var response = controller.list("pokemon-scarlet-violet", null, "zh-CN", "CN", 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().getData().total());
        assertEquals("FRESH", response.getBody().getData().items().get(0).freshness());
        assertEquals(2, response.getBody().getData().items().get(0).versionNo());
    }

    @Test
    void badRequestUsesStableHttpStatus() {
        ActivityNoticeController controller = new ActivityNoticeController(new ActivityNoticeService(
                new InMemoryActivityNoticeRepository()));
        var response = controller.badRequest(new IllegalArgumentException("at 非法"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
