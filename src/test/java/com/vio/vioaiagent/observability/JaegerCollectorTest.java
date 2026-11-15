package com.vio.vioaiagent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.tools.governance.BoundedToolExecutor;
import com.vio.vioaiagent.tools.governance.ToolAttemptTiming;
import com.vio.vioaiagent.tools.governance.ToolExecutionProperties;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in check against the local Jaeger collector and query API. */
@EnabledIfSystemProperty(named="vio.jaeger.integration", matches="true")
class JaegerCollectorTest {
    @Test void admissionDispatchAndResumedAttemptIdentitiesAreQueryable() throws Exception {
        String runId = "run-attempts-" + UUID.randomUUID();
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        try (var runtime = new AgentTracingConfiguration().agentTracingRuntime("http://127.0.0.1:4318/v1/traces", 1, false)) {
            var registry = ObservationRegistry.create(); registry.observationConfig().observationHandler(runtime.handler());
            var observation = new AgentRunObservation(registry);
            var dispatch = observation.admission(() -> observation.dispatch(runId, first), ignored -> runId);
            assertNull(registry.getCurrentObservation());
            dispatch.finish("STARTED"); dispatch.finish("CANCELLED");
            observation.attempt(runId, first, 1, () -> "waiting", () -> "WAITING_APPROVAL");
            observation.dispatch(runId, second).finish("STARTED");
            observation.attempt(runId, second, 0, () -> "completed", () -> "COMPLETED");
            runtime.provider().forceFlush().join(2, TimeUnit.SECONDS);
            var result = awaitTrace(runId, 5, true);
            var rows = new java.util.ArrayList<JsonNode>(); result.path("spans").forEach(rows::add);
            assertEquals(2, rows.stream().filter(span -> "agent.run.execute".equals(span.path("name").asText())).count());
            assertEquals(2, rows.stream().filter(span -> "agent.run.dispatch".equals(span.path("name").asText())).count());
            assertEquals(2, rows.stream().filter(span -> "agent.run.execute".equals(span.path("name").asText()))
                    .map(span -> tag(span, "agent.run.attempt.id")).distinct().count());
            save(runId, result);
        }
    }
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test void collectorReceivesWorkerTreeAndQueryUiIsAvailable() throws Exception {
        String runId = "run-jaeger-" + UUID.randomUUID();
        var timing = new ToolAttemptTiming();
        try (var runtime = new AgentTracingConfiguration().agentTracingRuntime("http://127.0.0.1:4318/v1/traces", 1, false);
             var worker = new BoundedToolExecutor(new ToolExecutionProperties(1, 1, 500))) {
            var registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(runtime.handler());
            var tools = new AgentToolObservation(registry);
            String answer = new AgentRunObservation(registry).execute(runId,
                    () -> tools.dispatch("lookupPokemonReference", timing,
                            () -> worker.call(() -> {}, tools.capture("lookupPokemonReference",
                                    () -> new AgentModelObservation(registry).call(() -> "fixture-answer")), 2000, timing)));
            assertEquals("fixture-answer", answer);
            assertTrue(runtime.provider().forceFlush().join(3, TimeUnit.SECONDS).isSuccess());
            assertNull(registry.getCurrentObservation());
        }

        JsonNode trace = awaitTrace(runId, 4);
        assertEquals(4, trace.path("spans").size());
        Map<String, JsonNode> spans = new HashMap<>();
        trace.path("spans").forEach(span -> assertNull(spans.put(span.path("name").asText(), span)));
        assertParent(spans.get("agent.run.execute"), spans.get("agent.tool.dispatch"));
        assertParent(spans.get("agent.tool.dispatch"), spans.get("agent.tool.callback"));
        assertParent(spans.get("agent.tool.callback"), spans.get("agent.model.call"));
        JsonNode dispatch = spans.get("agent.tool.dispatch");
        assertEquals("true", tag(dispatch, "agent.tool.callbackCompleted"));
        for (String field : java.util.List.of("queueWaitMs", "authorizationMs", "executionMs", "observedMs")) {
            assertEquals(timing.freeze().get(field).toString(), tag(dispatch, "agent.tool." + field));
        }
        assertFalse(trace.toString().contains("fixture-answer"));
        var ui = get("http://127.0.0.1:16686/");
        assertEquals(200, ui.statusCode());
        assertTrue(ui.headers().firstValue("Content-Type").orElse("").contains("text/html"));
        assertTrue(ui.body().toLowerCase(java.util.Locale.ROOT).contains("<html"));
        save(runId, trace);
    }

    @Test void collectorStoresSanitizedFailures() throws Exception {
        String runId = "run-jaeger-" + UUID.randomUUID();
        var original = new IllegalArgumentException("private-jaeger-provider-body");
        try (var runtime = new AgentTracingConfiguration().agentTracingRuntime("http://127.0.0.1:4318/v1/traces", 1, false)) {
            var registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(runtime.handler());
            assertSame(original, assertThrows(IllegalArgumentException.class,
                    () -> new AgentRunObservation(registry).execute(runId,
                            () -> new AgentModelObservation(registry).call(() -> { throw original; }))));
            assertTrue(runtime.provider().forceFlush().join(3, TimeUnit.SECONDS).isSuccess());
            assertNull(registry.getCurrentObservation());
        }
        JsonNode trace = awaitTrace(runId, 2);
        assertEquals(2, trace.path("spans").size());
        String recorded = trace.toString();
        assertFalse(recorded.contains("private-jaeger-provider-body"));
        assertTrue(recorded.contains("MODEL_CALL_FAILED"));
        assertTrue(recorded.contains("RUN_EXECUTION_FAILED"));
        save(runId, trace);
    }

    @Test void cancelledStreamExportsOneChildAndPreservesRunParent() throws Exception {
        String runId = "run-jaeger-" + UUID.randomUUID();
        var sourceCalls = new java.util.concurrent.atomic.AtomicInteger();
        var stopped = new java.util.concurrent.CountDownLatch(1);
        try (var runtime = new AgentTracingConfiguration().agentTracingRuntime("http://127.0.0.1:4318/v1/traces", 1, false)) {
            var registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(runtime.handler());
            registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<io.micrometer.observation.Observation.Context>() {
                public boolean supportsContext(io.micrometer.observation.Observation.Context context) { return true; }
                public void onStop(io.micrometer.observation.Observation.Context context) {
                    if (context.getName().equals("agent.model.stream")) stopped.countDown();
                }
            });
            String answer = new AgentRunObservation(registry).execute(runId,
                    () -> new AgentModelObservation(registry).stream(() -> {
                        sourceCalls.incrementAndGet();
                        return reactor.core.publisher.Flux.just("private-stream-fragment").concatWith(reactor.core.publisher.Flux.never());
                    }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).take(1).blockLast());
            assertEquals("private-stream-fragment", answer);
            assertTrue(stopped.await(3, TimeUnit.SECONDS));
            assertEquals(1, sourceCalls.get());
            assertNull(registry.getCurrentObservation());
            assertTrue(runtime.provider().forceFlush().join(3, TimeUnit.SECONDS).isSuccess());
        }
        JsonNode trace = awaitTrace(runId, 2);
        JsonNode root = null;
        JsonNode stream = null;
        for (JsonNode span : trace.path("spans")) {
            if (span.path("name").asText().equals("agent.run.execute")) root = span;
            if (span.path("name").asText().equals("agent.model.stream")) stream = span;
        }
        assertParent(root, stream);
        assertEquals("CANCEL", tag(stream, "outcome"));
        assertFalse(trace.toString().contains("private-stream-fragment"));
        save(runId, trace);
    }

    private JsonNode awaitTrace(String runId, int expectedSpans) throws Exception {
        return awaitTrace(runId, expectedSpans, false);
    }
    private JsonNode awaitTrace(String runId, int expectedSpans, boolean attempts) throws Exception {
        var now = java.time.Instant.now();
        String from = URLEncoder.encode(now.minusSeconds(300).toString(), StandardCharsets.UTF_8);
        String until = URLEncoder.encode(now.plusSeconds(30).toString(), StandardCharsets.UTF_8);
        String query = "http://127.0.0.1:16686/api/v3/traces?query.service_name=vio-agent-local&query.start_time_min=" + from + "&query.start_time_max=" + until;
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        do {
            var response = get(query);
            if (response.statusCode() == 404 && "No traces found".equals(json.readTree(response.body()).path("error").path("message").asText())) {
                Thread.sleep(100);
                continue;
            }
            assertEquals(200, response.statusCode());
            var spans = new java.util.ArrayList<JsonNode>();
            for (JsonNode resource : json.readTree(response.body()).path("result").path("resourceSpans")) {
                for (JsonNode scope : resource.path("scopeSpans")) scope.path("spans").forEach(spans::add);
            }
            var root = spans.stream().filter(span -> runId.equals(tag(span, "agent.run.id"))).findFirst();
            if (root.isPresent()) {
                String traceId = root.get().path("traceId").asText();
                var selected = json.createArrayNode();
                spans.stream().filter(span -> attempts ? runId.equals(tag(span,"agent.run.id")) : traceId.equals(span.path("traceId").asText())).forEach(selected::add);
                if (selected.size() == expectedSpans) return json.createObjectNode().put("traceId", traceId).set("spans", selected);
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Jaeger trace was not queryable within 15 seconds");
    }

    private HttpResponse<String> get(String uri) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertParent(JsonNode parent, JsonNode child) {
        assertNotNull(parent);
        assertNotNull(child);
        assertEquals(parent.path("traceId").asText(), child.path("traceId").asText());
        assertEquals(parent.path("spanId").asText(), child.path("parentSpanId").asText());
    }

    private static String tag(JsonNode span, String key) {
        for (JsonNode tag : span.path("attributes")) if (key.equals(tag.path("key").asText())) return tag.path("value").path("stringValue").asText();
        return null;
    }

    private void save(String runId, JsonNode trace) throws Exception {
        Path directory = Path.of("artifacts", "u4-jaeger-collector");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(runId + ".json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(trace));
    }
}
