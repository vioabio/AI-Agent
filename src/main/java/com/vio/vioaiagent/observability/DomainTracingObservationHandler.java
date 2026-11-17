package com.vio.vioaiagent.observability;

import io.micrometer.observation.Observation;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import java.util.Set;

/** Domain SDK context and sampling stay independent from framework tracing handlers. */
public final class DomainTracingObservationHandler extends DefaultTracingObservationHandler {
    private static final Object DOMAIN_TRACING_CONTEXT = new Object();
    DomainTracingObservationHandler(Tracer tracer) { super(tracer); }
    @Override public void onStart(Observation.Context context) {
        Observation.Context parent = context.getParentObservation() == null ? null
                : context.getParentObservation().getContextView().get(DOMAIN_TRACING_CONTEXT);
        if (parent != null && super.getTracingContext(parent).getSpan() != null) {
            super.onStart(context);
        } else {
            // Apply this SDK's sampling policy to a new domain root on HTTP threads too.
            try (var ignored = io.opentelemetry.context.Context.root().makeCurrent()) {
                super.onStart(context);
            }
        }
    }
    @Override public TracingContext getTracingContext(Observation.Context context) {
        Observation.Context isolated = context.computeIfAbsent(DOMAIN_TRACING_CONTEXT, key -> new Observation.Context());
        return super.getTracingContext(isolated);
    }
    @Override public io.micrometer.tracing.Span getParentSpan(Observation.ContextView context) {
        Observation.Context isolated = context.get(DOMAIN_TRACING_CONTEXT);
        if (isolated == null && context.getParentObservation() != null) {
            isolated = context.getParentObservation().getContextView().get(DOMAIN_TRACING_CONTEXT);
        }
        TracingContext tracing = isolated == null ? null : super.getTracingContext(isolated);
        return tracing != null && tracing.getSpan() != null ? tracing.getSpan() : getTracer().currentSpan();
    }
    @Override public boolean supportsContext(Observation.Context context) {
        String name = context.getName();
        return name != null && Set.of("agent.run.admit", "agent.run.dispatch", "agent.run.execute", "agent.run.authorize", "agent.run.commit",
                "agent.model.call", "agent.model.stream", "agent.tool.callback", "agent.tool.dispatch").contains(name) && super.supportsContext(context);
    }
}
