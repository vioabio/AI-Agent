package com.vio.vioaiagent.config;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real Spring HTTP lifecycle + real provider HTTP/SSE decoder, with an offline faulty provider. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.profiles.active=local,deepseek","vio.model.deepseek.api-key=test-only",
    "spring.ai.dashscope.api-key=test-only","search-api.api-key=test-only",
    "vio.security.enabled=false","vio.local-accounts.enabled=false",
    "vio.local-guides.semantic.enabled=false","vio.agent.specialists.enabled=false",
    "vio.agent.guide-research.enabled=false","vio.run.answer-streaming-enabled=true",
    "vio.http.cors.allowed-origins[0]=http://127.0.0.1:4319"
})
class StreamFailureRunHttpTest {
    static final Path DATA=Path.of("target","stream-fault-"+UUID.randomUUID()).toAbsolutePath();
    @DynamicPropertySource static void paths(DynamicPropertyRegistry p){
        p.add("vio.local-history.path",()->DATA.resolve("history.sqlite").toString());
        p.add("vio.local-teams.path",()->DATA.resolve("teams.sqlite").toString());
        p.add("vio.local-accounts.path",()->DATA.resolve("accounts.sqlite").toString());
        p.add("vio.local-guides.path",()->DATA.resolve("guides.sqlite").toString());
    }
    @LocalServerPort int port;
    @MockitoBean(name="deepSeekChatModel") ChatModel model;
    final ObjectMapper json=new ObjectMapper();
    final AtomicInteger decisions=new AtomicInteger(),streams=new AtomicInteger();
    HttpServer provider;
    @BeforeEach void setup() throws Exception {
        Files.createDirectories(DATA);decisions.set(0);streams.set(0);
        provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        provider.createContext("/chat/completions",e->{
            var input=json.readTree(e.getRequestBody().readAllBytes());
            boolean streaming=input.path("stream").asBoolean();
            if(!streaming){
                decisions.incrementAndGet();
                byte[] bytes="{\"id\":\"offline\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"准备回答\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);
                try(var output=e.getResponseBody()){output.write(bytes);}return;
            }
            streams.incrementAndGet();
            byte[] bytes="data: {\"id\":\"offline\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"部分测试\"},\"finish_reason\":null}]}\n\n".getBytes(StandardCharsets.UTF_8);
            byte[] tail="data: {\"id\":\"offline\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"回答\"},\"finish_reason\":null}]}\n\n".getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type","text/event-stream");e.sendResponseHeaders(200,0);
            try(var output=e.getResponseBody()){output.write(bytes);output.write(tail);output.flush();Thread.sleep(8000);}
            catch(Exception expected){/* Intentionally end SSE without DONE/finish_reason, although HTTP framing closes normally. */}
            finally {e.close();}
        });provider.start();
        var delegate=DeepSeekModelConfiguration.build("test-only","deepseek-flash","http://127.0.0.1:"+provider.getAddress().getPort());
        when(model.getDefaultOptions()).thenReturn(delegate.getDefaultOptions());
        when(model.call(any(Prompt.class))).thenAnswer(i->delegate.call(i.getArgument(0,Prompt.class)));
        when(model.stream(any(Prompt.class))).thenAnswer(i->delegate.stream(i.getArgument(0,Prompt.class)));
    }
    @AfterEach void close(){if(provider!=null)provider.stop(0);}
    JsonNode request(String path,String payload) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).timeout(Duration.ofSeconds(15));
        if(payload!=null)builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload));
        var result=HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(payload!=null&&path.equals("/chat/runs")?202:200,result.statusCode(),"Controlled Run API must succeed");return json.readTree(result.body());
    }
    @Test void realTruncatedProviderKeepsPartialAndFailsWithoutRestartingGeneration() throws Exception {
        var body=json.createObjectNode().put("conversationId","fault-"+UUID.randomUUID()).put("prompt","请回复一段简短的宝可梦欢迎语。")
            .put("gameReleaseId","pokemon-scarlet-violet").put("battleFormat","casual-singles").put("rulesetVersionId","casual-v1")
            .put("language","zh-CN").put("dataReleaseId","pokeapi-reference-v1").put("timeoutSeconds",30);
        String id=request("/chat/runs",body.toString()).path("runId").asText();
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();JsonNode view;
        do{Thread.sleep(100);view=request("/chat/runs/"+id,null);}while(!Set.of("FAILED","COMPLETED","TIMED_OUT").contains(view.path("status").asText())&&System.nanoTime()<deadline);
        assertEquals("FAILED",view.path("status").asText());
        var events=request("/chat/runs/"+id+"/events?afterEventId=0",null).path("events");var partial=new StringBuilder();
        for(var event:events){assertNotEquals("run.completed",event.path("type").asText());if(event.path("type").asText().equals("answer.delta"))partial.append(event.path("payload").path("text").asText());}
        assertEquals("部分测试回答",partial.toString());assertEquals(1,streams.get());assertEquals(1,decisions.get());
    }
    @Test void browserDisconnectReplaysPartialOnceAndShowsFailure() throws Exception {
        assumeTrue(Files.exists(Path.of("vio-ai-agent-frontend/node_modules/playwright/index.mjs")),"Browser test requires frontend npm ci");
        var probe=new ProcessBuilder("node","tools/campus/stream-fault-browser.mjs","--probe").redirectErrorStream(true).redirectOutput(DATA.resolve("browser-probe.log").toFile()).start();
        boolean probeFinished=probe.waitFor(30,java.util.concurrent.TimeUnit.SECONDS);
        if(!probeFinished)probe.destroyForcibly();
        assumeTrue(probeFinished&&probe.exitValue()==0,"Browser runtime unavailable; HTTP fault test still runs");
        var builder=new ProcessBuilder("node","tools/campus/stream-fault-browser.mjs").redirectErrorStream(true).redirectOutput(DATA.resolve("browser.log").toFile());
        builder.environment().put("VIO_FAULT_TEST_API","http://127.0.0.1:"+port);
        var browser=builder.start();
        try {assertTrue(browser.waitFor(90,java.util.concurrent.TimeUnit.SECONDS),"Controlled browser timeout");assertEquals(0,browser.exitValue(),"See isolated browser.log");assertEquals(1,streams.get());assertEquals(1,decisions.get());}
        finally{if(browser.isAlive())browser.destroyForcibly();}
    }
}
