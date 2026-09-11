package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.ModelInvocationCacheProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelInvocationCacheServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

    @Test
    void cacheHitSkipsSecondModelInvocation() {
        ModelInvocationCacheProperties properties = enabledProperties();
        InMemoryDurableRunStateRepository repository = new InMemoryDurableRunStateRepository();
        ModelInvocationCacheService service = new ModelInvocationCacheService(repository,
                codec(), properties, fixedClock());
        AgentRun run = run();
        AtomicInteger calls = new AtomicInteger();

        String first = service.invoke(run, "grounded prompt", prompt -> {
            calls.incrementAndGet();
            return "可核查回答";
        });
        String second = service.invoke(run, "grounded prompt", prompt -> {
            calls.incrementAndGet();
            return "不应再次调用";
        });

        assertEquals("可核查回答", first);
        assertEquals("可核查回答", second);
        assertEquals(1, calls.get());
        assertTrue(service.readiness().ready());
    }

    @Test
    void disabledCachePreservesOriginalGeneratorPath() {
        ModelInvocationCacheProperties properties = enabledProperties();
        properties.setEnabled(false);
        ModelInvocationCacheService service = new ModelInvocationCacheService(null, null,
                properties, fixedClock());
        AtomicInteger calls = new AtomicInteger();

        assertEquals("first", service.invoke(run(), "prompt", ignored -> {
            calls.incrementAndGet();
            return calls.get() == 1 ? "first" : "second";
        }));
        assertEquals("second", service.invoke(run(), "prompt", ignored -> {
            calls.incrementAndGet();
            return calls.get() == 2 ? "second" : "unexpected";
        }));
        assertEquals(2, calls.get());
        assertTrue(!service.readiness().enabled());
    }

    @Test
    void enabledCacheWithoutControlledCodecFailsClosed() {
        ModelInvocationCacheService service = new ModelInvocationCacheService(
                new InMemoryDurableRunStateRepository(), null, enabledProperties(), fixedClock());

        assertThrows(ModelInvocationCacheException.class,
                () -> service.invoke(run(), "prompt", ignored -> "不会执行"));
    }

    @Test
    void corruptedCachedResponseIsRejectedByDigestCheck() {
        RecordingRepository repository = new RecordingRepository();
        ModelInvocationCacheProperties properties = enabledProperties();
        ModelInvocationCacheService service = new ModelInvocationCacheService(repository,
                codec(), properties, fixedClock());
        AgentRun run = run();

        service.invoke(run, "prompt", ignored -> "原始回答");
        ModelInvocationCacheEntry original = repository.entry;
        repository.entry = new ModelInvocationCacheEntry(original.runId(), original.stepNo(),
                original.requestHash(), original.modelVersion(), original.promptVersion(),
                "0000000000000000000000000000000000000000000000000000000000000000",
                original.encryptedResponse(), original.artifactUri(), original.usage(),
                original.keyVersion(), original.createdAt(), original.expiresAt());

        assertThrows(ModelInvocationCacheException.class,
                () -> service.invoke(run, "prompt", ignored -> "不应执行"));
    }

    @Test
    void concurrentMissesShareOneModelInvocation() throws Exception {
        ModelInvocationCacheService service = new ModelInvocationCacheService(
                new InMemoryDurableRunStateRepository(), codec(), enabledProperties(), fixedClock());
        AgentRun run = run();
        AtomicInteger calls = new AtomicInteger();
        int callers = 12;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch generatorEntered = new CountDownLatch(1);
        CountDownLatch releaseGenerator = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return service.invoke(run, "same grounded prompt", ignored -> {
                        calls.incrementAndGet();
                        generatorEntered.countDown();
                        try {
                            if (!releaseGenerator.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test generator release timed out");
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("test generator interrupted", ex);
                        }
                        return "single response";
                    });
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(generatorEntered.await(5, TimeUnit.SECONDS));
            releaseGenerator.countDown();
            for (Future<String> future : futures) {
                assertEquals("single response", future.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, calls.get());
        } finally {
            releaseGenerator.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private ModelInvocationCacheProperties enabledProperties() {
        ModelInvocationCacheProperties properties = new ModelInvocationCacheProperties();
        properties.setEnabled(true);
        properties.setFailClosed(true);
        properties.setStepNo(1);
        properties.setTtlSeconds(3600);
        properties.setModelVersion("model-v1");
        properties.setPromptVersion("prompt-v1");
        return properties;
    }

    private ModelInvocationCacheCodec codec() {
        return new ModelInvocationCacheCodec() {
            @Override
            public String encode(String response) {
                return "ENC:" + response;
            }

            @Override
            public String decode(String encodedResponse) {
                return encodedResponse != null && encodedResponse.startsWith("ENC:")
                        ? encodedResponse.substring(4) : encodedResponse;
            }

            @Override
            public String keyVersion() {
                return "test-key-v1";
            }
        };
    }

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private AgentRun run() {
        return AgentRun.create("run-cache", "session-cache", "conversation-cache", "user-cache",
                "如何配队？", "sv-1", "casual-singles", "rules-1", "zh-CN",
                "trace-cache", null, NOW.plusSeconds(60));
    }

    private static final class RecordingRepository implements ModelInvocationCacheRepository {
        private ModelInvocationCacheEntry entry;

        @Override
        public Optional<ModelInvocationCacheEntry> findValid(String runId, int stepNo,
                                                              String requestHash, Instant now) {
            if (entry == null || !runId.equals(entry.runId()) || stepNo != entry.stepNo()
                    || !requestHash.equals(entry.requestHash()) || entry.expired(now)) {
                return Optional.empty();
            }
            return Optional.of(entry);
        }

        @Override
        public ModelInvocationCacheEntry save(ModelInvocationCacheEntry value) {
            entry = value;
            return value;
        }

        @Override
        public int deleteExpired(Instant now, int limit) {
            if (entry != null && entry.expired(now)) {
                entry = null;
                return 1;
            }
            return 0;
        }
    }
}
