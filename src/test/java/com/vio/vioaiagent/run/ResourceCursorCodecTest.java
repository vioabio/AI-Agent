package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceCursorCodecTest {
    @Test
    void bindsCursorToResourceAndOwner() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC);
        ResourceCursorCodec codec = new ResourceCursorCodec("test-secret", Duration.ofMinutes(15), clock);
        String token = codec.encode("teams", "user-a", 20);
        assertEquals(20, codec.decode(token, "teams", "user-a").offset());
        assertThrows(InvalidResourceCursorException.class, () -> codec.decode(token, "teams", "user-b"));
        assertThrows(InvalidResourceCursorException.class, () -> codec.decode(token, "favorites", "user-a"));
    }

    @Test
    void rejectsTamperingAndExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T00:00:00Z"));
        ResourceCursorCodec codec = new ResourceCursorCodec("test-secret", Duration.ofSeconds(10), clock);
        String token = codec.encode("conversations", "user-a", 5);
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");
        assertThrows(InvalidResourceCursorException.class, () -> codec.decode(tampered, "conversations", "user-a"));
        clock.advanceSeconds(11);
        assertThrows(ResourceCursorExpiredException.class, () -> codec.decode(token, "conversations", "user-a"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        private void advanceSeconds(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
