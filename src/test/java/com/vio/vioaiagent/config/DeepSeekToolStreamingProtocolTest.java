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

/** Exercise the real Spring AI SSE decoder and tool argument merger, not a mocked ChatModel. */
class DeepSeekToolStreamingProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fragmentedToolArgumentsAndAnswerChunksSurviveTheWholeAgentLoop() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<ObjectNode> requests = new CopyOnWriteArrayList<>();
        server.createContext("/chat/completions", exchange -> {
            requests.add((ObjectNode) mapper.readTree(exchange.getRequestBody().readAllBytes()));
            String response;
            if (requests.size() == 1) {
                ObjectNode first = mapper.createObjectNode().put("role", "assistant");
                first.putArray("tool_calls").addObject().put("index", 0).put("id", "call-1")
                        .put("type", "function").putObject("function").put("name", "getTypeChart")
                        .put("arguments", "{\"attackType\":");
                ObjectNode second = mapper.createObjectNode();
                second.putArray("tool_calls").addObject().put("index", 0).putObject("function").put("arguments", "\"火\"}");
                response = event(first, null) + event(second, null) + event(mapper.createObjectNode(), "tool_calls");
            } else {
                response = event(mapper.createObjectNode().put("role", "assistant").put("content", "火克制"), null)
                        + event(mapper.createObjectNode().put("content", "草。"), null)
                        + event(mapper.createObjectNode(), "stop");
            }
            byte[] bytes = (response + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
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
            assertEquals(2, requests.size(), "one tool decision and one final answer; no redundant generation");
            assertSame(parent, ToolExecutionContextHolder.peek());
            for (ObjectNode request : requests) {
                assertTrue(request.path("stream").asBoolean());
                assertEquals("disabled", request.path("thinking").path("type").asText());
            }
            assertTrue(requests.get(1).path("messages").toString().contains("attackType"));
            assertTrue(requests.get(1).path("messages").toString().contains("tool"));
        } finally { ToolExecutionContextHolder.clear(); server.stop(0); }
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
