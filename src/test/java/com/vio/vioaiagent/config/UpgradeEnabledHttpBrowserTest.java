package com.vio.vioaiagent.config;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.reference.LocalPokeApiRepository;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import reactor.core.publisher.Flux;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real enabled Spring/HTTP/events/browser; offline model and public-data fixture, not provider-quality acceptance. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.profiles.active=local,deepseek,v11-preview","vio.model.deepseek.api-key=test-only",
    "spring.ai.dashscope.api-key=test-only","search-api.api-key=test-only",
    "spring.ai.mcp.client.enabled=false","vio.security.enabled=false","vio.local-accounts.enabled=false",
    "vio.local-guides.semantic.enabled=false","vio.local-guides.rerank.enabled=false",
    "vio.agent.guide-research.enabled=false","vio.agent.specialists.enabled=true",
    "vio.run.answer-streaming-enabled=true","vio.agent.v1.enabled=false","vio.tracing.enabled=false",
    "vio.http.cors.allowed-origins[0]=http://127.0.0.1:4327"
})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class UpgradeEnabledHttpBrowserTest {
    @org.springframework.boot.test.context.TestConfiguration
    static class GateConfiguration {
        @org.springframework.context.annotation.Bean ReconnectGate reconnectGate(){return new ReconnectGate();}
    }
    @org.springframework.web.bind.annotation.RestController
    @org.springframework.boot.test.context.TestComponent
    static class ReconnectGate {
        volatile CountDownLatch latch=new CountDownLatch(1);
        @org.springframework.web.bind.annotation.PostMapping("/upgrade-test/release")
        void release(){latch.countDown();}
    }
    static final Path DATA=Path.of("artifacts","u6-enabled-"+UUID.randomUUID()).toAbsolutePath();
    static final String QUESTION="查询胖丁的属性和种族值，作为培养资料，只列资料事实。";
    @DynamicPropertySource static void paths(DynamicPropertyRegistry properties){
        properties.add("vio.local-history.path",()->DATA.resolve("history.sqlite").toString());
        properties.add("vio.local-teams.path",()->DATA.resolve("teams.sqlite").toString());
        properties.add("vio.local-accounts.path",()->DATA.resolve("accounts.sqlite").toString());
        properties.add("vio.local-guides.path",()->DATA.resolve("guides.sqlite").toString());
    }
    @LocalServerPort int port;
    @MockitoBean(name="deepSeekChatModel") ChatModel model;
    @MockitoSpyBean LocalPokeApiRepository repository;
    @MockitoSpyBean com.vio.vioaiagent.pokemon.reference.LocalBuildCheckService buildChecks;
    @org.springframework.beans.factory.annotation.Autowired com.vio.vioaiagent.pokemon.repository.SavedTeamRepository teams;
    @org.springframework.beans.factory.annotation.Autowired ReconnectGate reconnectGate;
    long trainingTeamId;
    final List<Prompt> prompts=new CopyOnWriteArrayList<>();
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        Files.createDirectories(DATA);prompts.clear();
        reconnectGate.latch=new CountDownLatch(1);
        doAnswer(inv->{
            String query=inv.getArgument(0);boolean puff=Set.of("39","胖丁","jigglypuff").contains(query);
            if(!puff&&!Set.of("7","杰尼龟","squirtle").contains(query))throw new IllegalArgumentException("Fixture query not supported");
            if(!"pokeapi-reference-v1".equals(inv.getArgument(1)))throw new IllegalArgumentException("Fixture version mismatch");
            long id=puff?39:7;String slug=puff?"jigglypuff":"squirtle",name=puff?"胖丁":"杰尼龟";
            var fact=new PokemonFact(id,id,(int)id,slug,name,slug,name,"pokeapi-reference-v1","pokemon-scarlet-violet",
                    "https://pokeapi.co/api/v2/pokemon/"+id+"/","sha256:"+"a".repeat(64),"LOCAL_REFERENCE",puff?"一般":"水",puff?"妖精":null,
                    puff?115:44,puff?45:48,puff?20:65,puff?45:50,puff?25:64,puff?20:43);
            return new LocalPokeApiRepository.Detail(fact,List.of(puff?"cute-charm":"torrent"),List.of(),"2026-11-13T00:00:00Z","Controlled offline public-data fixture");
        }).when(repository).detail(anyString(),anyString());
        // This UI case exercises unavailable evidence, never external network or invented learning records.
        doAnswer(inv->{
            var request=(com.vio.vioaiagent.pokemon.reference.BuildCheckFacts.Request)inv.getArgument(0);
            String syntax=com.vio.vioaiagent.pokemon.reference.BuildCheckFacts.syntax(request.build());
            var missing=com.vio.vioaiagent.pokemon.reference.BuildCheckFacts.missing(request.build());
            String ability=com.vio.vioaiagent.pokemon.reference.BuildCheckFacts.abilityStatus(request.build(),null);
            var moves=new ArrayList<com.vio.vioaiagent.pokemon.reference.BuildCheckFacts.Move>();
            for(int i=0;i<request.build().moves().size();i++){
                String move=request.build().moves().get(i),key=com.vio.vioaiagent.pokemon.reference.BuildCheckFacts.normalize(move);
                moves.add(new com.vio.vioaiagent.pokemon.reference.BuildCheckFacts.Move(i+1,move,key,com.vio.vioaiagent.pokemon.reference.BuildCheckFacts.moveStatus(key,null,null),null));
            }
            return com.vio.vioaiagent.pokemon.reference.BuildCheckFacts.seal(new com.vio.vioaiagent.pokemon.reference.BuildCheckFacts.Report(com.vio.vioaiagent.pokemon.reference.BuildCheckFacts.SCHEMA,request,syntax,missing,
                    com.vio.vioaiagent.pokemon.reference.BuildCheckFacts.outcome(syntax,missing,null,ability,moves),null,ability,moves,com.vio.vioaiagent.pokemon.reference.BuildCheckFacts.SCOPE,""));
        }).when(buildChecks).check(any(com.vio.vioaiagent.pokemon.reference.BuildCheckFacts.Request.class));
        var savedBuild=new com.vio.vioaiagent.pokemon.domain.TeamBuild(List.of("water-gun","protect"),"torrent","","modest",51,
                new com.vio.vioaiagent.pokemon.domain.TeamBuild.Stats(0,0,4,252,0,252),com.vio.vioaiagent.pokemon.domain.TeamBuild.Stats.filled(31),"water");
        trainingTeamId=teams.create(new com.vio.vioaiagent.pokemon.domain.SavedTeam(0,"upgrade-test-trainer","培养快照测试","casual-v1","pokeapi-reference-v1",
                List.of(new com.vio.vioaiagent.pokemon.domain.TeamMember(0,7,1,savedBuild)))).id();
        when(model.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build());
        when(model.call(any(Prompt.class))).thenAnswer(inv->{
            Prompt prompt=inv.getArgument(0);prompts.add(prompt);
            boolean hasEvidence=prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
            if(hasEvidence){
                if(prompt.getContents().contains("U2_RECONNECT_FIXTURE"))assertTrue(reconnectGate.latch.await(30,TimeUnit.SECONDS),"Browser did not release reconnect fixture");
                else Thread.sleep(2000);
                return stop();
            }
            boolean damage=prompt.getContents().contains("U2_DAMAGE_FIXTURE");
            boolean team=prompt.getContents().contains("U2_TEAM_FIXTURE");
            boolean training=prompt.getContents().contains("U2_TRAINING_FIXTURE");
            String arguments=damage?"{\"request\":{\"generation\":9,\"dataReleaseId\":\"pokeapi-reference-v1\",\"attacker\":{\"species\":\"squirtle\"},\"defender\":{\"species\":\"squirtle\"},\"move\":\"tackle\",\"field\":{\"gameType\":\"Singles\",\"weather\":null,\"terrain\":null}}}":"{\"query\":\"39\",\"dataReleaseId\":\"pokeapi-reference-v1\"}";
            if(team)arguments="{\"queries\":[\"7\",\"39\"],\"dataReleaseId\":\"pokeapi-reference-v1\"}";
            if(training)arguments="{\"teamId\":"+trainingTeamId+"}";
            var call=new AssistantMessage.ToolCall("enabled-"+UUID.randomUUID(),"function",training?"readCurrentTeam":team?"lookupPokemonTeamReference":damage?"calculateBattleDamage":"lookupPokemonReference",arguments);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("PRIVATE_PLAN_MUST_NOT_BE_PUBLIC",Map.of(),List.of(call)))));
        });
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(stop()));
    }
    ChatResponse stop(){return new ChatResponse(List.of(new Generation(new AssistantMessage("胖丁是一般／妖精属性，HP种族值为115；这里只陈述已读取的资料。"))));}
    JsonNode request(String path,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).timeout(Duration.ofSeconds(15));
        if(body!=null)builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        var response=HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(body==null?200:202,response.statusCode());return json.readTree(response.body());
    }
    @Test void enabledPropertiesReachRealRunAndPreserveFactAndRoleBoundaries() throws Exception {
        var runtime=request("/chat/runtime",null);
        for(Class<?> component:List.of(com.vio.vioaiagent.agent.SavedTrainingRoadmap.class,com.vio.vioaiagent.agent.SavedTrainingResources.class,com.vio.vioaiagent.agent.TrainingIntentState.class,com.vio.vioaiagent.agent.TrainingTargetCheck.class,com.vio.vioaiagent.agent.TrainingLearningCheck.class,com.vio.vioaiagent.agent.TrainingResourceBudget.class,com.vio.vioaiagent.agent.SavedTrainingGymReview.class,com.vio.vioaiagent.agent.TrainingStatProjection.class,com.vio.vioaiagent.agent.TrainingLevelConstraints.class,com.vio.vioaiagent.agent.TrainingProgressCheck.class,com.vio.vioaiagent.agent.ReferenceRulesReply.class,com.vio.vioaiagent.agent.GymFocusedFactReply.class,com.vio.vioaiagent.pokemon.gym.GymResourcePolicy.class,com.vio.vioaiagent.pokemon.calculator.StaticStatCalculator.class)){
            try(var stream=component.getResourceAsStream("/"+component.getName().replace('.','/')+".class")){
                assertNotNull(stream);
                String expected=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
                assertEquals(expected,runtime.path("implementationSha256").path(component.getSimpleName()).asText());
            }
        }
        var body=Map.of("conversationId","enabled-"+UUID.randomUUID(),"prompt",QUESTION,"gameReleaseId","pokemon-scarlet-violet","battleFormat","casual-singles","rulesetVersionId","casual-v1","language","zh-CN","dataReleaseId","pokeapi-reference-v1","timeoutSeconds",30);
        String id=request("/chat/runs",json.writeValueAsString(body)).path("runId").asText();
        long deadline=System.nanoTime()+Duration.ofSeconds(25).toNanos();JsonNode events;
        do{Thread.sleep(100);events=request("/chat/runs/"+id+"/events?afterEventId=0",null).path("events");}
        while(events.findValuesAsText("type").stream().noneMatch(type->Set.of("run.completed","run.failed","run.timed_out").contains(type))&&System.nanoTime()<deadline);
        var cards=new ArrayList<JsonNode>();boolean context=false,completed=false;var answer=new StringBuilder();
        for(var event:events){
            String type=event.path("type").asText();if(type.equals("fact.card"))cards.add(event.path("payload").path("answerBlock"));
            if(type.equals("answer.delta"))answer.append(event.path("payload").path("text").asText());
            if(type.equals("run.completed"))completed=true;
            if(event.path("payload").toString().contains("CONTEXT_READY"))context=true;
        }
        assertTrue(completed,"Run did not complete");assertEquals(1,cards.size());assertEquals(39,cards.getFirst().path("formId").asInt());
        assertEquals(115,cards.getFirst().path("stats").get(0).asInt());assertTrue(answer.toString().contains("胖丁"));assertFalse(answer.toString().contains("PRIVATE_PLAN"));
        assertTrue(context,"Context assembly not observed");assertTrue(prompts.stream().anyMatch(p->p.getContents().contains("training-build-review@1")),"Skill not injected");
        for(Prompt prompt:prompts)if(prompt.getContents().contains("Bundled task skill")){
            var options=(ToolCallingChatOptions)prompt.getOptions();
            assertTrue(options.getToolCallbacks().stream().noneMatch(tool->Set.of("previewTeamChanges","doTerminalOperation").contains(tool.getToolDefinition().name())));
        }
        Files.writeString(DATA.resolve("enabled-run-events.json"),events.toPrettyString());
    }
    @Test void realBrowserDisplaysReplaysAndOpensTheVerifiedFact() throws Exception {
        assumeTrue(Files.exists(Path.of("vio-ai-agent-frontend/node_modules/playwright/index.mjs")),"frontend npm ci required");
        var process=new ProcessBuilder("node","tools/campus/upgrade-enabled-browser.mjs").redirectErrorStream(true).redirectOutput(DATA.resolve("browser.log").toFile());
        process.environment().put("VIO_UPGRADE_TEST_API","http://127.0.0.1:"+port);process.environment().put("VIO_UPGRADE_TEST_OUTPUT",DATA.toString());
        process.environment().put("VIO_UPGRADE_TRAINING_TEAM_ID",Long.toString(trainingTeamId));
        var child=process.start();
        try{assertTrue(child.waitFor(90,TimeUnit.SECONDS),"Browser exceeded timeout");assertEquals(0,child.exitValue(),Files.readString(DATA.resolve("browser.log")));}
        finally{reconnectGate.release();if(child.isAlive())child.destroyForcibly();}
    }
}
