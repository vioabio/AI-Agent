package com.vio.vioaiagent.observability;

import io.micrometer.observation.Observation;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.*;
import io.opentelemetry.api.common.*;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.export.*;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/** Local-only, opt-in domain tracing. No global SDK or HTTP/client instrumentation. */
@Configuration(proxyBeanMethods = false)
@Profile("local & !prod & !demo")
@ConditionalOnProperty(name = "vio.observability.tracing.enabled", havingValue = "true")
public class AgentTracingConfiguration {
    @Bean(destroyMethod = "close")
    AgentTracingRuntime agentTracingRuntime(
            @Value("${vio.observability.tracing.endpoint:http://127.0.0.1:4318/v1/traces}") String endpoint,
            @Value("${vio.observability.tracing.sample-rate:0.1}") double sampleRate,
            @Value("${management.tracing.enabled:false}") boolean automaticTracing) {
        if (automaticTracing) throw new IllegalArgumentException("Disable automatic tracing before enabling domain tracing");
        URI target = localEndpoint(endpoint);
        validateSampling(sampleRate);
        var exporter = OtlpHttpSpanExporter.builder().setEndpoint(target.toString())
                .setTimeout(Duration.ofMillis(500)).setConnectTimeout(Duration.ofMillis(500)).build();
        return runtime(exporter, sampleRate);
    }

    @Bean
    ObservationRegistryCustomizer<io.micrometer.observation.ObservationRegistry> agentTracingCustomizer(AgentTracingRuntime runtime) {
        return registry -> registry.observationConfig().observationHandler(runtime.handler());
    }

    static URI localEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if (!Set.of("http", "https").contains(uri.getScheme())
                    || !Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !"/v1/traces".equals(uri.getPath()) || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Tracing requires a loopback OTLP HTTP /v1/traces endpoint"); }
    }
    static void validateSampling(double rate) {
        if (!Double.isFinite(rate) || rate < 0 || rate > 1) throw new IllegalArgumentException("Tracing sample rate must be within 0..1");
    }

    static AgentTracingRuntime runtime(SpanExporter exporter, double sampleRate) {
        validateSampling(sampleRate);
        var processor = BatchSpanProcessor.builder(exporter).setMaxQueueSize(256).setMaxExportBatchSize(64)
                .setScheduleDelay(Duration.ofMillis(500)).setExporterTimeout(Duration.ofSeconds(1)).build();
        var provider = SdkTracerProvider.builder()
                .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "vio-agent-local")))
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(sampleRate)))
                .setSpanLimits(SpanLimits.builder().setMaxNumberOfAttributes(16).setMaxAttributeValueLength(256).setMaxNumberOfEvents(4).build())
                .addSpanProcessor(processor).build();
        var sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        var current = new OtelCurrentTraceContext();
        var tracer = new OtelTracer(sdk.getTracer("vio-agent-domain"), current, event -> { }, new OtelBaggageManager(current, List.of(), List.of()));
        var handler = new DefaultTracingObservationHandler(tracer) {
            @Override public boolean supportsContext(Observation.Context context) {
                return Set.of("agent.run.execute", "agent.model.call", "agent.model.stream", "agent.tool.callback").contains(context.getName()) && super.supportsContext(context);
            }
        };
        return new AgentTracingRuntime(provider, handler);
    }

    record AgentTracingRuntime(SdkTracerProvider provider, DefaultTracingObservationHandler handler) implements AutoCloseable {
        @Override public void close() { provider.shutdown().join(2, java.util.concurrent.TimeUnit.SECONDS); }
    }
}
