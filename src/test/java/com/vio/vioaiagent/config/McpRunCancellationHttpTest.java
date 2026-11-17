package com.vio.vioaiagent.config;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.mcp.McpStdioFixtureMain;
import com.vio.vioaiagent.mcp.client.McpCancellationTransport;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Actual Run HTTP/gateway/Spring-AI callback/SDK/STDIO server cancellation chain. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.profiles.active=local,deepseek","vio.model.deepseek.api-key=test-only",
        "spring.ai.dashscope.api-key=test-only","search-api.api-key=test-only",
        "spring.ai.mcp.client.enabled=false","vio.security.enabled=false","vio.local-accounts.enabled=false",
        "vio.local-guides.semantic.enabled=false","vio.local-guides.rerank.enabled=false",
        "vio.agent.specialists.enabled=false","vio.agent.guide-research.enabled=false",
        "vio.run.answer-streaming-enabled=true"
})
@Import(McpRunCancellationHttpTest.ExternalClient.class)
class McpRunCancellationHttpTest {
    static final Path DATA=createData();
    static Path createData() {
        try {
            Path artifacts = Files.createDirectories(Path.of("artifacts").toAbsolutePath());
            return Files.createTempDirectory(artifacts, "u6-web-mcp-");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
    @DynamicPropertySource static void paths(DynamicPropertyRegistry registry) {
        registry.add("vio.local-history.path",()->DATA.resolve("history.sqlite").toString());
        registry.add("vio.local-teams.path",()->DATA.resolve("teams.sqlite").toString());
        registry.add("vio.local-accounts.path",()->DATA.resolve("accounts.sqlite").toString());
        registry.add("vio.local-guides.path",()->DATA.resolve("guides.sqlite").toString());
    }
    @TestConfiguration static class ExternalClient {
        @Bean List<NamedClientMcpTransport> controlledTransports() {
            String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            var parameters=ServerParameters.builder(java).args(List.of("-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),McpStdioFixtureMain.class.getName(),DATA.resolve("mcp").toString())).build();
            var transport=new StdioClientTransport(parameters);transport.setStdErrorHandler(ignored->{});
            return List.of(new NamedClientMcpTransport("controlled-public",transport));
        }
        @Bean(destroyMethod="close") McpSyncClient controlledClient(@Qualifier("controlledTransports") List<NamedClientMcpTransport> transports) {
            var client=McpClient.sync(transports.getFirst().transport()).requestTimeout(Duration.ofSeconds(30)).build();client.initialize();return client;
        }
        @Bean ToolCallbackProvider controlledProvider(McpSyncClient client) {
            var tool=client.listTools().tools().stream().filter(t->t.name().equals("lookupPokemonReference")).findFirst().orElseThrow();
            // Explicit test-only mapping to the already registered public-read capability.
            ToolCallback callback=new SyncMcpToolCallback(client,tool) {
                @Override public ToolDefinition getToolDefinition() {return ToolDefinition.builder().name("web_search").description("读取公开资料").inputSchema(super.getToolDefinition().inputSchema()).build();}
            };
            return ()->new ToolCallback[]{callback};
        }
    }
    @LocalServerPort int port;
    @MockitoBean(name="deepSeekChatModel") ChatModel model;
    @Autowired @Qualifier("controlledTransports") List<NamedClientMcpTransport> transports;
    final ObjectMapper json=new ObjectMapper();
    final AtomicReference<String> query=new AtomicReference<>();
    final java.util.concurrent.atomic.AtomicBoolean toolFailed=new java.util.concurrent.atomic.AtomicBoolean();
    @BeforeEach void model() {
        when(model.call(any(Prompt.class))).thenAnswer(call->{
            Prompt prompt=call.getArgument(0);
            boolean hasResult=prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
            toolFailed.set(prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast).flatMap(message->message.getResponses().stream())
                    .anyMatch(response->response.responseData()!=null&&response.responseData().startsWith("[tool.")));
            AssistantMessage message=hasResult
                    ? new AssistantMessage(toolFailed.get()?"本次资料读取失败。":"公开资料已读取。")
                    : new AssistantMessage("",Map.of(),List.of(new AssistantMessage.ToolCall(UUID.randomUUID().toString(),"function","web_search",json.writeValueAsString(Map.of("query",query.get(),"dataReleaseId","pokeapi-reference-v1")))));
            return new ChatResponse(List.of(new Generation(message)));
        });
        when(model.stream(any(Prompt.class))).thenAnswer(call->Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(toolFailed.get()?"本次资料读取失败。":"公开资料已读取。"),ChatGenerationMetadata.builder().finishReason("stop").build())))));
    }
    JsonNode request(String path,String body) throws Exception {
        try(var http=HttpClient.newHttpClient()) {
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).timeout(Duration.ofSeconds(10));
            if(body!=null)builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            var result=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(path.equals("/chat/runs")&&body!=null?202:200,result.statusCode());return json.readTree(result.body());
        }
    }
    String start(String value,int seconds) throws Exception {
        query.set(value);
        return request("/chat/runs",json.writeValueAsString(Map.of("prompt","请使用外部公开资料查询服务读取一条参考资料。","conversationId",UUID.randomUUID().toString(),
                "gameReleaseId","pokemon-scarlet-violet","battleFormat","casual-singles","rulesetVersionId","casual-v1","language","zh-CN","dataReleaseId","pokeapi-reference-v1","timeoutSeconds",seconds))).path("runId").asText();
    }
    void marker(String name) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(!Files.exists(DATA.resolve("mcp").resolve(name))&&System.nanoTime()<end)Thread.sleep(25);
        assertTrue(Files.exists(DATA.resolve("mcp").resolve(name)),name);
    }
    JsonNode terminal(String id) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        do {var view=request("/chat/runs/"+id,null);if(Set.of("COMPLETED","CANCELLED","TIMED_OUT","FAILED").contains(view.path("status").asText()))return view;Thread.sleep(50);}while(System.nanoTime()<end);
        throw new AssertionError("Run did not terminate");
    }
    @Test void runCancellationReachesExternalWorkerAndSameConnectionRecovers() throws Exception {
        String id=start("slow-70",30);marker("slow-70-started");
        request("/chat/runs/"+id+"/cancel","{}");assertEquals("CANCELLED",terminal(id).path("status").asText());marker("slow-70-stopped");
        verifyEvents(id,"run.cancelled");recover();verifyEvents(id,"run.cancelled");
    }
    @Test void runDeadlineReachesExternalWorkerAndSameConnectionRecovers() throws Exception {
        String id=start("slow-71",4);marker("slow-71-started");
        assertEquals("TIMED_OUT",terminal(id).path("status").asText());marker("slow-71-stopped");
        verifyEvents(id,"run.timed_out");recover();verifyEvents(id,"run.timed_out");
    }
    @Test void toolDeadlineAlsoSendsCancellation() throws Exception {
        var bridge=(McpCancellationTransport)transports.getFirst().transport();long before=bridge.snapshot().written();
        String id=start("slow-72",30);marker("slow-72-started");marker("slow-72-stopped");
        var view=terminal(id);assertTrue(bridge.snapshot().written()>before);
        var events=request("/chat/runs/"+id+"/events?afterEventId=0",null).path("events");
        assertTrue(java.util.stream.StreamSupport.stream(events.spliterator(),false)
                .anyMatch(e->e.path("type").asText().equals("tool.finished")&&e.path("payload").path("status").asText().equals("FAILED")));
        String answer=answer(events);assertFalse(answer.contains("已读取"));
        if(view.path("status").asText().equals("COMPLETED"))assertTrue(answer.contains("失败"));
        Files.writeString(DATA.resolve(id+"-events.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(events));
        recover();
    }
    void verifyEvents(String id,String terminalType) throws Exception {
        var events=request("/chat/runs/"+id+"/events?afterEventId=0",null).path("events");
        assertTrue(java.util.stream.StreamSupport.stream(events.spliterator(),false).anyMatch(e->e.path("type").asText().replace("run.timed.out","run.timed_out").equals(terminalType)));
        assertFalse(java.util.stream.StreamSupport.stream(events.spliterator(),false).anyMatch(e->Set.of("answer.delta","run.completed").contains(e.path("type").asText())));
        Files.writeString(DATA.resolve(id+"-events.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(events));
    }
    void recover() throws Exception {
        String id=start("7",30);assertEquals("COMPLETED",terminal(id).path("status").asText());
        var events=request("/chat/runs/"+id+"/events?afterEventId=0",null).path("events");
        assertTrue(java.util.stream.StreamSupport.stream(events.spliterator(),false).anyMatch(e->e.path("type").asText().equals("tool.finished")
                &&e.path("payload").path("toolName").asText().equals("web_search")&&e.path("payload").path("status").asText().equals("SUCCEEDED")));
        assertEquals("公开资料已读取。",answer(events));
        Files.writeString(DATA.resolve(id+"-events.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(events));
        var bridge=(McpCancellationTransport)transports.getFirst().transport();
        assertEquals(0,bridge.snapshot().failed());
        Files.writeString(DATA.resolve("cancellation-transport.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(bridge.snapshot()));
    }
    String answer(JsonNode events) {
        var text=new StringBuilder();for(var event:events)if(event.path("type").asText().equals("answer.delta"))text.append(event.path("payload").path("text").asText());return text.toString();
    }
}
