package com.vio.vioaiagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** A second actual STDIO client implementation, distinct from the handwritten Node probe. */
class SdkMcpCompatibilityTest {
    @TempDir Path directory;
    /** Observe SDK-issued IDs without replacing its transport or session implementation. */
    static final class ObservedTransport extends StdioClientTransport {
        final java.util.concurrent.atomic.AtomicReference<Object> toolId=new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.ConcurrentMap<Object,Integer> replies=new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.ConcurrentMap<String,Object> queryIds=new java.util.concurrent.ConcurrentHashMap<>();
        ObservedTransport(ServerParameters parameters){super(parameters);}
        @Override public reactor.core.publisher.Mono<Void> sendMessage(McpSchema.JSONRPCMessage message){
            if(message instanceof McpSchema.JSONRPCRequest request && request.method().equals("tools/call")) {
                toolId.set(request.id());
                String query=new ObjectMapper().valueToTree(request.params()).path("arguments").path("query").asText(null);
                if (query!=null) queryIds.put(query,request.id());
            }
            return super.sendMessage(message);
        }
        @Override public reactor.core.publisher.Mono<Void> connect(java.util.function.Function<reactor.core.publisher.Mono<McpSchema.JSONRPCMessage>,reactor.core.publisher.Mono<McpSchema.JSONRPCMessage>> handler){
            return super.connect(incoming->handler.apply(incoming.doOnNext(message->{
                if(message instanceof McpSchema.JSONRPCResponse response && response.id()!=null)replies.merge(response.id(),1,Integer::sum);
            })));
        }
    }
    private void awaitMarker(String name) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(!java.nio.file.Files.exists(directory.resolve(name))&&System.nanoTime()<deadline)Thread.sleep(25);
        assertTrue(java.nio.file.Files.exists(directory.resolve(name)),"Missing fixture marker: "+name);
    }
    private String awaitFirstStarted(String first,String second) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(System.nanoTime()<deadline) {
            if(java.nio.file.Files.exists(directory.resolve(first+"-started")))return first;
            if(java.nio.file.Files.exists(directory.resolve(second+"-started")))return second;
            Thread.sleep(25);
        }
        throw new AssertionError("Neither queued request reached the controlled upstream");
    }
    @Test void concurrentCancellationAcknowledgesOnlyItsRequestAcrossFiveRecoveryRounds() throws Exception {
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var parameters=ServerParameters.builder(executable).args(List.of("-cp",classpath,McpStdioFixtureMain.class.getName(),directory.toString())).build();
        var transport=new ObservedTransport(parameters);transport.setStdErrorHandler(ignored->{});
        var client=McpClient.async(transport).requestTimeout(Duration.ofSeconds(30)).build();
        var records=new ArrayList<Map<String,Object>>();
        try {
            client.initialize().block(Duration.ofSeconds(15));
            for (int round=0;round<5;round++) {
                String first="slow-"+(round*2),second="slow-"+(round*2+1);
                var firstCall=client.callTool(new McpSchema.CallToolRequest("lookupPokemonReference",Map.of("query",first,"dataReleaseId","pokeapi-reference-v1"))).toFuture();
                var secondCall=client.callTool(new McpSchema.CallToolRequest("lookupPokemonReference",Map.of("query",second,"dataReleaseId","pokeapi-reference-v1"))).toFuture();
                // Public repository/cache reads are serialized. Cancel whichever
                // request reached the upstream, then observe the queued peer start.
                String active=awaitFirstStarted(first,second);
                String queued=active.equals(first)?second:first;
                var activeCall=active.equals(first)?firstCall:secondCall;
                var queuedCall=active.equals(first)?secondCall:firstCall;
                Object firstId=transport.queryIds.get(active),secondId=transport.queryIds.get(queued);
                assertNotNull(firstId);assertNotNull(secondId);assertNotEquals(firstId,secondId);
                transport.sendMessage(new McpSchema.JSONRPCNotification("2.0","notifications/cancelled",Map.of("requestId",firstId))).block(Duration.ofSeconds(3));
                var firstResult=activeCall.get(5,java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(firstResult.isError());
                assertEquals("MCP_REQUEST_CANCELLED",((McpSchema.TextContent)firstResult.content().getFirst()).text());
                awaitMarker(active+"-stopped");
                awaitMarker(queued+"-started");
                for(int replay=0;replay<3;replay++)
                    transport.sendMessage(new McpSchema.JSONRPCNotification("2.0","notifications/cancelled",Map.of("requestId",firstId))).block(Duration.ofSeconds(3));
                assertNotNull(client.ping().block(Duration.ofSeconds(3)));
                assertFalse(queuedCall.isDone());assertFalse(java.nio.file.Files.exists(directory.resolve(queued+"-stopped")));
                transport.sendMessage(new McpSchema.JSONRPCNotification("2.0","notifications/cancelled",Map.of("requestId",secondId))).block(Duration.ofSeconds(3));
                var secondResult=queuedCall.get(5,java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(secondResult.isError());
                assertEquals("MCP_REQUEST_CANCELLED",((McpSchema.TextContent)secondResult.content().getFirst()).text());
                awaitMarker(queued+"-stopped");
                var recovered=client.callTool(new McpSchema.CallToolRequest("lookupPokemonReference",Map.of("query","7","dataReleaseId","pokeapi-reference-v1"))).block(Duration.ofSeconds(5));
                assertNotNull(recovered);assertFalse(recovered.isError());
                assertEquals(44,new ObjectMapper().readTree(((McpSchema.TextContent)recovered.content().getFirst()).text()).path("fact").path("hp").asInt());
                assertEquals(1,transport.replies.get(firstId));assertEquals(1,transport.replies.get(secondId));
                records.add(Map.of("round",round+1,"firstRequestId",firstId,"secondRequestId",secondId,
                        "firstAcknowledgement",((McpSchema.TextContent)firstResult.content().getFirst()).text(),
                        "secondAcknowledgement",((McpSchema.TextContent)secondResult.content().getFirst()).text(),
                        "firstReplies",transport.replies.get(firstId),"secondReplies",transport.replies.get(secondId),
                        "bothWorkersStopped",true,"secondRequestSurvivedFirstCancellation",true,"recoveredFactHp",44));
            }
            assertNotNull(client.listTools().block(Duration.ofSeconds(3)));
        } finally {client.closeGracefully().block(Duration.ofSeconds(5));}
        Path output=java.nio.file.Files.createTempDirectory(Path.of("artifacts").toAbsolutePath(),"u6-mcp-concurrent-cancel-");
        java.nio.file.Files.writeString(output.resolve("report.json"),new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("scope","OFFICIAL_SDK_REAL_SERVER_OFFLINE_UPSTREAM_CONCURRENT_CANCEL",
                        "status","PASS","rounds",records,"webAgentPath","NOT_EXERCISED","durationClass","BOUNDED_PROBE")));
        System.out.println("MCP concurrent cancellation evidence: "+output.resolve("report.json"));
    }
    @Test void officialSdkTransportCancellationStopsWorkerAndPreservesConnection() throws Exception {
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var parameters=ServerParameters.builder(executable).args(List.of("-cp",classpath,McpStdioFixtureMain.class.getName(),directory.toString())).build();
        var transport=new ObservedTransport(parameters);transport.setStdErrorHandler(ignored->{});
        var client=McpClient.async(transport).requestTimeout(Duration.ofSeconds(15)).build();
        java.util.concurrent.CompletableFuture<McpSchema.CallToolResult> slow=null;
        try {
            assertEquals("2024-11-05",client.initialize().block(Duration.ofSeconds(15)).protocolVersion());
            slow=client.callTool(new McpSchema.CallToolRequest("lookupPokemonReference",Map.of("query","slow","dataReleaseId","pokeapi-reference-v1"))).toFuture();
            awaitMarker("slow-started");Object id=transport.toolId.get();assertNotNull(id);
            Object wrongType=id instanceof String?1L:String.valueOf(id);
            transport.sendMessage(new McpSchema.JSONRPCNotification("2.0","notifications/cancelled",Map.of("requestId",wrongType))).block(Duration.ofSeconds(3));
            assertNotNull(client.ping().block(Duration.ofSeconds(3)));
            assertFalse(slow.isDone(),"Wrong ID type cancelled another request");
            assertFalse(java.nio.file.Files.exists(directory.resolve("slow-stopped")));
            transport.sendMessage(new McpSchema.JSONRPCNotification("2.0","notifications/cancelled",Map.of("requestId",id))).block(Duration.ofSeconds(3));
            var cancelled=slow.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(cancelled.isError());assertEquals("MCP_REQUEST_CANCELLED",((McpSchema.TextContent)cancelled.content().getFirst()).text());
            awaitMarker("slow-stopped");
            // A repeated late cancellation must neither duplicate the reply nor cancel a new request.
            transport.sendMessage(new McpSchema.JSONRPCNotification("2.0","notifications/cancelled",Map.of("requestId",id))).block(Duration.ofSeconds(3));
            var recovered=client.callTool(new McpSchema.CallToolRequest("lookupPokemonReference",Map.of("query","7","dataReleaseId","pokeapi-reference-v1"))).block(Duration.ofSeconds(5));
            assertNotNull(recovered);assertFalse(recovered.isError());
            assertEquals(44,new ObjectMapper().readTree(((McpSchema.TextContent)recovered.content().getFirst()).text()).path("fact").path("hp").asInt());
            assertNotNull(client.ping().block(Duration.ofSeconds(3)));
            assertEquals(1,transport.replies.get(id));
        } finally {
            if(slow!=null&&!slow.isDone())slow.cancel(true);
            client.closeGracefully().block(Duration.ofSeconds(5));
        }
    }
    @Test void pinnedOfficialSdkDiscoversAndCallsAllThreePublicTools() throws Exception {
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var parameters=ServerParameters.builder(executable).args(List.of("-cp",classpath,McpStdioFixtureMain.class.getName(),directory.toString())).build();
        var transport=new StdioClientTransport(parameters);
        transport.setStdErrorHandler(ignored->{});
        var client=McpClient.sync(transport).requestTimeout(Duration.ofSeconds(12)).build();
        try {
            assertEquals("2024-11-05",client.initialize().protocolVersion());
            assertEquals(List.of("calculateBattleDamage","checkPokemonBuild","lookupPokemonReference"),client.listTools().tools().stream().map(McpSchema.Tool::name).sorted().toList());
            var json=new ObjectMapper();
            var fact=client.callTool(new McpSchema.CallToolRequest("lookupPokemonReference",Map.of("query","7","dataReleaseId","pokeapi-reference-v1")));
            assertFalse(fact.isError());assertEquals(44,json.readTree(((McpSchema.TextContent)fact.content().getFirst()).text()).path("fact").path("hp").asInt());
            var build=client.callTool(new McpSchema.CallToolRequest("checkPokemonBuild",Map.of("request",Map.of("pokemonId",7,"dataReleaseId","pokeapi-reference-v1","gameReleaseId","pokemon-scarlet-violet"))));
            assertFalse(build.isError());assertEquals("INCOMPLETE",json.readTree(((McpSchema.TextContent)build.content().getFirst()).text()).path("status").asText());
            var damage=client.callTool(new McpSchema.CallToolRequest("calculateBattleDamage",Map.of("request",Map.of("generation",9,"dataReleaseId","pokeapi-reference-v1","attacker",Map.of("species","squirtle"),"defender",Map.of("species","squirtle"),"move","tackle","field",Map.of("gameType","Singles")))));
            assertFalse(damage.isError());assertTrue(json.readTree(((McpSchema.TextContent)damage.content().getFirst()).text()).path("range").path("maximum").asInt()>0);
            assertTrue(client.callTool(new McpSchema.CallToolRequest("lookupPokemonReference",Map.of("query","7","dataReleaseId","wrong"))).isError());
            assertNotNull(client.ping());
        }finally{assertTrue(client.closeGracefully(),"SDK client did not close gracefully");}
    }
}
