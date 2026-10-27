package com.vio.vioaiagent.tools.governance;

import com.vio.vioaiagent.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ToolResultCacheTest {
    @Test void differentQueriesInOneRunExecuteSeparatelyAndCanonicalDuplicatesReuseOnlyTheirOwnResult() {
        try (var fixture = new Fixture()) {
            var events = new ArrayList<ToolGovernanceEvent>();
            var context = new ToolExecutionContext("owner", "run", Set.of("operator"), "trace", "same-key", events::add);
            fixture.register("1");
            var first = fixture.gateway.execute("cacheRead", "{\"query\":\"empty\",\"scope\":{\"x\":1,\"y\":2}}", context);
            var second = fixture.gateway.execute("cacheRead", "{\"query\":\"found\",\"scope\":{\"x\":1,\"y\":2}}", context);
            var repeat = fixture.gateway.execute("cacheRead", "{ \"scope\": {\"y\":2,\"x\":1}, \"query\":\"found\" }", context);
            assertEquals(ToolGovernanceResult.Status.EXECUTED, first.status());
            assertEquals(ToolGovernanceResult.Status.EXECUTED, second.status());
            assertEquals(ToolGovernanceResult.Status.DEDUPLICATED, repeat.status());
            assertNotEquals(first.output(), second.output()); assertEquals(second.output(), repeat.output());
            assertEquals(2, fixture.calls.get());
            var cache = events.stream().filter(event -> event.eventType().equals("tool.deduplicated")).findFirst().orElseThrow();
            assertEquals("SUCCEEDED", cache.attributes().get("status")); assertEquals(true, cache.attributes().get("cached"));
            assertTrue(cache.attributes().get("parameterDigest").toString().matches("[a-f0-9]{64}"));
            assertFalse(cache.attributes().toString().contains("found"));
        }
    }
    @Test void ownerRunAndToolVersionDoNotShareCachedReadResults() {
        try (var fixture = new Fixture()) {
            fixture.register("1");
            for (String[] scope : List.of(new String[]{"a","r"}, new String[]{"b","r"}, new String[]{"a","s"})) {
                assertEquals(ToolGovernanceResult.Status.EXECUTED, fixture.gateway.execute("cacheRead", "{}",
                        new ToolExecutionContext(scope[0],scope[1],Set.of("operator"),"t","key",null)).status());
            }
            fixture.register("2");
            assertEquals(ToolGovernanceResult.Status.EXECUTED, fixture.gateway.execute("cacheRead", "{}",
                    new ToolExecutionContext("a","r",Set.of("operator"),"t","key",null)).status());
            assertEquals(4,fixture.calls.get());
        }
    }
    @Test void cacheHitStillChecksCurrentRoleAndSchema() {
        try (var fixture = new Fixture()) {
            fixture.register("1");
            var owner = new ToolExecutionContext("a","r",Set.of("operator"),"t","key",null);
            assertEquals(ToolGovernanceResult.Status.EXECUTED, fixture.gateway.execute("cacheRead", "{}", owner).status());
            var denied = new ToolExecutionContext("a","r",Set.of("trainer"),"t","key",null);
            assertEquals(ToolGovernanceResult.Status.REJECTED, fixture.gateway.execute("cacheRead", "{}", denied).status());
            assertEquals(ToolGovernanceResult.Status.REJECTED, fixture.gateway.execute("cacheRead", "not-json", owner).status());
            assertEquals(1,fixture.calls.get());
        }
    }
    @Test void sameWriteKeyWithChangedParametersIsRejectedWithoutAnotherApprovalOrCallback() {
        try (var fixture = new Fixture()) {
            fixture.gateway.wrapMcp(fixture.callback("cacheWrite"),new ToolCapabilityCard("cacheWrite","1",ToolCapabilityCard.RiskLevel.LOW,
                    ToolCapabilityCard.SideEffect.WRITE,true,2000,List.of("operator"),"{}","{}"));
            var context = new ToolExecutionContext("a","r",Set.of("operator"),"t","write-key",null);
            var pending = fixture.gateway.execute("cacheWrite","{\"value\":1}",context);
            assertEquals(ToolGovernanceResult.Status.WAITING_APPROVAL,pending.status());
            fixture.hitl.decide(pending.approvalId(),"a","r",true,"fixture approval");
            assertEquals(ToolGovernanceResult.Status.EXECUTED,fixture.gateway.execute("cacheWrite","{\"value\":1}",context).status());
            assertEquals(ToolGovernanceResult.Status.DEDUPLICATED,fixture.gateway.execute("cacheWrite","{\"value\":1}",context).status());
            var changed=fixture.gateway.execute("cacheWrite","{\"value\":2}",context);
            assertEquals(ToolGovernanceResult.Status.REJECTED,changed.status());
            assertTrue(changed.output().contains("绑定其他参数"));assertEquals(1,fixture.calls.get());
        }
    }
    private static final class Fixture implements AutoCloseable {
        final AtomicInteger calls=new AtomicInteger(); final HitlManager hitl=new HitlManager();
        final ParameterMasker masker=new ParameterMasker();
        final ToolExecutionGateway gateway=new ToolExecutionGateway(hitl,new PathGuard(List.of(System.getProperty("user.dir")+"/tmp")),
                new CommandGuard(List.of()),masker,new AuditLogger(masker));
        ToolCallback callback(String name) { return new ToolCallback() {
            public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name(name).description("fixture").inputSchema("{}").build();}
            public String call(String input){calls.incrementAndGet();return input;}
        }; }
        void register(String version) { gateway.wrapMcp(callback("cacheRead"),new ToolCapabilityCard("cacheRead",version,ToolCapabilityCard.RiskLevel.LOW,
                ToolCapabilityCard.SideEffect.NONE,true,2000,List.of("operator"),"{}","{}")); }
        public void close(){gateway.shutdown();}
    }
}
