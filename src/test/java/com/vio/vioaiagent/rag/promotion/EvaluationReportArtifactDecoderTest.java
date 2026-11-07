package com.vio.vioaiagent.rag.promotion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationReportArtifactDecoderTest {
    private static final String SOURCE_HASH = "a".repeat(64);
    private static final String BASELINE_HASH = "sha256:" + "b".repeat(64);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final EvaluationReportArtifactDecoder decoder = new EvaluationReportArtifactDecoder(mapper);

    @Test
    void completeFixtureRecomputesPassAndUsesOriginalBytes() throws Exception {
        // Synthetic contract fixture, never a real evaluation or release evidence.
        ObjectNode fixture = readyFixture();
        fixture.put("passed", false); // caller-controlled pass flags are not authoritative
        metric(fixture, "fact_task_success_rate").put("passed", false);
        byte[] bytes = bytes(fixture);
        EvaluationReport report = decoder.decode(bytes, EvaluationReportArtifactDecoder.hash(bytes), baseline());
        assertTrue(report.passed());
        assertTrue(report.immutable());
        assertEquals(924, report.sampleSize());
        assertEquals("v1.1", report.suiteVersion());
        assertEquals(">=0.98", report.thresholds().get("fact_task_success_rate"));
        assertEquals("<=2500", report.thresholds().get("run_ttft_p95_ms"));
        assertEquals("<=120.0", report.thresholds().get("cost_usd_total"));
        assertFalse(report.thresholds().containsKey("run_ttft_p50_ms"));
        assertFalse(report.metrics().containsKey("correction_closure_rate"));
        assertTrue(report.meetsThresholds());
        assertEquals(EvaluationReportArtifactDecoder.hash(bytes), report.reportHash());
    }

    @Test
    void rawByteMutationAndNoncanonicalHashAreRejected() throws Exception {
        byte[] bytes = bytes(readyFixture());
        String originalHash = EvaluationReportArtifactDecoder.hash(bytes);
        byte[] reformatted = (new String(bytes, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(reformatted, originalHash, baseline()));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(bytes, originalHash.toUpperCase(), baseline()));
        assertNotEquals(originalHash, EvaluationReportArtifactDecoder.hash(reformatted));
    }

    @Test
    void missingSuiteAndDuplicateSuiteAreRejected() throws Exception {
        ObjectNode missing = readyFixture();
        ((ArrayNode) missing.path("suites")).remove(0);
        assertInvalid(missing);
        ObjectNode duplicate = readyFixture();
        ((ArrayNode) duplicate.path("suites")).add(duplicate.path("suites").get(0).deepCopy());
        assertInvalid(duplicate);
    }

    @Test
    void missingAndDuplicateMetricsAreRejected() throws Exception {
        ObjectNode missing = readyFixture();
        ((ArrayNode) missing.path("results")).remove(0);
        assertInvalid(missing);
        ObjectNode duplicate = readyFixture();
        ((ArrayNode) duplicate.path("results")).add(duplicate.path("results").get(0).deepCopy());
        assertInvalid(duplicate);
    }

    @Test
    void producerCannotRelaxThresholdOrItsVersion() throws Exception {
        ObjectNode threshold = readyFixture();
        metric(threshold, "fact_task_success_rate").put("threshold", "< 0.50");
        assertInvalid(threshold);
        ObjectNode version = readyFixture();
        metric(version, "fact_task_success_rate").put("thresholdVersion", "easier-v2");
        assertInvalid(version);
    }

    @Test
    void readySuiteCannotReduceTheServerMinimumOrMisstateAggregate() throws Exception {
        ObjectNode insufficient = readyFixture();
        ObjectNode fact = suite(insufficient, "fact-v1");
        fact.put("datasetCaseCount", 199).put("sampleCount", 199).put("passedCount", 199);
        insufficient.put("datasetCaseCount", 923).put("sampleCount", 923);
        assertInvalid(insufficient);
        ObjectNode aggregate = readyFixture();
        aggregate.put("sampleCount", 925);
        assertInvalid(aggregate);
    }

    @Test
    void readyMetricCannotReduceItsDenominatorOrInventAPass() throws Exception {
        ObjectNode insufficient = readyFixture();
        metric(insufficient, "fact_task_success_rate").put("sampleCount", 199);
        assertInvalid(insufficient);
        ObjectNode failing = readyFixture();
        metric(failing, "fact_task_success_rate").put("value", 0.97).put("passed", true);
        assertInvalid(failing);
    }

    @Test
    void ratioMetricsAcceptExactlyOneWithoutRestrictingUsageOrLatency() throws Exception {
        ObjectNode fixture = readyFixture();
        // 100% is valid even for the calculation metric whose threshold is 1.0.
        for (String id : ratioMetricIds()) metric(fixture, id).put("value", 1.0);
        metric(fixture, "tokens_consumed_total").put("value", 120.0);
        metric(fixture, "cost_usd_total").put("value", 120.0);
        metric(fixture, "run_ttft_p50_ms").put("value", 500.0);
        metric(fixture, "run_ttft_p95_ms").put("value", 2000.0);
        metric(fixture, "run_duration_p50_ms").put("value", 3000.0);
        metric(fixture, "run_duration_p95_ms").put("value", 15000.0);
        metric(fixture, "cancel_effective_p95_ms").put("value", 1000.0);

        EvaluationReport report = decode(fixture, baseline());
        assertTrue(report.passed());
        assertTrue(report.meetsThresholds());
        for (String id : ratioMetricIds()) assertEquals(1.0, report.metrics().get(id));
        assertEquals(120.0, report.metrics().get("tokens_consumed_total"));
        assertEquals(120.0, report.metrics().get("cost_usd_total"));
        assertEquals(15000.0, report.metrics().get("run_duration_p95_ms"));
    }

    @Test
    void ratiosAboveOneAreInvalidEvidenceInReadyAndPendingReports() throws Exception {
        for (String status : List.of("ready", "pending")) {
            for (String id : ratioMetricIds()) {
                ObjectNode fixture = readyFixture();
                fixture.put("status", status);
                metric(fixture, id).put("status", status).put("value", Math.nextUp(1.0));
                byte[] artifact = bytes(fixture);
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                        () -> decoder.decode(artifact, EvaluationReportArtifactDecoder.hash(artifact), baseline()),
                        status + " report must reject invalid ratio evidence for " + id);
                assertTrue(failure.getMessage().contains(id));
            }
        }
    }

    @Test
    void pendingUsageAndLatencyAboveOneRemainArchivalObservations() throws Exception {
        ObjectNode fixture = readyFixture().put("status", "pending");
        metric(fixture, "tokens_consumed_total").put("status", "pending").put("value", 121.0);
        metric(fixture, "cost_usd_total").put("status", "pending").put("value", 121.0);
        metric(fixture, "run_ttft_p95_ms").put("status", "pending").put("value", 2501.0);
        EvaluationReport report = decode(fixture, baseline());
        assertFalse(report.passed());
        assertEquals(121.0, report.metrics().get("tokens_consumed_total"));
        assertEquals(121.0, report.metrics().get("cost_usd_total"));
        assertEquals(2501.0, report.metrics().get("run_ttft_p95_ms"));
    }

    @Test
    void pendingObservationsAreArchivedWithoutAReleasePass() throws Exception {
        ObjectNode pending = readyFixture();
        pending.put("status", "pending").put("sampleCount", 725);
        ObjectNode fact = suite(pending, "fact-v1");
        fact.put("status", "pending").put("sampleCount", 1).put("passedCount", 1);
        ((ArrayNode) fact.path("errors")).add("Synthetic fixture: 1/200 samples");
        metric(pending, "fact_task_success_rate").put("status", "pending").put("sampleCount", 1);
        EvaluationReport report = decode(pending, baseline());
        assertFalse(report.passed());
        assertEquals(725, report.sampleSize());
        ObjectNode noObservation = readyFixture();
        noObservation.put("status", "pending");
        metric(noObservation, "fact_task_success_rate").put("status", "pending").put("sampleCount", 0).putNull("value");
        assertFalse(decode(noObservation, baseline()).passed());
    }

    @Test
    void actualRunnerPendingShapeWithOneFactAndNoSecurityObservationCanBeArchived() throws Exception {
        // Matches the producer's observed sample/metric shape, with synthetic
        // observations and metadata; never actual quality or release evidence.
        ObjectNode pending = readyFixture();
        pending.put("status", "pending").put("sampleCount", 1).putNull("securityRedTeamFailures");
        pending.remove("baselineEvaluationRunId");
        pending.remove("baselineReportHash");
        for (JsonNode item : pending.path("suites")) {
            ObjectNode suite = (ObjectNode) item;
            int observed = suite.path("suiteId").asText().equals("fact-v1") ? 1 : 0;
            suite.put("status", "pending").put("sampleCount", observed).put("passedCount", observed);
            ((ArrayNode) suite.path("errors")).add("Synthetic incomplete observed suite");
        }
        for (JsonNode item : pending.path("results")) {
            ObjectNode metric = (ObjectNode) item;
            if (metric.path("evaluationPhase").asText().equals("n6b")) continue;
            metric.put("status", "pending").put("passed", false);
            if (metric.path("metricId").asText().equals("fact_task_success_rate")) {
                metric.put("sampleCount", 1).put("value", 1.0);
            } else {
                metric.put("sampleCount", 0).putNull("value");
            }
        }
        EvaluationReport archived = decode(pending, null);
        assertFalse(archived.passed());
        assertEquals(1, archived.sampleSize());
        assertFalse(archived.metrics().containsKey("securityRedTeamFailures"));
        assertFalse(archived.hasUsableEvidence());
        assertFalse(archived.meetsThresholds(), "Unknown safety evidence must not become zero failures");
    }

    @Test
    void safetyFailureCountCannotContradictPassedCasesOrHideObservedSafetySamples() throws Exception {
        ObjectNode contradictory = readyFixture();
        suite(contradictory, "security-redteam-v1").put("passedCount", 0);
        contradictory.put("securityRedTeamFailures", 0);
        assertInvalid(contradictory);
        ObjectNode pending = contradictory.deepCopy().put("status", "pending");
        assertInvalid(pending);
        pending.putNull("securityRedTeamFailures");
        assertInvalid(pending);
        pending.put("securityRedTeamFailures", 100);
        assertFalse(decode(pending, baseline()).passed());
    }

    @Test
    void missingIndependentBaselineCannotSelfCertifyRelease() throws Exception {
        ObjectNode fixture = readyFixture();
        fixture.remove("baselineEvaluationRunId");
        fixture.remove("baselineReportHash");
        EvaluationReport archived = decode(fixture, null);
        assertFalse(archived.passed());
        assertEquals(105.0, archived.metrics().get("cost_usd_total"));
        assertNull(archived.thresholds().get("cost_usd_total"));
        assertNull(EvaluationReportArtifactDecoder.baselineEvaluationRunId(bytes(fixture)));
    }

    @Test
    void baselineReferenceMustMatchIdHashModelAndNotReferToItself() throws Exception {
        ObjectNode id = readyFixture();
        id.put("baselineEvaluationRunId", "different-baseline");
        assertInvalid(id);
        ObjectNode hash = readyFixture();
        hash.put("baselineReportHash", "sha256:" + "c".repeat(64));
        assertInvalid(hash);
        ObjectNode model = readyFixture();
        model.put("modelVersion", "different-model");
        assertInvalid(model);
        ObjectNode self = readyFixture();
        self.put("baselineEvaluationRunId", self.path("evaluationRunId").textValue());
        assertInvalid(self);
        assertEquals("synthetic-baseline-v1", EvaluationReportArtifactDecoder.baselineEvaluationRunId(bytes(readyFixture())));
    }

    @Test
    void relativeBudgetUsesTheStoredBaselineRatherThanUploadedNumber() throws Exception {
        ObjectNode fixture = readyFixture();
        ObjectNode cost = metric(fixture, "cost_usd_total");
        cost.put("baselineValue", 1_000_000).put("value", 121);
        assertInvalid(fixture);
        EvaluationReport missingUsage = new EvaluationReport("synthetic-baseline-v1", BASELINE_HASH, "v1.1",
                "synthetic-model-v1", "synthetic-data-v1", "synthetic-knowledge-v1", "synthetic-index-v1",
                924, false, true, Instant.parse("2026-10-09T00:00:00Z"), Map.of("cost_usd_total", 100.0), Map.of());
        byte[] bytes = bytes(readyFixture());
        assertThrows(IllegalArgumentException.class,
                () -> decoder.decode(bytes, EvaluationReportArtifactDecoder.hash(bytes), missingUsage));
    }

    @Test
    void safetyFailuresCannotBeOverriddenByReadyOrPassed() throws Exception {
        ObjectNode ready = readyFixture();
        ready.put("securityRedTeamFailures", 1).put("passed", true);
        assertInvalid(ready);
        ObjectNode blocked = readyFixture();
        blocked.put("status", "blocked").put("securityRedTeamFailures", 1);
        assertFalse(decode(blocked, baseline()).passed());
    }

    @Test
    void placeholderMetadataAndFalseSourceProvenanceAreRejected() throws Exception {
        ObjectNode placeholder = readyFixture();
        placeholder.put("modelVersion", "set-at-run");
        assertInvalid(placeholder);
        ObjectNode hash = readyFixture();
        hash.put("manifestSha256", "not-a-hash");
        assertInvalid(hash);
        ObjectNode dataset = readyFixture();
        suite(dataset, "rag-v1").put("datasetFile", "datasets/other.jsonl");
        assertInvalid(dataset);
        ObjectNode time = readyFixture();
        time.put("generatedAt", "tomorrow");
        assertInvalid(time);
    }

    @Test
    void duplicateJsonKeysAndTrailingTokensAreRejectedWithoutMutatingCallerMapper() throws Exception {
        byte[] fixture = bytes(readyFixture());
        String json = new String(fixture, StandardCharsets.UTF_8);
        byte[] duplicate = ("{\"status\":\"blocked\"," + json.substring(1)).getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> decoder.decode(duplicate, EvaluationReportArtifactDecoder.hash(duplicate), baseline()));
        assertThrows(IllegalArgumentException.class, () -> EvaluationReportArtifactDecoder.baselineEvaluationRunId(duplicate));
        byte[] trailing = (json + " {}").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> decoder.decode(trailing, EvaluationReportArtifactDecoder.hash(trailing), baseline()));
        assertFalse(mapper.isEnabled(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION));
    }

    @Test
    void rejectsOversizeEmptyNonUtf8AndN6bEvidence() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> decoder.decode(new byte[EvaluationReportArtifactDecoder.MAX_ARTIFACT_BYTES + 1], BASELINE_HASH));
        assertThrows(IllegalArgumentException.class, () -> EvaluationReportArtifactDecoder.hash(new byte[0]));
        byte[] invalidUtf8 = {(byte) 0xc3, (byte) 0x28};
        assertThrows(IllegalArgumentException.class,
                () -> decoder.decode(invalidUtf8, EvaluationReportArtifactDecoder.hash(invalidUtf8)));
        ObjectNode fixture = readyFixture();
        metric(fixture, "correction_closure_rate").put("status", "ready");
        assertInvalid(fixture);
    }

    private void assertInvalid(ObjectNode fixture) throws IOException {
        byte[] bytes = bytes(fixture);
        assertThrows(IllegalArgumentException.class,
                () -> decoder.decode(bytes, EvaluationReportArtifactDecoder.hash(bytes), baseline()));
    }

    private EvaluationReport decode(ObjectNode fixture, EvaluationReport baseline) throws IOException {
        byte[] bytes = bytes(fixture);
        return decoder.decode(bytes, EvaluationReportArtifactDecoder.hash(bytes), baseline);
    }

    private static byte[] bytes(ObjectNode fixture) throws IOException { return mapper.writeValueAsBytes(fixture); }

    private static List<String> ratioMetricIds() {
        return List.of("fact_task_success_rate", "evidence_task_success_rate", "rules_task_success_rate",
                "no_answer_handling_rate", "deterministic_calculation_accuracy", "rag_recall_at_5",
                "citation_correctness", "groundedness", "tool_selection_parameter_accuracy");
    }

    private EvaluationReport baseline() {
        return baselineReport();
    }

    static EvaluationReport baselineReport() {
        return new EvaluationReport("synthetic-baseline-v1", BASELINE_HASH, "v1.1", "synthetic-model-v1",
                "synthetic-data-v1", "synthetic-knowledge-v1", "synthetic-index-v1", 924, false, true,
                Instant.parse("2026-10-09T00:00:00Z"),
                Map.of("tokens_consumed_total", 100.0, "cost_usd_total", 100.0), Map.of());
    }

    static byte[] readyArtifact(String runId, EvaluationReport baseline) throws IOException {
        ObjectNode fixture = readyFixture();
        fixture.put("evaluationRunId", runId);
        if (baseline == null) {
            fixture.remove("baselineEvaluationRunId");
            fixture.remove("baselineReportHash");
        } else {
            fixture.put("baselineEvaluationRunId", baseline.evaluationRunId())
                    .put("baselineReportHash", baseline.reportHash()).put("modelVersion", baseline.modelVersion());
        }
        return bytes(fixture);
    }

    /** Builds synthetic test observations from server rules without relaxing any minima. */
    private static ObjectNode readyFixture() throws IOException {
        JsonNode manifest = resource("/evaluation/v1.1/manifest.json");
        JsonNode dictionary = resource("/evaluation/v1.1/metric-dictionary.json");
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", "v1.1").put("suiteVersion", "v1.1").put("producer", "run-v1.1-eval")
                .put("status", "ready").put("generatedAt", "2026-10-10T00:00:00Z").put("redacted", true)
                .put("evaluationRunId", "synthetic-decoder-contract-v1").put("codeCommit", "c".repeat(40))
                .put("modelVersion", "synthetic-model-v1").put("promptVersion", "synthetic-prompt-v1")
                .put("dataReleaseId", "synthetic-data-v1").put("knowledgeReleaseId", "synthetic-knowledge-v1")
                .put("indexReleaseId", "synthetic-index-v1").put("environment", "synthetic-unit-test")
                .put("manifest", "evaluation/v1.1/manifest.ready.json").put("manifestSha256", SOURCE_HASH)
                .put("dictionary", "evaluation/v1.1/metric-dictionary.json").put("dictionarySha256", SOURCE_HASH)
                .put("securityRedTeamFailures", 0).put("baselineEvaluationRunId", "synthetic-baseline-v1")
                .put("baselineReportHash", BASELINE_HASH);
        root.putArray("errors");
        root.putArray("warnings").add("Synthetic unit-test fixture; not release evidence.");
        int aggregateCount = 0;
        ArrayNode suites = root.putArray("suites");
        for (JsonNode rule : manifest.path("suites")) {
            int count = rule.path("minimumCases").intValue();
            aggregateCount += count;
            ObjectNode suite = suites.addObject();
            suite.put("suiteId", rule.path("suiteId").textValue()).put("kind", rule.path("kind").textValue())
                    .put("status", "ready").put("datasetFile", rule.path("file").textValue())
                    .put("datasetSha256", SOURCE_HASH).put("datasetCaseCount", count)
                    .put("sampleCount", count).put("passedCount", count);
            suite.putArray("errors");
        }
        root.put("sampleCount", aggregateCount).put("datasetCaseCount", aggregateCount);
        ArrayNode results = root.putArray("results");
        for (JsonNode rule : dictionary.path("metrics")) {
            String phase = rule.path("evaluationPhase").asText("n6a");
            ObjectNode metric = results.addObject();
            metric.put("metricId", rule.path("metricId").textValue()).put("threshold", rule.path("alertThreshold").textValue())
                    .put("thresholdVersion", rule.path("thresholdVersion").textValue()).put("evaluationPhase", phase)
                    .put("blocking", !rule.has("blocking") || rule.path("blocking").booleanValue())
                    .put("source", "synthetic-contract-fixture").put("passed", !phase.equals("n6b"))
                    .put("status", phase.equals("n6b") ? "not_applicable" : "ready")
                    .put("sampleCount", phase.equals("n6b") ? 0 : rule.path("minRequests").intValue());
            if (phase.equals("n6b")) metric.putNull("value");
            else metric.put("value", rule.path("type").asText().equals("cost") ? 105.0 : 1.0);
        }
        return root;
    }

    private static JsonNode resource(String name) throws IOException {
        try (InputStream stream = EvaluationReportArtifactDecoderTest.class.getResourceAsStream(name)) {
            assertNotNull(stream, "Packaged server rule resource: " + name);
            return mapper.readTree(stream);
        }
    }

    private ObjectNode metric(ObjectNode root, String id) {
        for (JsonNode item : root.path("results")) if (item.path("metricId").asText().equals(id)) return (ObjectNode) item;
        throw new AssertionError("Missing fixture metric: " + id);
    }

    private ObjectNode suite(ObjectNode root, String id) {
        for (JsonNode item : root.path("suites")) if (item.path("suiteId").asText().equals(id)) return (ObjectNode) item;
        throw new AssertionError("Missing fixture suite: " + id);
    }
}
