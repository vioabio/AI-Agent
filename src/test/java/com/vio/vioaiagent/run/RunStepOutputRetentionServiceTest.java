package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.DurableRunProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunStepOutputRetentionServiceTest {
    @Test
    void cleanupIsDisabledByDefault() {
        DurableRunProperties properties = new DurableRunProperties();
        RunStepOutputRetentionService service = new RunStepOutputRetentionService(
                provider(null), properties, fixedClock());
        assertEquals("DISABLED", service.cleanupExpiredBatch().status());
    }

    @Test
    void cleanupUsesBoundedRepositoryBatch() {
        DurableRunProperties properties = new DurableRunProperties();
        properties.setStepOutputRetentionCleanupEnabled(true);
        properties.setStepOutputRetentionCleanupBatchSize(7);
        InMemoryRunStepOutputRepository repository = new InMemoryRunStepOutputRepository();
        RunStepOutputRetentionService service = new RunStepOutputRetentionService(
                provider(repository), properties, fixedClock());
        RunStepOutputRetentionService.CleanupResult result = service.cleanupExpiredBatch();
        assertEquals("COMPLETED", result.status());
        assertEquals(7, result.batchSize());
        assertEquals(0, result.deleted());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            public T getObject(Object... args) { return value; }
            public T getIfAvailable() { return value; }
            public T getIfUnique() { return value; }
            public T getIfAvailable(java.util.function.Supplier<T> supplier) { return value == null ? supplier.get() : value; }
            public T getIfUnique(java.util.function.Supplier<T> supplier) { return value == null ? supplier.get() : value; }
            public Iterator<T> iterator() { return value == null ? java.util.Collections.emptyIterator() : java.util.Collections.singleton(value).iterator(); }
            public java.util.stream.Stream<T> orderedStream() { return value == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(value); }
            public java.util.stream.Stream<T> stream() { return orderedStream(); }
        };
    }
}
