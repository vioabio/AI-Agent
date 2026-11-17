package com.vio.vioaiagent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit local SDK probe. Timing is descriptive and has no pass/fail threshold. */
@EnabledIfSystemProperty(named="vio.tracing.probe", matches="true")
class AgentTracingOverheadTest {
    @Test void recordsRepeatedSdkWorkloadsWithSamplingAndFailedExport() throws Exception {
        Path artifacts = Path.of("artifacts").toRealPath();
        Path output = Path.of(System.getProperty("vio.tracing.probe.output")).toAbsolutePath().normalize();
        assertEquals(artifacts,output.getParent().toRealPath());
        assertFalse(Files.exists(output));
        var records = new ArrayList<Map<String,Object>>();
        for (int repeat = 0; repeat < 3; repeat++) {
            // Rotate order to reduce systematic warmup/order bias.
            var modes = new ArrayList<>(List.of("DISABLED","SAMPLE_ZERO","SAMPLE_QUARTER","SAMPLE_ALL","EXPORT_FAILURE"));
            Collections.rotate(modes,repeat);
            for (String mode : modes) records.add(measure(mode,repeat));
        }
        var report = Map.of("schemaVersion","domain-tracing-overhead-v1",
                "scope","LOCAL_SDK_FIXED_WORKLOAD_NANOSECONDS","releaseDecision","NOT_EVALUATED",
                "timingThresholdApplied",false,"warmupPerMode",32,"measuredRunsPerMode",64,
                "environment",Map.of("javaVersion",System.getProperty("java.version"),"os",System.getProperty("os.name")),
                "records",records);
        Files.writeString(output,new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report),StandardOpenOption.CREATE_NEW);
    }

    private Map<String,Object> measure(String mode,int repeat) {
        var exported = new AtomicInteger();
        var exportAttempts = new AtomicInteger();
        SpanExporter exporter = new SpanExporter() {
            public CompletableResultCode export(Collection<SpanData> spans) {
                exportAttempts.incrementAndGet();
                if (mode.equals("EXPORT_FAILURE")) return CompletableResultCode.ofFailure();
                exported.addAndGet(spans.size()); return CompletableResultCode.ofSuccess();
            }
            public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };
        double rate = mode.equals("SAMPLE_ZERO") ? 0 : mode.equals("SAMPLE_QUARTER") ? 0.25 : 1;
        try (var runtime = mode.equals("DISABLED") ? null : AgentTracingConfiguration.runtime(exporter,rate)) {
            var registry = ObservationRegistry.create();
            if (runtime != null) registry.observationConfig().observationHandler(runtime.handler());
            var runs = new AgentRunObservation(registry);
            var models = new AgentModelObservation(registry);
            var calls = new AtomicInteger();
            var durations = new ArrayList<Long>();
            for (int i = 0; i < 96; i++) {
                long start = System.nanoTime();
                assertEquals(42,runs.execute("run-probe-" + i,() -> {
                    calls.incrementAndGet();
                    runs.phase(AgentRunObservation.Phase.AUTHORIZE,() -> true);
                    return models.call(() -> 42);
                }));
                long elapsed = System.nanoTime() - start;
                if (i >= 32) durations.add(elapsed);
                assertNull(registry.getCurrentObservation());
                // Drain outside the measured interval to avoid bounded queue drops.
                if (runtime != null && i % 16 == 15) assertTrue(runtime.provider().forceFlush().join(2,TimeUnit.SECONDS).isSuccess());
            }
            assertEquals(96,calls.get());
            if (Set.of("DISABLED","SAMPLE_ZERO","EXPORT_FAILURE").contains(mode)) assertEquals(0,exported.get());
            if (mode.equals("SAMPLE_ALL")) assertEquals(288,exported.get());
            if (mode.equals("EXPORT_FAILURE")) assertTrue(exportAttempts.get() > 0);
            var sorted = durations.stream().sorted().toList();
            return Map.of("mode",mode,"repeat",repeat,"sampleRate",rate,"businessCalls",calls.get(),
                    "exportedSpansIncludingWarmup",exported.get(),"exportAttempts",exportAttempts.get(),
                    "medianNs",sorted.get(31),"p95Ns",sorted.get(60),"durationsNs",durations);
        }
    }
}
