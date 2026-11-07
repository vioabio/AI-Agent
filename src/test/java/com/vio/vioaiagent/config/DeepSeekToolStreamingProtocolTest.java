package com.vio.vioaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.vio.vioaiagent.agent.ToolCallAgent;
import com.vio.vioaiagent.tools.PokeTypeTool;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise real HTTP/JSON and SSE decoding; this is not a live provider quality test. */
class DeepSeekToolStreamingProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void privateToolDecisionsAndPublicAnswerChunksSurviveTheWholeAgentLoop() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<ObjectNode> requests = new CopyOnWriteArrayList<>();
        server.createContext("/chat/completions", exchange -> {
            requests.add((ObjectNode) mapper.readTree(exchange.getRequestBody().readAllBytes()));
            String response;
            boolean streaming = requests.getLast().path("stream").asBoolean();
            if (!streaming) {
                ObjectNode message = mapper.createObjectNode().put("role", "assistant")
                        .put("content", requests.size() == 1 ? "Private planning: query the chart." : "Private planning: ready to synthesize.");
                if (requests.size() == 1) message.putArray("tool_calls").addObject().put("id", "call-1")
                        .put("type", "function").putObject("function").put("name", "getTypeChart")
                        .put("arguments", "{\"attackType\":\"火\"}");
                response = completion(message, requests.size() == 1 ? "tool_calls" : "stop");
            } else {
                response = event(mapper.createObjectNode().put("role", "assistant").put("content", "火克制"), null)
                        + event(mapper.createObjectNode().put("content", "草。"), null)
                        + event(mapper.createObjectNode(), "stop") + "data: [DONE]\n\n";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        ToolExecutionContext parent = new ToolExecutionContext("local", "run-http-test", Set.of("trainer"), "trace-http", null, null);
        ToolExecutionContextHolder.set(parent);
        try {
            var model = DeepSeekModelConfiguration.build("offline-test-only", "deepseek-flash",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            var agent = new ToolCallAgent(ToolCallbacks.from(new PokeTypeTool()));
            agent.setSystemPrompt("Use the available tools when needed.");
            agent.setChatClient(ChatClient.builder(model).build());
            List<String> chunks = new ArrayList<>();
            agent.setAnswerDeltaSink(chunks::add);
            agent.run("火属性对草属性？");
            assertEquals("火克制草。", agent.getFinalAnswer());
            assertEquals(List.of("火克制", "草。"), chunks);
            assertEquals(3, requests.size(), "two private decisions followed by a dedicated public answer");
            assertSame(parent, ToolExecutionContextHolder.peek());
            for (ObjectNode request : requests) {
                assertEquals("disabled", request.path("thinking").path("type").asText());
            }
            assertFalse(requests.get(0).path("stream").asBoolean());
            assertFalse(requests.get(1).path("stream").asBoolean());
            assertTrue(requests.get(2).path("stream").asBoolean());
            assertFalse(requests.get(2).hasNonNull("tools") && !requests.get(2).path("tools").isEmpty());
            assertTrue(requests.get(1).path("messages").toString().contains("attackType"));
            assertTrue(requests.get(1).path("messages").toString().contains("tool"));
        } finally { ToolExecutionContextHolder.clear(); server.stop(0); }
    }

    @Test
    void fragmentedToolArgumentsAreMergedByTheRealStreamingDecoder() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            ObjectNode first = mapper.createObjectNode().put("role", "assistant");
            first.putArray("tool_calls").addObject().put("index", 0).put("id", "call-1")
                    .put("type", "function").putObject("function").put("name", "getTypeChart")
                    .put("arguments", "{\"attackType\":");
            ObjectNode second = mapper.createObjectNode();
            second.putArray("tool_calls").addObject().put("index", 0).putObject("function").put("arguments", "\"火\"}");
            byte[] bytes = (event(first, null) + event(second, null) + event(mapper.createObjectNode(), "tool_calls")
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var model = DeepSeekModelConfiguration.build("offline-test-only", "deepseek-flash",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            var chunks = ChatClient.builder(model).build().prompt().user("火属性的克制关系？")
                    .toolCallbacks(ToolCallbacks.from(new PokeTypeTool()))
                    .stream().chatResponse().collectList().block(java.time.Duration.ofSeconds(10));
            assertNotNull(chunks);
            var calls = chunks.stream().filter(chunk -> chunk.getResult() != null)
                    .flatMap(chunk -> chunk.getResult().getOutput().getToolCalls().stream()).toList();
            assertTrue(calls.stream().anyMatch(call -> "getTypeChart".equals(call.name())
                    && "{\"attackType\":\"火\"}".equals(call.arguments())), "SSE fragments must form one valid tool argument object");
        } finally { server.stop(0); }
    }

    private String completion(ObjectNode message, String finish) throws java.io.IOException {
        ObjectNode response = mapper.createObjectNode().put("id", "completion-test").put("object", "chat.completion")
                .put("created", 1).put("model", "deepseek-flash");
        ObjectNode choice = response.putArray("choices").addObject().put("index", 0).put("finish_reason", finish);
        choice.set("message", message);
        return mapper.writeValueAsString(response);
    }

    private String event(ObjectNode delta, String finish) throws java.io.IOException {
        ObjectNode event = mapper.createObjectNode().put("id", "completion-test").put("object", "chat.completion.chunk")
                .put("created", 1).put("model", "deepseek-flash");
        ObjectNode choice = event.putArray("choices").addObject().put("index", 0);
        choice.set("delta", delta);
        if (finish == null) choice.putNull("finish_reason"); else choice.put("finish_reason", finish);
        return "data: " + mapper.writeValueAsString(event) + "\n\n";
    }
}
