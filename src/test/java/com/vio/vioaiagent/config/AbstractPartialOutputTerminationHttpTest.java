package com.vio.vioaiagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.profiles.active=local,deepseek", "vio.model.deepseek.api-key=test-only",
        "spring.ai.dashscope.api-key=test-only", "search-api.api-key=test-only",
        "vio.security.enabled=false", "vio.local-accounts.enabled=false",
        "vio.local-guides.semantic.enabled=false", "vio.local-guides.rerank.enabled=false",
        "vio.agent.specialists.enabled=false", "vio.agent.guide-research.enabled=false",
        "vio.run.answer-streaming-enabled=true", "management.tracing.enabled=false",
        "vio.observability.tracing.endpoint=http://127.0.0.1:4318/v1/traces",
        "vio.http.cors.allowed-origins[0]=http://127.0.0.1:4329"
})
abstract class AbstractPartialOutputTerminationHttpTest {
    static final String PARTIAL="部分测试回答";
    final ObjectMapper json=new ObjectMapper();
    final HttpClient client=HttpClient.newHttpClient();
    final AtomicInteger decisions=new AtomicInteger(),streams=new AtomicInteger(),tailAttempts=new AtomicInteger();
    volatile CountDownLatch release=new CountDownLatch(1);
    HttpServer provider;
    ExecutorService providerThreads;
    @LocalServerPort int port;
    @MockitoBean(name="deepSeekChatModel") ChatModel model;
    abstract Path dataDirectory();
    abstract boolean featuresEnabled();

    static Path createDirectory(String mode) {
        try {return Files.createTempDirectory(Path.of("artifacts").toAbsolutePath(),"u6-partial-"+mode+"-");}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    static void paths(DynamicPropertyRegistry registry,Path directory) {
        registry.add("vio.local-history.path",()->directory.resolve("history.sqlite").toString());
        registry.add("vio.local-teams.path",()->directory.resolve("teams.sqlite").toString());
        registry.add("vio.local-accounts.path",()->directory.resolve("accounts.sqlite").toString());
        registry.add("vio.local-guides.path",()->directory.resolve("guides.sqlite").toString());
    }
    @BeforeEach void startProvider() throws Exception {
        decisions.set(0);streams.set(0);tailAttempts.set(0);release=new CountDownLatch(1);
        provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        providerThreads=Executors.newVirtualThreadPerTaskExecutor();provider.setExecutor(providerThreads);
        provider.createContext("/chat/completions",exchange->{
            var body=json.readTree(exchange.getRequestBody().readAllBytes());
            if(!body.path("stream").asBoolean()) {
                decisions.incrementAndGet();
                byte[] bytes=("{\"id\":\"controlled\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"deepseek-flash\","
                        +"\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"准备回答\"},\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
                try(var output=exchange.getResponseBody()){output.write(bytes);}return;
            }
            streams.incrementAndGet();
            CountDownLatch gate=new CountDownLatch(1);release=gate;
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            try(var output=exchange.getResponseBody()) {
                // Spring AI retains one response for usage look-ahead. Drain
                // the pending text without declaring completion or token usage.
                output.write((chunk(PARTIAL,null)+"data: {\"id\":\"controlled-buffer-drain\",\"choices\":[]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));output.flush();
                if(!gate.await(30,TimeUnit.SECONDS))return;
                tailAttempts.incrementAndGet();
                output.write((chunk("不应发布的尾部","stop")+"data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));output.flush();
            } catch(Exception expected) { /* Cancellation may close the provider socket before the late write. */ }
            finally {exchange.close();}
        });
        provider.createContext("/control/status",exchange->{
            byte[] bytes=json.writeValueAsBytes(Map.of("fixture","u6-partial-output-v1","decisions",decisions.get(),"streams",streams.get(),"tailAttempts",tailAttempts.get()));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        });
        provider.createContext("/control/release",exchange->{
            if(!exchange.getRequestMethod().equals("POST")){exchange.sendResponseHeaders(405,-1);exchange.close();return;}
            release.countDown();exchange.sendResponseHeaders(204,-1);exchange.close();
        });
        provider.start();
        var delegate=DeepSeekModelConfiguration.build("test-only","deepseek-flash","http://127.0.0.1:"+provider.getAddress().getPort());
        when(model.getDefaultOptions()).thenReturn(delegate.getDefaultOptions());
        when(model.call(any(Prompt.class))).thenAnswer(invocation->delegate.call(invocation.getArgument(0,Prompt.class)));
        when(model.stream(any(Prompt.class))).thenAnswer(invocation->delegate.stream(invocation.getArgument(0,Prompt.class)));
    }
    private String chunk(String text,String finish) throws java.io.IOException {
        var choice=json.createObjectNode().put("index",0);choice.set("delta",json.createObjectNode().put("content",text));
        if(finish==null)choice.putNull("finish_reason");else choice.put("finish_reason",finish);
        var root=json.createObjectNode().put("id","controlled").put("object","chat.completion.chunk").put("model","deepseek-flash");
        root.putArray("choices").add(choice);return "data: "+json.writeValueAsString(root)+"\n\n";
    }
    @AfterEach void closeProvider(){release.countDown();if(provider!=null)provider.stop(0);if(providerThreads!=null)providerThreads.shutdownNow();client.close();}
    JsonNode request(String path,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).timeout(Duration.ofSeconds(15));
        if(body!=null)builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(path.equals("/chat/runs")&&body!=null?202:200,response.statusCode());return json.readTree(response.body());
    }
    private String createRun(int seconds) throws Exception {
        var body=json.createObjectNode().put("conversationId","partial-"+UUID.randomUUID()).put("prompt","请回复一段简短的宝可梦欢迎语。")
                .put("gameReleaseId","pokemon-scarlet-violet").put("battleFormat","casual-singles").put("rulesetVersionId","casual-v1")
                .put("language","zh-CN").put("dataReleaseId","pokeapi-reference-v1").put("timeoutSeconds",seconds);
        return request("/chat/runs",body.toString()).path("runId").asText();
    }
    private JsonNode waitEvents(String id,String wanted) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        do {
            var events=request("/chat/runs/"+id+"/events?afterEventId=0",null).path("events");
            for(var event:events)if(event.path("type").asText().equals(wanted))return events;
            Thread.sleep(50);
        }while(System.nanoTime()<end);
        throw new AssertionError("Missing controlled event "+wanted);
    }
    private void checkTermination(boolean cancel) throws Exception {
        var runtime=request("/chat/runtime",null);
        for(String flag:List.of("skills","contextAssembly","answerBlocks","domainTracing"))assertEquals(featuresEnabled(),runtime.path("upgradeFeatures").path(flag).asBoolean());
        String id=createRun(cancel?30:8);waitEvents(id,"answer.delta");
        assertEquals("RUNNING",request("/chat/runs/"+id,null).path("status").asText());
        if(cancel)assertTrue(request("/chat/runs/"+id+"/cancel","{}").path("changed").asBoolean());
        var frozen=waitEvents(id,cancel?"run.cancelled":"run.timed_out");
        release.countDown();
        long end=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while(tailAttempts.get()==0&&System.nanoTime()<end)Thread.sleep(25);
        assertEquals(1,tailAttempts.get());Thread.sleep(200);
        var after=request("/chat/runs/"+id+"/events?afterEventId=0",null).path("events");assertEquals(frozen,after);
        var answer=new StringBuilder();int terminals=0;
        for(var event:after){String type=event.path("type").asText();if(type.equals("answer.delta"))answer.append(event.path("payload").path("text").asText());if(type.startsWith("run.")&&Set.of("run.completed","run.failed","run.cancelled","run.timed_out").contains(type))terminals++;}
        assertEquals(PARTIAL,answer.toString());assertEquals(1,terminals);assertEquals(1,decisions.get());assertEquals(1,streams.get());
        assertFalse(request("/chat/runs/"+id+"/cancel","{}").path("changed").asBoolean());
        Files.writeString(dataDirectory().resolve(cancel?"http-cancel-events.json":"http-timeout-events.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(after));
    }
    @Test void cancellationFreezesAnAlreadyPublishedPartialAnswer() throws Exception {checkTermination(true);}
    @Test void deadlineFreezesAnAlreadyPublishedPartialAnswer() throws Exception {checkTermination(false);}
    @Test void browserReplaysPartialCancellationAndTimeout() throws Exception {
        assumeTrue(Files.exists(Path.of("vio-ai-agent-frontend/node_modules/playwright/index.mjs")),"Install frontend browser dependencies");
        var probe=new ProcessBuilder("node","tools/campus/partial-output-browser.mjs","--probe").redirectErrorStream(true).redirectOutput(dataDirectory().resolve("browser-probe.log").toFile()).start();
        boolean available=probe.waitFor(30,TimeUnit.SECONDS);if(!available)probe.destroyForcibly();
        assumeTrue(available&&probe.exitValue()==0,"Browser runtime unavailable");
        var builder=new ProcessBuilder("node","tools/campus/partial-output-browser.mjs").redirectErrorStream(true).redirectOutput(dataDirectory().resolve("browser.log").toFile());
        builder.environment().put("VIO_PARTIAL_TEST_API","http://127.0.0.1:"+port+"/api");
        builder.environment().put("VIO_PARTIAL_CONTROL","http://127.0.0.1:"+provider.getAddress().getPort()+"/control");
        builder.environment().put("VIO_PARTIAL_OUTPUT",dataDirectory().toString());
        builder.environment().put("VIO_PARTIAL_MODE",featuresEnabled()?"enabled":"disabled");
        var browser=builder.start();
        try{assertTrue(browser.waitFor(90,TimeUnit.SECONDS));assertEquals(0,browser.exitValue(),"See isolated browser.log");assertEquals(2,decisions.get());assertEquals(2,streams.get());}
        finally{if(browser.isAlive())browser.destroyForcibly();}
    }
}
