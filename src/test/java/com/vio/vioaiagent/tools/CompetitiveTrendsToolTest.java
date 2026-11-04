package com.vio.vioaiagent.tools;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CompetitiveTrendsToolTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC);
    static final String BODY = "Total battles: 12345\n| 1 | Great Tusk | 28.31876% | 1 |\n| 2 | Gholdengo | 21.63658% | 2 |\n";
    @Test void discoversPublishedMonthAndRetainsScopeProvenanceAndCachedRetrievalTime() {
        List<String> urls = new ArrayList<>();
        var tool = new CompetitiveTrendsTool(url -> {urls.add(url);return url.endsWith("/stats/") ? "<a href=\"2026-09/\">old</a><a href=\"2026-10/\">new</a><a href=\"2099-01/\">bad</a>" : BODY;}, CLOCK);
        var snapshot = tool.lookupCompetitiveTrends("gen9ou");
        assertEquals("2026-10", snapshot.month()); assertEquals(12345, snapshot.battles());
        assertEquals("28.31876", snapshot.entries().getFirst().usagePercent());
        assertEquals("https://www.smogon.com/stats/2026-10/gen9ou-0.txt", snapshot.sourceUrl());
        assertTrue(snapshot.limitation().contains("不能将前六名拼成热门队伍"));
        assertEquals(snapshot, tool.lookupCompetitiveTrends("gen9ou")); assertEquals(2, urls.size());
        assertThrows(IllegalArgumentException.class, () -> tool.lookupCompetitiveTrends("casual-singles"));
        assertThrows(IllegalArgumentException.class, () -> tool.lookupCompetitiveTrends("https://private.invalid"));
    }
    @Test void invalidOrUnavailableUpstreamNeverBecomesAValidEmptyRanking() {
        for (String body : new String[]{"<html>blocked</html>", "Total battles: 123\n| 1 | Pikachu | 200% |", "Total battles: 123\n| 2 | Pikachu | 20% |"}) {
            var tool = new CompetitiveTrendsTool(url -> url.endsWith("/stats/") ? "<a href='2026-10/'>month</a>" : body, CLOCK);
            assertThrows(IllegalStateException.class, () -> tool.lookupCompetitiveTrends("gen9ou"));
        }
        var tool = new CompetitiveTrendsTool(url -> {throw new java.io.IOException("private network error");}, CLOCK);
        assertFalse(assertThrows(IllegalStateException.class, () -> tool.lookupCompetitiveTrends("gen9ou")).getMessage().contains("private"));
    }
    @Test void expiredCacheDoesNotMasqueradeAsLatestAfterFetchFailure() {
        AtomicInteger calls = new AtomicInteger();
        var time = new java.util.concurrent.atomic.AtomicReference<>(CLOCK.instant());
        Clock clock = new Clock() { public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return time.get();} };
        var tool = new CompetitiveTrendsTool(url -> { if(calls.incrementAndGet()>2)throw new java.io.IOException();return url.endsWith("/stats/") ? "<a href='2026-10/'>month</a>" : BODY;}, clock);
        tool.lookupCompetitiveTrends("gen9ou");time.set(time.get().plusSeconds(901));
        assertThrows(IllegalStateException.class, () -> tool.lookupCompetitiveTrends("gen9ou"));
    }
}
