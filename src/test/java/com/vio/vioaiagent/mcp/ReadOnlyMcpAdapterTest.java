package com.vio.vioaiagent.mcp;

import com.vio.vioaiagent.tools.governance.*;
import com.vio.vioaiagent.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ReadOnlyMcpAdapterTest {
    final com.vio.vioaiagent.pokemon.reference.LocalPokeApiRepository repository = mock(com.vio.vioaiagent.pokemon.reference.LocalPokeApiRepository.class);
    final ToolCallback[] callbacks = org.springframework.ai.support.ToolCallbacks.from(
            new com.vio.vioaiagent.pokemon.reference.PokemonReferenceTools(repository),
            new com.vio.vioaiagent.pokemon.reference.PokemonBuildCheckController.Tools(mock(com.vio.vioaiagent.pokemon.reference.LocalBuildCheckService.class), null),
            new com.vio.vioaiagent.pokemon.engine.DamageEngineController.Tools(mock(com.vio.vioaiagent.pokemon.engine.LocalDamageEngine.class), null));
    Map<String,Object> arguments() { return Map.of("query", "7", "dataReleaseId", "pokeapi-reference-v1"); }
    ToolGovernanceResult success(String output) { return new ToolGovernanceResult(ToolGovernanceResult.Status.EXECUTED,"lookupPokemonReference",output,null,"event",0,1); }
    @Test void createsProcessIdentityAndCallIdsWithoutAcceptingClientClaims() {
        var gateway = mock(ToolExecutionGateway.class);
        var contexts = new ArrayList<ToolExecutionContext>();
        when(gateway.execute(anyString(), anyString(), any(ToolExecutionContext.class))).thenAnswer(call -> { contexts.add(call.getArgument(2)); return success("{}"); });
        var adapter = new ReadOnlyMcpAdapter(callbacks, gateway);
        assertFalse(adapter.invoke("lookupPokemonReference", arguments()).block().isError());
        assertFalse(adapter.invoke("lookupPokemonReference", arguments()).block().isError());
        assertEquals(2, contexts.size()); assertEquals(contexts.getFirst().userId(), contexts.getLast().userId());
        assertTrue(contexts.getFirst().userId().startsWith("mcp-public-")); assertEquals(Set.of("trainer"), contexts.getFirst().roles());
        assertNotEquals(contexts.getFirst().runId(), contexts.getLast().runId()); assertNull(contexts.getFirst().tokenBinding());
        assertTrue(adapter.invoke("lookupPokemonReference", Map.of("query","7","dataReleaseId","pokeapi-reference-v1","userId","victim")).block().isError());
        assertTrue(adapter.invoke("readCurrentTeam", Map.of()).block().isError()); assertEquals(2, contexts.size());
    }
    @Test void boundsAdmissionAndRefusesOversizedResultsWithoutReturningPartialSuccess() throws Exception {
        var gateway = mock(ToolExecutionGateway.class); var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        when(gateway.execute(anyString(), anyString(), any(ToolExecutionContext.class))).thenAnswer(call -> { entered.countDown(); release.await(); return success("{}"); });
        var adapter = new ReadOnlyMcpAdapter(callbacks, gateway);
        var a = adapter.invoke("lookupPokemonReference", arguments()).subscribe(); var b = adapter.invoke("lookupPokemonReference", arguments()).subscribe();
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            var busy = adapter.invoke("lookupPokemonReference", arguments()).block(Duration.ofSeconds(1)); assertTrue(busy.isError());
            assertEquals("MCP_BUSY", ((io.modelcontextprotocol.spec.McpSchema.TextContent)busy.content().getFirst()).text());
        } finally { release.countDown(); a.dispose(); b.dispose(); }
        var largeGateway = mock(ToolExecutionGateway.class);
        when(largeGateway.execute(anyString(),anyString(),any(ToolExecutionContext.class))).thenReturn(success("x".repeat(65_537)));
        var result = new ReadOnlyMcpAdapter(callbacks,largeGateway).invoke("lookupPokemonReference",arguments()).block();
        assertTrue(result.isError()); assertTrue(((io.modelcontextprotocol.spec.McpSchema.TextContent)result.content().getFirst()).text().length()<100);
    }
    @Test void gatewayStillValidatesTypesAndTimeoutDoesNotStartARetry() throws Exception {
        var hitl = new HitlManager();
        var gateway = new ToolExecutionGateway(hitl,null,null,null,ToolAuditSink.noop(),null,null,false,new ToolExecutionProperties(2,0,100));
        try {
            var adapter = new ReadOnlyMcpAdapter(callbacks,gateway);
            assertTrue(adapter.invoke("lookupPokemonReference",Map.of("query",7,"dataReleaseId","pokeapi-reference-v1")).block().isError());
            verifyNoInteractions(repository);
            var calls = new AtomicInteger();
            when(repository.detail(anyString(),anyString())).thenAnswer(call -> { calls.incrementAndGet(); Thread.sleep(5000); return null; });
            var callback = Arrays.stream(callbacks).filter(c->c.getToolDefinition().name().equals("lookupPokemonReference")).findFirst().orElseThrow();
            var card = ToolCapabilityCatalog.find("lookupPokemonReference").orElseThrow();
            gateway.register(callback,new ToolCapabilityCard(card.name(),card.version(),card.riskLevel(),card.sideEffect(),true,50,card.allowedRoles(),card.inputSchema(),card.outputSchema()));
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertTrue(adapter.invoke("lookupPokemonReference",arguments()).block().isError()));
            assertEquals(1,calls.get());
        } finally { gateway.shutdown(); hitl.shutdown(); }
    }
}
