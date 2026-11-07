package com.vio.vioaiagent.observability;

import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunAttemptTimingTest {
    @Test void readinessIsOncePerAttemptNotSdkTextAndFreezeRejectsLateUpdates() {
        long now=System.nanoTime();var timing=new RunAttemptTiming(now-20_000_000,now-10_000_000);
        var ledger=new ModelUsageLedger();ledger.responseTiming(timing);assertNull(((Map<?,?>)ledger.snapshot().get("responseTiming")).get("firstAnswerReadyMs"));
        assertNotNull(ledger.answerReady("BUFFERED"));assertNull(ledger.answerReady("DELTA"));
        var frozen=ledger.freeze();var value=(Map<?,?>)frozen.get("responseTiming");
        assertEquals("BUFFERED",value.get("answerPath"));assertEquals(false,value.get("deliveryMeasured"));
        assertEquals(10L,value.get("dispatchWaitMs"));assertNull(ledger.answerReady("DELTA"));assertSame(frozen,ledger.snapshot());
    }
    @Test void toolHistoryIsWhitelistedBoundedDeduplicatedAndFrozen() {
        var ledger=new ModelUsageLedger();
        var bypass=com.vio.vioaiagent.tools.governance.ToolAttemptTiming.bypass("CACHED_RESULT");
        for(int i=0;i<65;i++)ledger.toolTiming("event-"+i,"lookup",bypass);
        ledger.toolTiming("event-0","lookup",bypass);
        var frozen=ledger.freeze();assertEquals(64,((List<?>)frozen.get("toolTimings")).size());assertEquals(true,frozen.get("toolTimingsTruncated"));
        ledger.toolTiming("late","lookup",bypass);assertSame(frozen,ledger.snapshot());
        var other=new ModelUsageLedger();other.toolTiming("event","lookup",Map.of("secret","private"));assertFalse(other.freeze().toString().contains("private"));
    }
    @Test void prometheusSeparatesReadinessAndPartialCallbackSamplesFromLegacyTtft() {
        var registry=new SimpleMeterRegistry();
        try {
            @SuppressWarnings("unchecked") ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider=mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(registry);var metrics=new AgentMetrics(provider);
            metrics.recordPublicAnswerReady(20,"BUFFERED");metrics.recordToolAttemptTiming(Map.of("queueWaitMs",10L,"executionMs",15L,"callbackCompleted",false));
            assertEquals(1,registry.find("vio_run_answer_ready").tag("path","buffered").timer().count());
            assertEquals(1,registry.find("vio_tool_execution_observed").tag("callback_completed","false").timer().count());
            assertEquals(0,registry.find("vio_agent_ttft").timer().count());
        } finally {registry.close();}
    }
}
