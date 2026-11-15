package com.vio.vioaiagent.mcp.client;

import io.modelcontextprotocol.spec.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpCancellationTransportTest {
    @Test void asynchronousSdkCallbackPropagatesInterruptionAndRecovers(@org.junit.jupiter.api.io.TempDir java.nio.file.Path cache) throws Exception {
        String executable=java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var parameters=io.modelcontextprotocol.client.transport.ServerParameters.builder(executable).args(List.of("-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                com.vio.vioaiagent.mcp.McpStdioFixtureMain.class.getName(),cache.toString())).build();
        var delegate=new io.modelcontextprotocol.client.transport.StdioClientTransport(parameters);delegate.setStdErrorHandler(ignored->{});
        var transport=new McpCancellationTransport(delegate);
        var client=io.modelcontextprotocol.client.McpClient.async(transport).requestTimeout(java.time.Duration.ofSeconds(15)).build();
        try(var worker=Executors.newSingleThreadExecutor()) {
            client.initialize().block(java.time.Duration.ofSeconds(10));
            var tool=client.listTools().block(java.time.Duration.ofSeconds(5)).tools().stream().filter(t->t.name().equals("lookupPokemonReference")).findFirst().orElseThrow();
            var callback=CancellableMcpToolCallback.wrap(new org.springframework.ai.mcp.AsyncMcpToolCallback(client,tool));
            var pending=worker.submit(()->callback.call("{\"query\":\"slow\",\"dataReleaseId\":\"pokeapi-reference-v1\"}"));
            awaitFile(cache.resolve("slow-started"));assertTrue(pending.cancel(true));awaitFile(cache.resolve("slow-stopped"));
            var result=new com.fasterxml.jackson.databind.ObjectMapper().readTree(callback.call("{\"query\":\"7\",\"dataReleaseId\":\"pokeapi-reference-v1\"}"));
            assertEquals(44,new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.get(0).path("text").asText()).path("fact").path("hp").asInt());
            assertEquals(1,transport.snapshot().written());assertEquals(0,transport.snapshot().failed());
        } finally {client.closeGracefully().block(java.time.Duration.ofSeconds(5));}
    }
    private void awaitFile(java.nio.file.Path path) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!java.nio.file.Files.exists(path)&&System.nanoTime()<deadline)Thread.sleep(25);
        assertTrue(java.nio.file.Files.exists(path));
    }
    @Test void wrapperRetainsDefinitionMetadataAndContext() {
        var delegate=mock(ToolCallback.class);
        var definition=org.springframework.ai.tool.definition.ToolDefinition.builder().name("public_read").description("test").inputSchema("{}").build();
        var metadata=org.springframework.ai.tool.metadata.ToolMetadata.builder().build();
        var context=new org.springframework.ai.chat.model.ToolContext(Map.of("trace","test"));
        when(delegate.getToolDefinition()).thenReturn(definition);when(delegate.getToolMetadata()).thenReturn(metadata);
        when(delegate.call("{}",context)).thenReturn("ok");
        var wrapper=new CancellableMcpToolCallback(delegate);
        assertSame(definition,wrapper.getToolDefinition());assertSame(metadata,wrapper.getToolMetadata());assertEquals("ok",wrapper.call("{}",context));
        assertSame(delegate,CancellableMcpToolCallback.wrap(delegate));
    }
    @Test void cancellationRetainsWireIdTypeAndClearsTheCallingThreadScope() {
        for (Object id:List.of("request-1",7L)) {
            var wire=mock(McpClientTransport.class);
            var messages=new CopyOnWriteArrayList<McpSchema.JSONRPCMessage>();
            when(wire.sendMessage(any())).thenAnswer(call->{messages.add(call.getArgument(0));return Mono.empty();});
            var transport=new McpCancellationTransport(wire);
            var callback=mock(ToolCallback.class);
            when(callback.call("{}" )).thenAnswer(call->{
                transport.sendMessage(new McpSchema.JSONRPCRequest("2.0","tools/call",id,Map.of())).block();
                throw new RuntimeException(new InterruptedException());
            });
            assertThrows(RuntimeException.class,()->new CancellableMcpToolCallback(callback).call("{}"));
            assertEquals(2,messages.size());
            var cancellation=(McpSchema.JSONRPCNotification)messages.get(1);
            assertEquals("notifications/cancelled",cancellation.method());
            assertEquals(id,((Map<?,?>)cancellation.params()).get("requestId"));
            transport.sendMessage(new McpSchema.JSONRPCRequest("2.0","tools/call","outside",Map.of())).block();
            assertThrows(RuntimeException.class,()->McpCancellationTransport.call(()->{throw new CancellationException();}));
            assertEquals(3,messages.size());assertEquals(1,transport.snapshot().written());
        }
    }
    @Test void normalReturnsAndBusinessErrorsDoNotSendCancellation() {
        var wire=mock(McpClientTransport.class);when(wire.sendMessage(any())).thenReturn(Mono.empty());
        var transport=new McpCancellationTransport(wire);
        assertEquals("ok",McpCancellationTransport.call(()->{
            transport.sendMessage(new McpSchema.JSONRPCRequest("2.0","tools/call",1,Map.of())).block();return "ok";
        }));
        assertThrows(IllegalArgumentException.class,()->McpCancellationTransport.call(()->{throw new IllegalArgumentException();}));
        assertEquals(0,transport.snapshot().requested());
    }
    @Test void cancellationOnOneWorkerDoesNotBindAnotherWorkersRequest() throws Exception {
        var wire=mock(McpClientTransport.class);var messages=new CopyOnWriteArrayList<McpSchema.JSONRPCMessage>();
        when(wire.sendMessage(any())).thenAnswer(call->{messages.add(call.getArgument(0));return Mono.empty();});
        var transport=new McpCancellationTransport(wire);var bound=new CountDownLatch(2);var release=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var normal=workers.submit(()->McpCancellationTransport.call(()->{
                transport.sendMessage(new McpSchema.JSONRPCRequest("2.0","tools/call","normal",Map.of())).block();
                bound.countDown();try{release.await();}catch(InterruptedException error){throw new RuntimeException(error);}return "ok";
            }));
            var cancelled=workers.submit(()->McpCancellationTransport.call(()->{
                transport.sendMessage(new McpSchema.JSONRPCRequest("2.0","tools/call","cancel",Map.of())).block();
                bound.countDown();try{assertTrue(bound.await(3,TimeUnit.SECONDS));}catch(InterruptedException error){throw new RuntimeException(error);}
                throw new CancellationException();
            }));
            assertThrows(ExecutionException.class,()->cancelled.get(5,TimeUnit.SECONDS));
            assertFalse(normal.isDone());release.countDown();assertEquals("ok",normal.get(3,TimeUnit.SECONDS));
            var notifications=messages.stream().filter(McpSchema.JSONRPCNotification.class::isInstance).map(McpSchema.JSONRPCNotification.class::cast).toList();
            assertEquals(1,notifications.size());assertEquals("cancel",((Map<?,?>)notifications.getFirst().params()).get("requestId"));
        } finally {release.countDown();}
    }
}
