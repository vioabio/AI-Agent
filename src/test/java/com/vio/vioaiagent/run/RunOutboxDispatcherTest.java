package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunOutboxDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");

    @Test
    void missingSinkDoesNotClaimDurableMessages() {
        InMemoryRunOutboxRepository repository = seeded("outbox-missing");
        ObjectProvider<RunOutboxSink> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        var report = dispatcher(repository, provider).dispatchOnce();

        assertTrue(report.sinkMissing());
        assertEquals(0, report.claimed());
        assertEquals(RunOutboxMessage.OutboxStatus.PENDING,
                repository.find("outbox-missing").orElseThrow().status());
    }

    @Test
    void successfulPublishMarksOnlyTheClaimedMessagePublished() {
        InMemoryRunOutboxRepository repository = seeded("outbox-success");
        ObjectProvider<RunOutboxSink> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(message -> { });

        var report = dispatcher(repository, provider).dispatchOnce();

        assertEquals(1, report.claimed());
        assertEquals(1, report.published());
        assertEquals(0, report.failed());
        assertEquals(RunOutboxMessage.OutboxStatus.PUBLISHED,
                repository.find("outbox-success").orElseThrow().status());
    }

    @Test
    void failedPublishReturnsToRetryWithSanitizedBoundedError() {
        InMemoryRunOutboxRepository repository = seeded("outbox-failed");
        ObjectProvider<RunOutboxSink> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(message -> {
            throw new IllegalStateException("token=secret-value\nsecond-line");
        });

        var report = dispatcher(repository, provider).dispatchOnce();
        var stored = repository.find("outbox-failed").orElseThrow();

        assertEquals(1, report.claimed());
        assertEquals(1, report.failed());
        assertEquals(RunOutboxMessage.OutboxStatus.FAILED, stored.status());
        assertTrue(stored.lastError().contains("token=[REDACTED]"));
        assertTrue(!stored.lastError().contains("secret-value"));
        assertTrue(!stored.lastError().contains("\n"));
    }

    private static InMemoryRunOutboxRepository seeded(String id) {
        InMemoryRunOutboxRepository repository = new InMemoryRunOutboxRepository();
        repository.enqueue(RunOutboxMessage.pending(id, "run-1", 1, "RUN_STARTED", Map.of()));
        return repository;
    }

    private static RunOutboxDispatcher dispatcher(InMemoryRunOutboxRepository repository,
                                                   ObjectProvider<RunOutboxSink> provider) {
        return new RunOutboxDispatcher(repository, provider,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
