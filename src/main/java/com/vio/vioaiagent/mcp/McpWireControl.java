package com.vio.vioaiagent.mcp;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Bounded STDIO envelope control around the pinned SDK; no domain authorization is inferred here. */
final class McpWireControl {
    static final String TOKEN = "__vio_mcp_invocation";
    static final int MAX_PENDING = 16, MAX_TOOLS = 2, MAX_FRAME = 24_576, MAX_OUTPUT_FRAME = 262_144;
    private enum Phase { NEW, INITIALIZING, AWAIT_INITIALIZED, READY, CLOSED }
    private static final class Pending {
        final String key, token, method, tool;
        final Sinks.One<Boolean> cancelled = Sinks.one();
        Pending(String key, String method, String tool) { this.key=key; this.method=method; this.tool=tool; this.token=UUID.randomUUID().toString(); }
    }
    final ObjectMapper json = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24).maxStringLength(MAX_FRAME).maxNumberLength(100).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    final InputStream input;
    final OutputStream output;
    private final ObjectMapper outputJson = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(MAX_OUTPUT_FRAME).build()).build());
    private final RpcOutput writer;
    private final CountDownLatch end;
    private final Map<String, Pending> pending = new HashMap<>(), tokens = new HashMap<>();
    private Phase phase = Phase.NEW;
    private int tools;

    McpWireControl(InputStream input, OutputStream output, CountDownLatch end) {
        this.end=end; this.writer=new RpcOutput(output); this.output=writer; this.input=new RpcInput(input);
    }
    Mono<McpSchema.CallToolResult> invoke(ReadOnlyMcpAdapter adapter, String name, Map<String,Object> supplied) {
        var args = new LinkedHashMap<>(supplied);
        Object token = args.remove(TOKEN); Pending call;
        synchronized (this) { call = token instanceof String ? tokens.get(token) : null; }
        if (call == null || !name.equals(call.tool)) return Mono.just(new McpSchema.CallToolResult("MCP_CALL_SCOPE_INVALID",true));
        // Cancel yields one normal MCP error result through the SDK, suppressing a late success reply.
        return Mono.firstWithSignal(call.cancelled.asMono().map(ignored -> new McpSchema.CallToolResult("MCP_REQUEST_CANCELLED",true)), adapter.invoke(name,args));
    }
    private static String key(JsonNode id) {
        if (id != null && id.isTextual() && id.textValue().length() <= 128) return "s:"+id.textValue();
        if (id != null && id.isIntegralNumber() && id.asText().length() <= 100) return "n:"+id.asText();
        return null;
    }
    private byte[] accept(byte[] frame) throws IOException {
        JsonNode root;
        try { root=json.readTree(frame); } catch (Exception malformed) { error(null,-32700,"MCP_PARSE_ERROR"); return null; }
        if (root==null || !root.isObject() || !"2.0".equals(root.path("jsonrpc").asText()) || !root.path("method").isTextual()) {
            // This server never asks the client for roots/sampling; unsolicited responses are ignored.
            if (root!=null && root.isObject() && !root.has("method") && (root.has("result")||root.has("error"))) return null;
            error(null,-32600,"MCP_INVALID_REQUEST"); return null;
        }
        String method=root.path("method").asText();
        if (!root.has("id")) {
            if (method.equals("notifications/cancelled")) {
                Pending call; synchronized(this) { call=pending.get(key(root.path("params").path("requestId"))); }
                if (call!=null && call.method.equals("tools/call")) call.cancelled.tryEmitValue(true);
            } else if (method.equals("notifications/initialized")) {
                synchronized(this) { if(phase!=Phase.AWAIT_INITIALIZED)return null; phase=Phase.READY; }
                return line(root);
            }
            return null; // Unknown notifications cannot fill the SDK's unbounded inbound queue.
        }
        JsonNode id=root.get("id"); String idKey=key(id);
        if (idKey==null || method.isBlank() || method.length()>128) { error(null,-32600,"MCP_INVALID_REQUEST"); return null; }
        // The pinned SDK gates session handlers on initialization. Ping must remain usable before it.
        boolean initializing;
        synchronized(this) {
            if(phase==Phase.CLOSED)return null;
            if(pending.containsKey(idKey))throw new IOException("MCP_DUPLICATE_ACTIVE_ID");
            initializing=phase!=Phase.READY;
        }
        if(initializing&&method.equals("ping")) {
            ObjectNode pong=json.createObjectNode().put("jsonrpc","2.0");pong.set("id",id);pong.putObject("result");writer.direct(pong);return null;
        }
        boolean tool=method.equals("tools/call"); JsonNode args=root.path("params").path("arguments");
        if (tool && (!root.path("params").path("name").isTextual() || root.path("params").path("name").asText().length()>80
                || (!args.isMissingNode()&&!args.isObject()) || args.has(TOKEN))) { error(id,-32602,"MCP_INVALID_PARAMS"); return null; }
        Pending call; String refusal=null;
        synchronized(this) {
            if (phase==Phase.CLOSED) return null;
            if (pending.containsKey(idKey)) throw new IOException("MCP_DUPLICATE_ACTIVE_ID");
            if (!method.equals("initialize") && phase!=Phase.READY) refusal="MCP_NOT_INITIALIZED";
            else if (method.equals("initialize")&&phase!=Phase.NEW) refusal="MCP_ALREADY_INITIALIZED";
            else if (pending.size()>=MAX_PENDING || tool&&tools>=MAX_TOOLS) refusal="MCP_BUSY";
            call=refusal==null?new Pending(idKey,method,tool?root.path("params").path("name").asText():null):null;
            if (call!=null) { pending.put(idKey,call); if(tool){tokens.put(call.token,call);tools++;} if(method.equals("initialize"))phase=Phase.INITIALIZING; }
        }
        if (refusal!=null) { error(id,-32000,refusal); return null; }
        if (tool) {
            ObjectNode params=(ObjectNode)root.get("params");
            ObjectNode arguments=args.isMissingNode()?json.createObjectNode():(ObjectNode)args;
            arguments.put(TOKEN,call.token); params.set("arguments",arguments);
        }
        return line(root);
    }
    private byte[] line(JsonNode value) throws IOException {
        byte[] data=json.writeValueAsBytes(value); byte[] framed=Arrays.copyOf(data,data.length+1);framed[data.length]='\n';return framed;
    }
    private void error(JsonNode id,int code,String message) throws IOException {
        ObjectNode value=json.createObjectNode().put("jsonrpc","2.0");value.set("id",id==null?NullNode.instance:id);
        value.putObject("error").put("code",code).put("message",message);writer.direct(value);
    }
    private synchronized void preparingReply(JsonNode reply) {
        Pending call=pending.get(key(reply.get("id")));
        if (call!=null&&call.method.equals("initialize")&&reply.has("result"))phase=Phase.AWAIT_INITIALIZED;
    }
    private synchronized void replied(JsonNode reply) {
        Pending call=pending.remove(key(reply.get("id")));
        if(call!=null&&call.tool!=null){tokens.remove(call.token);tools--;}
    }
    void disconnect() {
        List<Pending> calls;
        synchronized(this) { if(phase==Phase.CLOSED)return;phase=Phase.CLOSED;calls=List.copyOf(pending.values());pending.clear();tokens.clear();tools=0; }
        calls.forEach(call->call.cancelled.tryEmitValue(true));end.countDown();
    }
    synchronized int pendingCount() { return pending.size(); }
    private final class RpcInput extends InputStream {
        final InputStream source; byte[] current=new byte[0];int offset;
        RpcInput(InputStream source){this.source=new BufferedInputStream(source);}
        @Override public int read() throws IOException {
            try {
                while(offset==current.length){
                    var bytes=new ByteArrayOutputStream();int value;
                    while((value=source.read())!=-1&&value!='\n'){
                        if(bytes.size()>=MAX_FRAME)throw new IOException("MCP_FRAME_TOO_LARGE");bytes.write(value);
                    }
                    if(value==-1&&bytes.size()==0){disconnect();return -1;}
                    if(bytes.size()==0)continue;
                    byte[] accepted=accept(bytes.toByteArray());if(accepted==null)continue;current=accepted;offset=0;
                }
                return current[offset++]&255;
            }catch(IOException failure){disconnect();throw failure;}
        }
        @Override public int read(byte[] data,int off,int length) throws IOException {
            Objects.checkFromIndexSize(off,length,data.length);if(length==0)return 0;
            int first=read();if(first<0)return -1;data[off]=(byte)first;
            int available=Math.min(length-1,current.length-offset);System.arraycopy(current,offset,data,off+1,available);offset+=available;return available+1;
        }
        @Override public void close() throws IOException {disconnect();source.close();}
    }
    private final class RpcOutput extends OutputStream {
        final OutputStream destination; final ByteArrayOutputStream frame=new ByteArrayOutputStream();
        RpcOutput(OutputStream destination){this.destination=destination;}
        synchronized void direct(JsonNode node) throws IOException {destination.write(line(node));destination.flush();}
        @Override public synchronized void write(int value) throws IOException {
            if(value!='\n'){if(frame.size()>=MAX_OUTPUT_FRAME){disconnect();throw new IOException("MCP_OUTPUT_TOO_LARGE");}frame.write(value);return;}
            if(frame.size()==0)return;
            try {
                JsonNode reply=outputJson.readTree(frame.toByteArray());
                if(reply.has("error")) {
                    ObjectNode safe=json.createObjectNode().put("jsonrpc","2.0");safe.set("id",reply.path("id"));
                    safe.putObject("error").put("code",reply.path("error").path("code").asInt(-32603)).put("message","MCP_RPC_ERROR");reply=safe;
                }
                preparingReply(reply);direct(reply);replied(reply);
            }finally{frame.reset();}
        }
        @Override public synchronized void write(byte[] data,int off,int length) throws IOException {Objects.checkFromIndexSize(off,length,data.length);for(int i=off;i<off+length;i++)write(data[i]&255);}
        @Override public synchronized void flush() throws IOException {destination.flush();}
        @Override public void close() throws IOException {disconnect();destination.flush();}
    }
}
