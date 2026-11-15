package com.vio.vioaiagent.observability;

import io.micrometer.observation.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentTracingConfigurationTest {
    @Test void partialSamplingKeepsWholeDomainTreesAndRestoresThreadScope() {
        var capture = new Capture();
        try (var runtime = AgentTracingConfiguration.runtime(capture, 0.25)) {
            var registry = ObservationRegistry.create(); registry.observationConfig().observationHandler(runtime.handler());
            var runs = new AgentRunObservation(registry);
            for (int i = 0; i < 128; i++) {
                assertEquals("ok", runs.execute("run-sampling-" + i, () -> {
                    runs.phase(AgentRunObservation.Phase.AUTHORIZE, () -> true);
                    return new AgentModelObservation(registry).call(() -> "ok");
                }));
                assertNull(registry.getCurrentObservation());
                if (i % 16 == 15) assertTrue(runtime.provider().forceFlush().join(2,TimeUnit.SECONDS).isSuccess());
            }
            var traces = capture.spans.stream().collect(java.util.stream.Collectors.groupingBy(SpanData::getTraceId));
            assertTrue(traces.size() > 0 && traces.size() < 128);
            for (var spans : traces.values()) {
                assertEquals(3,spans.size());
                var root = spans.stream().filter(span -> span.getName().equals("agent.run.execute")).findFirst().orElseThrow();
                assertTrue(spans.stream().filter(span -> span != root).allMatch(span -> span.getParentSpanId().equals(root.getSpanId())));
            }
        }
    }
    @Test void sampledForeignParentCannotOverrideZeroDomainSampling() {
        var capture = new Capture();
        try (var foreignProvider = io.opentelemetry.sdk.trace.SdkTracerProvider.builder()
                    .setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOn()).build();
             var runtime = AgentTracingConfiguration.runtime(capture, 0)) {
            var registry = ObservationRegistry.create(); registry.observationConfig().observationHandler(runtime.handler());
            var foreign = foreignProvider.get("foreign-http").spanBuilder("sampled-http").startSpan();
            try (var ignored = foreign.makeCurrent()) {
                assertEquals("ok",new AgentRunObservation(registry).execute("run-zero", () ->
                        new AgentModelObservation(registry).call(() -> "ok")));
                assertSame(foreign,io.opentelemetry.api.trace.Span.current());
            } finally { foreign.end(); }
            assertTrue(runtime.provider().forceFlush().join(2,TimeUnit.SECONDS).isSuccess());
            assertTrue(capture.spans.isEmpty());
            assertNull(registry.getCurrentObservation());
        }
    }
    @Test void unsampledForeignHttpContextCannotSuppressAdmissionAndDispatch() {
        var capture = new Capture();
        try (var foreignProvider = io.opentelemetry.sdk.trace.SdkTracerProvider.builder()
                    .setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOff()).build();
             var runtime = AgentTracingConfiguration.runtime(capture, 1)) {
            var registry = ObservationRegistry.create(); registry.observationConfig().observationHandler(runtime.handler());
            registry.observationConfig().observationHandler(new io.micrometer.tracing.handler.DefaultTracingObservationHandler(io.micrometer.tracing.Tracer.NOOP));
            var foreign = foreignProvider.get("foreign-http").spanBuilder("http-request").startSpan();
            try (var ignored = foreign.makeCurrent()) {
                var observation = new AgentRunObservation(registry);
                observation.admission(() -> {
                    observation.dispatch("run-under-http", UUID.randomUUID().toString()).finish("CANCELLED");
                    return "run-under-http";
                }, java.util.function.Function.identity());
                assertSame(foreign, io.opentelemetry.api.trace.Span.current());
            } finally { foreign.end(); }
            runtime.provider().forceFlush().join(2, TimeUnit.SECONDS);
            assertEquals(2, capture.spans.size());
            assertTrue(capture.spans.stream().noneMatch(span -> span.getTraceId().equals(foreign.getSpanContext().getTraceId())));
            var admission = capture.spans.stream().filter(span -> span.getName().equals("agent.run.admit")).findFirst().orElseThrow();
            var dispatch = capture.spans.stream().filter(span -> span.getName().equals("agent.run.dispatch")).findFirst().orElseThrow();
            assertEquals(admission.getSpanId(), dispatch.getParentSpanId());
            assertNull(registry.getCurrentObservation());
        }
    }
    @Test void workerAttemptExportsAuthorizationAndCommitWithOneRunRoot() {
        var capture = new Capture();
        try (var runtime = AgentTracingConfiguration.runtime(capture, 1)) {
            var registry = ObservationRegistry.create(); registry.observationConfig().observationHandler(runtime.handler());
            var observations = new AgentRunObservation(registry);
            observations.attempt("run-lifecycle", 37, () -> {
                observations.phase(AgentRunObservation.Phase.AUTHORIZE, () -> true);
                observations.execute("run-lifecycle", () -> new AgentModelObservation(registry).call(() -> "answer"));
                return observations.phase(AgentRunObservation.Phase.COMMIT, () -> "saved");
            }, () -> "COMPLETED");
            runtime.provider().forceFlush().join(2, TimeUnit.SECONDS);
            assertEquals(4, capture.spans.size());
            var root = capture.spans.stream().filter(s -> s.getName().equals("agent.run.execute")).findFirst().orElseThrow();
            assertEquals("37", root.getAttributes().get(AttributeKey.stringKey("agent.run.dispatch_wait_ms")));
            assertEquals("COMPLETED", root.getAttributes().get(AttributeKey.stringKey("agent.run.status")));
            assertTrue(capture.spans.stream().filter(s -> s != root).allMatch(s -> s.getParentSpanId().equals(root.getSpanId())));
            assertNull(registry.getCurrentObservation());
        }
    }
    @Test void domainTracingCoexistsWithFrameworkNoopTracingHandler() {
        var capture = new Capture();
        try (var runtime = AgentTracingConfiguration.runtime(capture, 1)) {
            var registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(runtime.handler());
            registry.observationConfig().observationHandler(new io.micrometer.tracing.handler.DefaultTracingObservationHandler(io.micrometer.tracing.Tracer.NOOP));
            new AgentRunObservation(registry).execute("run-with-framework-handler",()->new AgentModelObservation(registry).call(()->"ok"));
            runtime.provider().forceFlush().join(2,TimeUnit.SECONDS);
            assertEquals(2,capture.spans.size());
            assertNull(registry.getCurrentObservation());
        }
    }
    @Test void frameworkContextMayHaveNoNameWhenHandlersAreSelected() {
        var capture = new Capture();
        try (var runtime = AgentTracingConfiguration.runtime(capture, 1)) {
            assertFalse(runtime.handler().supportsContext(new Observation.Context()));
            var http = new Observation.Context(); http.setName("http.server.requests");
            assertFalse(runtime.handler().supportsContext(http));
            var registry = ObservationRegistry.create(); registry.observationConfig().observationHandler(runtime.handler());
            assertEquals("healthy",new AgentRunObservation(registry).execute("run-after-http",()->"healthy"));
            runtime.provider().forceFlush().join(2,TimeUnit.SECONDS);
            assertEquals(1,capture.spans.size());
        }
    }
    @Test void toolWorkerKeepsRunAndNestedModelParentAndClearsScopeAfterFailure() throws Exception {
        var capture = new Capture();
        try (var runtime = AgentTracingConfiguration.runtime(capture, 1);
             var worker = new com.vio.vioaiagent.tools.governance.BoundedToolExecutor(new com.vio.vioaiagent.tools.governance.ToolExecutionProperties(1, 2, 500))) {
            var registry = ObservationRegistry.create(); registry.observationConfig().observationHandler(runtime.handler());
            for (String id : List.of("run-worker-a", "run-worker-b")) {
                new AgentRunObservation(registry).execute(id, () -> worker.call(() -> { }, new AgentToolObservation(registry).capture("lookupPokemonReference", () -> {
                    assertEquals("agent.tool.callback", registry.getCurrentObservation().getContext().getName());
                    return new AgentModelObservation(registry).call(() -> "result");
                }), 2000));
            }
            var original = new IllegalArgumentException("private-tool-body-test");
            assertSame(original, assertThrows(IllegalArgumentException.class, () -> worker.call(() -> { }, new AgentToolObservation(registry).capture("lookupPokemonReference", () -> { throw original; }), 2000)));
            worker.call(() -> { }, () -> { assertNull(registry.getCurrentObservation()); return "clean"; }, 2000);
            runtime.provider().forceFlush().join(2, TimeUnit.SECONDS);
            assertEquals(7, capture.spans.size());
            var roots = capture.spans.stream().filter(s -> s.getName().equals("agent.run.execute")).toList();
            assertEquals(2, roots.size()); assertNotEquals(roots.get(0).getTraceId(), roots.get(1).getTraceId());
            for (var root : roots) {
                var child = capture.spans.stream().filter(s -> s.getName().equals("agent.tool.callback") && s.getParentSpanId().equals(root.getSpanId())).findFirst().orElseThrow();
                assertEquals(root.getTraceId(), child.getTraceId());
                assertTrue(capture.spans.stream().anyMatch(s -> s.getName().equals("agent.model.call") && s.getParentSpanId().equals(child.getSpanId()) && s.getTraceId().equals(root.getTraceId())));
            }
            assertFalse(capture.spans.toString().contains("private-tool-body-test"));
            assertNull(registry.getCurrentObservation());
        }
    }
    static class Capture implements SpanExporter {
        final List<SpanData> spans = new CopyOnWriteArrayList<>();
        public CompletableResultCode export(Collection<SpanData> values) { spans.addAll(values); return CompletableResultCode.ofSuccess(); }
        public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }
    @Test void realBridgePreservesRunParentForSyncAndAsyncModelAndExcludesOtherObservations() {
        var capture = new Capture();
        try (var runtime = AgentTracingConfiguration.runtime(capture, 1)) {
            var registry = ObservationRegistry.create(); registry.observationConfig().observationHandler(runtime.handler());
            new AgentRunObservation(registry).execute("run-trace-test", () -> {
                new AgentModelObservation(registry).call(() -> "answer");
                new AgentModelObservation(registry).stream(() -> reactor.core.publisher.Flux.just("text")).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).blockLast();
                Observation.createNotStarted("spring.ai.chat", registry).highCardinalityKeyValue("prompt", "private-prompt-test").observe(() -> { });
                return "done";
            });
            // blockLast may wake before Reactor's asynchronous doFinally stops its span.
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).untilAsserted(() -> {
                runtime.provider().forceFlush().join(1, TimeUnit.SECONDS);
                assertEquals(3, capture.spans.size());
            });
            var root = capture.spans.stream().filter(s -> s.getName().equals("agent.run.execute")).findFirst().orElseThrow();
            assertEquals("run-trace-test", root.getAttributes().get(AttributeKey.stringKey("agent.run.id")));
            capture.spans.stream().filter(s -> s != root).forEach(s -> { assertEquals(root.getTraceId(), s.getTraceId()); assertEquals(root.getSpanId(), s.getParentSpanId()); });
            assertFalse(capture.spans.toString().contains("private-prompt-test"));
            assertNull(registry.getCurrentObservation());
        }
    }
    @Test void exceptionPayloadIsSanitizedBeforeItReachesExporterAndZeroSamplingDropsSpans() {
        var capture = new Capture();
        try (var runtime = AgentTracingConfiguration.runtime(capture, 1)) {
            var registry = ObservationRegistry.create(); registry.observationConfig().observationHandler(runtime.handler());
            var failure = new IllegalStateException("private-provider-body-test");
            assertSame(failure, assertThrows(IllegalStateException.class, () -> new AgentModelObservation(registry).call(() -> { throw failure; })));
            runtime.provider().forceFlush().join(2, TimeUnit.SECONDS);
            assertEquals(1, capture.spans.size());
            assertTrue(capture.spans.getFirst().getEvents().stream().anyMatch(e -> "MODEL_CALL_FAILED".equals(e.getAttributes().get(AttributeKey.stringKey("exception.message")))));
            assertFalse(capture.spans.toString().contains("private-provider-body-test"));
        }
        var skipped = new Capture();
        try (var runtime = AgentTracingConfiguration.runtime(skipped, 0)) {
            var registry = ObservationRegistry.create(); registry.observationConfig().observationHandler(runtime.handler());
            new AgentModelObservation(registry).call(() -> "answer");
            runtime.provider().forceFlush().join(2, TimeUnit.SECONDS); assertTrue(skipped.spans.isEmpty());
        }
    }
    @Test void httpExporterPostsOtlpAndUnavailableCollectorDoesNotReplayBusiness() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var received = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/v1/traces", e -> {
            assertEquals("POST", e.getRequestMethod());
            assertTrue(e.getRequestHeaders().getFirst("Content-Type").contains("protobuf"));
            if (e.getRequestBody().readAllBytes().length > 0) received.incrementAndGet();
            e.sendResponseHeaders(200, -1); e.close();
        }); server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces";
        try (var runtime = new AgentTracingConfiguration().agentTracingRuntime(endpoint, 1, false)) {
            var registry = ObservationRegistry.create(); registry.observationConfig().observationHandler(runtime.handler());
            new AgentRunObservation(registry).execute("run-http-test", () -> "ok");
            runtime.provider().forceFlush().join(2, TimeUnit.SECONDS); assertEquals(1, received.get());
            server.stop(0);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> assertEquals(1, new AgentRunObservation(registry).execute("run-offline-test", calls::incrementAndGet)));
            runtime.provider().forceFlush().join(2, TimeUnit.SECONDS); assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }
    @Test void featureIsDisabledByDefaultAndExcludedFromProdAndDemo() {
        var runner = new ApplicationContextRunner().withUserConfiguration(AgentTracingConfiguration.class);
        runner.withPropertyValues("spring.profiles.active=local").run(c -> assertFalse(c.containsBean("agentTracingRuntime")));
        for (String profile : List.of("prod", "demo", "local,prod"))
            runner.withPropertyValues("spring.profiles.active=" + profile, "vio.observability.tracing.enabled=true").run(c -> assertFalse(c.containsBean("agentTracingRuntime")));
    }
    @Test void enabledLocalConfigurationRegistersTheHandlerOnBootRegistry() {
        new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration.class))
                .withUserConfiguration(AgentTracingConfiguration.class)
                .withPropertyValues("spring.profiles.active=local", "vio.observability.tracing.enabled=true", "vio.observability.tracing.sample-rate=0")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var registry = context.getBean(ObservationRegistry.class);
                    new AgentRunObservation(registry).execute("run-bean-test", () -> { assertNotNull(registry.getCurrentObservation()); return "ok"; });
                    assertNull(registry.getCurrentObservation());
                });
    }
    @Test void rejectsRemoteCollectorsCredentialsAndInvalidSamplingWithoutDisclosingInput() {
        for (String endpoint : List.of("https://example.com/v1/traces", "http://user:private@localhost:4318/v1/traces", "http://localhost:4318/v1/traces?key=private", "file:///tmp/traces", "http://127.0.0.1:4318/other"))
            assertEquals("Tracing requires a loopback OTLP HTTP /v1/traces endpoint", assertThrows(IllegalArgumentException.class, () -> AgentTracingConfiguration.localEndpoint(endpoint)).getMessage());
        for (double rate : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -.1, 1.1}) assertThrows(IllegalArgumentException.class, () -> AgentTracingConfiguration.validateSampling(rate));
        assertThrows(IllegalArgumentException.class, () -> new AgentTracingConfiguration().agentTracingRuntime("http://localhost:4318/v1/traces", 1, true));
    }
}
