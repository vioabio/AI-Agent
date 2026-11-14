package com.vio.vioaiagent.mcp;

import com.vio.vioaiagent.tools.governance.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class McpWireControlTest {
    static final class Harness implements AutoCloseable {
        final PipedInputStream raw = new PipedInputStream(131072);
        final PipedOutputStream client;
        final ByteArrayOutputStream replies = new ByteArrayOutputStream();
        final CountDownLatch end = new CountDownLatch(1);
        final McpWireControl wire;
        Harness() throws IOException { client=new PipedOutputStream(raw);wire=new McpWireControl(raw,replies,end); }
        void send(String frame) throws IOException {client.write((frame+"\n").getBytes(StandardCharsets.UTF_8));client.flush();}
        String forwarded() throws IOException {
            var bytes=new ByteArrayOutputStream();int c;
            while((c=wire.input.read())!=-1&&c!='\n')bytes.write(c);
            return bytes.toString(StandardCharsets.UTF_8);
        }
        void reply(String frame) throws IOException {wire.output.write((frame+"\n").getBytes(StandardCharsets.UTF_8));}
        void ready() throws Exception {
            send("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\"}");forwarded();
            reply("{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{}}");
            send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");forwarded();replies.reset();
        }
        public void close() throws IOException {wire.disconnect();client.close();raw.close();}
    }
    String call(int id) {return "{\"jsonrpc\":\"2.0\",\"id\":"+id+",\"method\":\"tools/call\",\"params\":{\"name\":\"lookupPokemonReference\",\"arguments\":{\"query\":\"7\",\"dataReleaseId\":\"pokeapi-reference-v1\"}}}";}
    @Test void malformedFramesNeverReachSdkAndRecoveryWorks() throws Exception {
        try(var h=new Harness()) {
            h.ready();
            h.send("{broken-private-payload");
            h.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":2,\"method\":\"ping\"}");
            h.send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{}}");h.send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}");
            assertEquals(4,h.wire.json.readTree(h.forwarded()).path("id").asInt());
            String out=h.replies.toString(StandardCharsets.UTF_8);
            assertEquals(3,out.lines().count());assertTrue(out.contains("MCP_PARSE_ERROR"));assertTrue(out.contains("MCP_INVALID_PARAMS"));
            assertFalse(out.contains("private-payload"));assertEquals(1,h.wire.pendingCount());
        }
    }
    @Test void preInitializationPingWorksWithoutBlockingInitialize() throws Exception {
        try(var h=new Harness()) {
            h.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");h.send(call(2));
            h.send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"initialize\"}");
            assertEquals("initialize",h.wire.json.readTree(h.forwarded()).path("method").asText());
            assertTrue(h.replies.toString(StandardCharsets.UTF_8).contains("MCP_NOT_INITIALIZED"));
            assertEquals(1,h.wire.pendingCount());
        }
    }
    @Test void admissionIsBoundedUntilResponseFlushAndUnknownNotificationsDoNotAllocate() throws Exception {
        try(var h=new Harness()) {
            h.ready();for(int i=1;i<=16;i++){h.send("{\"jsonrpc\":\"2.0\",\"id\":"+i+",\"method\":\"ping\"}");h.forwarded();}
            assertEquals(16,h.wire.pendingCount());
            h.send("{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"ping\"}");
            for(int i=0;i<100;i++)h.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/unknown\"}");
            h.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":999}}");
            // A second initialized notification is dropped too. EOF lets the synchronous reader drain refusals.
            h.client.close();assertEquals(-1,h.wire.input.read());
            assertTrue(h.replies.toString(StandardCharsets.UTF_8).contains("MCP_BUSY"));assertEquals(0,h.end.getCount());
        }
        try(var h=new Harness()) {
            h.ready();h.send(call(1));h.forwarded();h.send(call(2));h.forwarded();
            h.reply("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");assertEquals(1,h.wire.pendingCount());
            h.send(call(3));h.forwarded();assertEquals(2,h.wire.pendingCount());
        }
    }
    @Test void cancellationBindsTypedIdInterruptsWorkAndDoesNotRetry() throws Exception {
        exerciseCancellation(false);
    }
    @Test void eofDisconnectCancelsActiveWork() throws Exception {exerciseCancellation(true);}
    void exerciseCancellation(boolean disconnect) throws Exception {
        try(var h=new Harness()) {
            h.ready();h.send(call(1));var forwarded=h.wire.json.readTree(h.forwarded());
            var gateway=mock(ToolExecutionGateway.class);var entered=new CountDownLatch(1);var stopped=new CountDownLatch(1);
            when(gateway.execute(anyString(),anyString(),any(ToolExecutionContext.class))).thenAnswer(invocation->{entered.countDown();try{Thread.sleep(10000);return null;}finally{stopped.countDown();}});
            var fixture=new ReadOnlyMcpAdapterTest();var adapter=new ReadOnlyMcpAdapter(fixture.callbacks,gateway);
            Map<String,Object> args=h.wire.json.convertValue(forwarded.path("params").path("arguments"),Map.class);
            var result=h.wire.invoke(adapter,"lookupPokemonReference",args).toFuture();assertTrue(entered.await(3,TimeUnit.SECONDS));
            if(disconnect){h.client.close();assertEquals(-1,h.wire.input.read());}
            else {
                h.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":\"1\"}}");
                h.send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}");h.forwarded();assertFalse(result.isDone());
                h.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1}}");
                h.send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}");h.forwarded();
            }
            var cancelled=result.get(3,TimeUnit.SECONDS);assertTrue(cancelled.isError());
            assertEquals("MCP_REQUEST_CANCELLED",((McpSchema.TextContent)cancelled.content().getFirst()).text());
            assertTrue(stopped.await(3,TimeUnit.SECONDS));verify(gateway,times(1)).execute(anyString(),anyString(),any(ToolExecutionContext.class));
        }
    }
    @Test void duplicateActiveIdDisconnectsAndOutputErrorsAreSanitized() throws Exception {
        try(var h=new Harness()) {
            h.ready();h.send(call(1));h.forwarded();h.send(call(1));assertThrows(IOException.class,h::forwarded);assertEquals(0,h.end.getCount());
        }
        try(var h=new Harness()) {
            h.reply("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32603,\"message\":\"private-payload\",\"data\":\"secret\"}}");
            String safe=h.replies.toString(StandardCharsets.UTF_8);assertTrue(safe.contains("MCP_RPC_ERROR"));assertFalse(safe.contains("secret"));assertFalse(safe.contains("private-payload"));
            h.replies.reset();h.reply("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"text\":\""+"x".repeat(60000)+"\"}}");
            assertEquals(60000,new com.fasterxml.jackson.databind.ObjectMapper().readTree(h.replies.toByteArray()).path("result").path("text").asText().length());
        }
    }
}
