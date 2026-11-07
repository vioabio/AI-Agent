package com.vio.vioaiagent.config;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.controller.*;
import com.vio.vioaiagent.pokemon.domain.*;
import com.vio.vioaiagent.pokemon.repository.*;
import com.vio.vioaiagent.pokemon.service.PokemonResourceService;
import com.vio.vioaiagent.pokemon.team.*;
import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.security.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real security/MVC/Run/approval/SQLite wiring; executor and reference facts are controlled fixtures. */
class TeamWorkflowHttpIntegrationTest {
    @TempDir Path directory;
    static final String RELEASE="pokeapi-reference-v1";
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    Browser trainer;

    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({LocalAccountSecurityConfiguration.class,LocalAccountController.class,AuthSessionController.class,
            AgentRunController.class,TeamWorkflowController.class,HitlController.class})
    static class Config implements WebMvcConfigurer {
        @Bean LocalAccountService accounts(Environment e){return new LocalAccountService(e.getRequiredProperty("test.root")+"/accounts.sqlite");}
        @Bean LocalDataClaimService claims(LocalAccountService a,Environment e){return new LocalDataClaimService(a,e.getRequiredProperty("test.root")+"/history.sqlite",e.getRequiredProperty("test.root")+"/teams.sqlite");}
        @Bean OidcProperties oidc(){return mock(OidcProperties.class);}
        @Bean OidcPrincipalPolicyAdapter adapter(){return mock(OidcPrincipalPolicyAdapter.class);}
        @Bean SecurityProperties security(){return new SecurityProperties(false,Map.of(),List.of(),List.of());}
        @Bean CorsProperties cors(){return new CorsProperties(List.of("http://localhost:3000"));}
        @Bean ApiKeyAuthInterceptor identity(){return new ApiKeyAuthInterceptor(new TokenStore(security()),security());}
        @Override public void addInterceptors(InterceptorRegistry r){r.addInterceptor(identity()).addPathPatterns("/chat/**","/teams/**");}
        @Bean SqliteSavedTeamRepository teams(Environment e){return new SqliteSavedTeamRepository(new ObjectMapper(),e.getRequiredProperty("test.root")+"/teams.sqlite");}
        @Bean SqliteLocalHistoryRepository history(Environment e){return new SqliteLocalHistoryRepository(e.getRequiredProperty("test.root")+"/history.sqlite",new ObjectMapper().findAndRegisterModules());}
        @Bean InMemoryRunRepository runs(){return new InMemoryRunRepository();}
        @Bean InMemoryEventRepository events(){return new InMemoryEventRepository();}
        @Bean(destroyMethod="shutdown") HitlManager hitl(){return new HitlManager();}
        @Bean PokemonFactRepository facts(){
            var f=mock(PokemonFactRepository.class);
            when(f.findDataRelease(RELEASE)).thenReturn(Optional.of(new VersionedDataRelease(RELEASE,"pokemon-scarlet-violet","https://pokeapi.co/","sha256:fixture","LOCAL_REFERENCE")));
            when(f.isQueryableRelease(any())).thenReturn(true);
            when(f.findRuleset("casual-v1")).thenReturn(Optional.of(new RulesetBinding("casual-v1","pokemon-scarlet-violet","测试休闲单打",true)));
            when(f.isAvailable(7,RELEASE,"casual-v1")).thenReturn(true);
            when(f.isAvailable(4,RELEASE,"casual-v1")).thenReturn(true);
            when(f.findByFormId(7,RELEASE)).thenReturn(Optional.of(new PokemonFact(7,7,7,"squirtle","杰尼龟","squirtle","杰尼龟",RELEASE,"pokemon-scarlet-violet","https://pokeapi.co/api/v2/pokemon/7/","sha256:fixture","LOCAL_REFERENCE","水",null,44,48,65,50,64,43)));
            when(f.findByFormId(4,RELEASE)).thenReturn(Optional.of(new PokemonFact(4,4,4,"charmander","小火龙","charmander","小火龙",RELEASE,"pokemon-scarlet-violet","https://pokeapi.co/api/v2/pokemon/4/","sha256:fixture","LOCAL_REFERENCE","火",null,39,52,43,60,50,65)));
            return f;
        }
        @Bean PokemonResourceService resources(PokemonFactRepository f,SqliteSavedTeamRepository t){return new PokemonResourceService(f,t);}
        @Bean ResourceController resourcesController(SqliteLocalHistoryRepository h,PokemonResourceService p,SecurityProperties s){return new ResourceController(h,p,new InMemoryFavoriteRepository(),s,null,null,null);}
        @Bean TeamWorkflowService workflow(PokemonResourceService p,SqliteSavedTeamRepository t,InMemoryRunRepository r,HitlManager h){return new TeamWorkflowService(p,t,r,h);}
        @Bean AtomicInteger planning(){return new AtomicInteger();}
        @Bean(destroyMethod="shutdown") PokemonAgentRunService service(InMemoryRunRepository r,InMemoryEventRepository e,SqliteLocalHistoryRepository h,TeamWorkflowService w,AtomicInteger planning){
            return new PokemonAgentRunService(r,e,h,h,c->{
                String receipt=TeamChangePresentation.finishIfPending(w,c);if(receipt!=null)return receipt;
                planning.incrementAndGet();
                long id=Long.parseLong(c.run().prompt().substring(c.run().prompt().lastIndexOf(':')+1));
                w.readCurrentTeam(id);
                if(c.run().prompt().contains("calculateBattleDamage"))w.observeCalculation("calculateBattleDamage","[tool.error] 受控计算失败");
                List<TeamWorkflowService.BuildChange> changes=List.of(new TeamWorkflowService.BuildChange(1,null,null,null,null,51,null,null));
                if(c.run().prompt().startsWith("multi")) changes=List.of(
                    new TeamWorkflowService.BuildChange(1,null,null,null,"timid",51,
                        new TeamWorkflowService.StatChange(0,0,4,252,0,252),new TeamWorkflowService.StatChange(null,0,null,null,null,null)),
                    new TeamWorkflowService.BuildChange(2,List.of("surf","protect"),null,"",null,null,null,null));
                if(c.run().prompt().startsWith("invalid-multi")) changes=List.of(
                    new TeamWorkflowService.BuildChange(1,null,null,null,null,51,null,null),
                    new TeamWorkflowService.BuildChange(2,null,null,null,null,null,new TeamWorkflowService.StatChange(252,252,252,0,0,0),null));
                w.previewTeamChanges(id,changes,"按用户明确要求调整练习配置");
                return "等待用户确认";
            },Executors.newSingleThreadExecutor(),Executors.newSingleThreadScheduledExecutor());
        }
    }

    @BeforeEach void start() throws Exception {
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("local");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",Map.of("vio.local-accounts.enabled","true","test.root",directory.toString())));
        context.register(Config.class);context.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
        trainer=new Browser("trainer");
    }
    @AfterEach void close(){if(context!=null)context.close();RequestContext.clear();}

    class Browser {
        String csrf,owner;MockHttpSession session;
        Browser(String name) throws Exception {
            var initial=mvc.perform(get("/auth/session")).andReturn();csrf=initial.getResponse().getCookie("CSRF-TOKEN").getValue();
            request(post("/auth/local/register"),Map.of("username",name,"password","fixture-password","nickname","HTTP流程测试"),201);
            var login=mvc.perform(write(post("/auth/local/login"),Map.of("username",name,"password","fixture-password"))).andReturn();
            assertEquals(200,login.getResponse().getStatus());session=(MockHttpSession)login.getRequest().getSession(false);
            owner=request(get("/auth/session"),null,200).path("principalId").asText();assertFalse(owner.isBlank());
        }
        MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request,Object body) throws Exception {
            request.cookie(new Cookie("CSRF-TOKEN",csrf)).header("X-CSRF-TOKEN",csrf).header("Origin","http://localhost:3000");
            if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));return request;
        }
        JsonNode request(MockHttpServletRequestBuilder request,Object body,int expected) throws Exception {
            if(session!=null)request.session(session);
            var result=mvc.perform(write(request,body)).andReturn();
            assertEquals(expected,result.getResponse().getStatus(),result.getResponse().getContentAsString());
            String text=result.getResponse().getContentAsString();if(text.isBlank())return json.nullNode();
            JsonNode value=json.readTree(text);return value.has("data")?value.get("data"):value;
        }
    }
    JsonNode newTeam() throws Exception {
        return trainer.request(post("/teams"),Map.of("name","HTTP练习队","rulesetId","casual-v1","dataReleaseId",RELEASE,
                "members",List.of(Map.of("formId",7,"slot",1,"build",Map.of("level",50,"nature","modest","ability","torrent","item","leftovers","moves",List.of("surf"))))),201);
    }
    JsonNode newRun(long team,int seconds,boolean failedCalculation) throws Exception {
        return newRun(team,seconds,failedCalculation?"calculateBattleDamage":"preview");
    }
    JsonNode newRun(long team,int seconds,String mode) throws Exception {
        return trainer.request(post("/chat/runs"),Map.of("prompt",mode+":"+team,
                "gameReleaseId","pokemon-scarlet-violet","battleFormat","casual-singles","rulesetVersionId","casual-v1","dataReleaseId",RELEASE,"timeoutSeconds",seconds,"conversationId","http-"+UUID.randomUUID()),202);
    }
    JsonNode waitFor(String run,String expected) throws Exception {
        Instant until=Instant.now().plusSeconds(8);JsonNode latest;
        do {latest=trainer.request(get("/chat/runs/"+run),null,200);if(expected.equals(latest.path("status").asText())
                && (expected.equals("WAITING_APPROVAL")||context.getBean(InMemoryEventRepository.class).findAfter(run,0).stream().anyMatch(e->e.type().isTerminal())))return latest;Thread.sleep(15);}while(Instant.now().isBefore(until));
        fail("Run did not reach "+expected+": "+latest.path("status"));return latest;
    }
    JsonNode preview(String run) throws Exception {waitFor(run,"WAITING_APPROVAL");return trainer.request(get("/chat/runs/"+run+"/team-change"),null,200);}
    JsonNode decide(String run,String approval,boolean approved,int expected) throws Exception {
        return trainer.request(post("/chat/runs/"+run+"/team-change/decision"),Map.of("approvalId",approval,"approved",approved),expected);
    }
    SavedTeamRepository.Snapshot snapshot(long team){return context.getBean(SqliteSavedTeamRepository.class).snapshotForOwner(team,trainer.owner);}
    void assertSingleTerminal(String run,AgentEventType terminal){
        var events=context.getBean(InMemoryEventRepository.class).findAfter(run,0);
        assertEquals(List.of(terminal),events.stream().filter(e->e.type().isTerminal()).map(AgentEvent::type).toList());
    }
    void assertNoAnswer(JsonNode run) throws Exception {
        var history=trainer.request(get("/chat/conversations/"+run.path("conversationId").asText()+"/messages"),null,200);
        assertTrue(history.isArray());for(var message:history)assertNotEquals("assistant",message.path("role").asText());
    }

    @Test void confirmedSaveReadbackAndDuplicateDecisionHaveOneDurableRevision() throws Exception {
        long team=newTeam().path("id").asLong();var before=snapshot(team);var run=newRun(team,30,false);String id=run.path("runId").asText();
        String approval=preview(id).path("preview").path("proposalId").asText();assertEquals(before,snapshot(team));
        assertTrue(decide(id,approval,true,200).path("allowed").asBoolean());waitFor(id,"COMPLETED");
        var saved=snapshot(team);assertEquals(before.revision()+1,saved.revision());assertEquals(51,saved.team().members().getFirst().build().level());
        var old=before.team().members().getFirst().build();var actual=saved.team().members().getFirst().build();
        assertEquals(new TeamBuild(old.moves(),old.ability(),old.item(),old.nature(),51,old.evs(),old.ivs()),actual);
        var outcome=trainer.request(get("/chat/runs/"+id+"/team-change"),null,200);assertEquals("APPLIED",outcome.path("status").asText());
        var reopened=new SqliteSavedTeamRepository(new ObjectMapper(),directory.resolve("teams.sqlite").toString());assertEquals(saved,reopened.snapshotForOwner(team,trainer.owner));
        var persisted=new SqliteLocalHistoryRepository(directory.resolve("history.sqlite").toString(),json).findByConversationForUser(run.path("conversationId").asText(),trainer.owner);
        assertTrue(persisted.stream().anyMatch(m->m.role().equals("assistant")&&m.content().equals(outcome.path("message").asText())));
        decide(id,approval,true,409);assertEquals(saved,snapshot(team));assertEquals(1,context.getBean(AtomicInteger.class).get());assertSingleTerminal(id,AgentEventType.RUN_COMPLETED);
    }
    @Test void denialNeverWritesOrPublishesASavedAnswer() throws Exception {
        long team=newTeam().path("id").asLong();var before=snapshot(team);var run=newRun(team,30,false);String id=run.path("runId").asText();
        String approval=preview(id).path("preview").path("proposalId").asText();assertFalse(decide(id,approval,false,200).path("allowed").asBoolean());
        waitFor(id,"FAILED");assertEquals(before,snapshot(team));assertNoAnswer(run);assertSingleTerminal(id,AgentEventType.RUN_FAILED);decide(id,approval,true,409);
    }
    @Test void cancelledRunCannotConsumeItsStillPendingApproval() throws Exception {
        long team=newTeam().path("id").asLong();var before=snapshot(team);var run=newRun(team,30,false);String id=run.path("runId").asText();
        String approval=preview(id).path("preview").path("proposalId").asText();trainer.request(post("/chat/runs/"+id+"/cancel"),Map.of(),200);
        decide(id,approval,true,409);
        assertEquals(HitlManager.ApprovalStatus.PENDING,context.getBean(HitlManager.class).getApproval(approval).status());
        assertEquals(before,snapshot(team));assertNoAnswer(run);assertSingleTerminal(id,AgentEventType.RUN_CANCELLED);
    }
    @Test void deadlineExpiredPreviewReturns410AndCannotSave() throws Exception {
        long team=newTeam().path("id").asLong();var before=snapshot(team);var run=newRun(team,1,false);String id=run.path("runId").asText();
        String approval=preview(id).path("preview").path("proposalId").asText();waitFor(id,"TIMED_OUT");decide(id,approval,true,410);
        assertEquals(before,snapshot(team));assertNoAnswer(run);assertSingleTerminal(id,AgentEventType.RUN_TIMED_OUT);
    }
    @Test void foreignAccountAndSpoofedHeadersCannotReadOrApprove() throws Exception {
        long team=newTeam().path("id").asLong();var run=newRun(team,30,false);String id=run.path("runId").asText(),approval=preview(id).path("preview").path("proposalId").asText();
        var other=new Browser("other");
        other.request(get("/teams/"+team).header("X-User-Id",trainer.owner),null,404);
        other.request(get("/chat/runs/"+id+"/team-change").header("X-User-Id",trainer.owner),null,404);
        other.request(post("/chat/runs/"+id+"/team-change/decision").header("X-User-Id",trainer.owner),Map.of("approvalId",approval,"approved",true),404);
        decide(id,"different-approval",true,404);
        trainer.request(post("/hitl/approvals/"+approval+"?runId="+id+"&approved=true"),Map.of(),403);
        assertEquals(HitlManager.ApprovalStatus.PENDING,context.getBean(HitlManager.class).getApproval(approval).status());assertEquals(50,snapshot(team).team().members().getFirst().build().level());
    }
    @Test void missingCsrfAndForeignOriginCannotConfirm() throws Exception {
        long team=newTeam().path("id").asLong();String id=newRun(team,30,false).path("runId").asText(),approval=preview(id).path("preview").path("proposalId").asText();
        String body=json.writeValueAsString(Map.of("approvalId",approval,"approved",true));
        assertEquals(403,mvc.perform(post("/chat/runs/"+id+"/team-change/decision").session(trainer.session).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(post("/chat/runs/"+id+"/team-change/decision").session(trainer.session).cookie(new Cookie("CSRF-TOKEN",trainer.csrf)).header("X-CSRF-TOKEN",trainer.csrf).header("Origin","https://untrusted.example").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
        assertEquals(HitlManager.ApprovalStatus.PENDING,context.getBean(HitlManager.class).getApproval(approval).status());assertEquals(50,snapshot(team).team().members().getFirst().build().level());
    }
    @Test void editThenRestoreStillConflictsWithTheOriginalPreview() throws Exception {
        var original=newTeam();long team=original.path("id").asLong();String id=newRun(team,30,false).path("runId").asText(),approval=preview(id).path("preview").path("proposalId").asText();
        var edit=original.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)edit).put("name","另一窗口编辑");
        trainer.request(patch("/teams/"+team),edit,200);trainer.request(patch("/teams/"+team),original,200);var before=snapshot(team);
        decide(id,approval,true,200);waitFor(id,"COMPLETED");var outcome=trainer.request(get("/chat/runs/"+id+"/team-change"),null,200);
        assertEquals("CONFLICT",outcome.path("status").asText());assertTrue(outcome.path("message").asText().contains("未保存"));assertEquals(before,snapshot(team));assertSingleTerminal(id,AgentEventType.RUN_COMPLETED);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void failedReadOrRequiredCalculationProducesNoPreviewOrWrite(boolean failedRead) throws Exception {
        long team=newTeam().path("id").asLong();var before=snapshot(team);
        if(failedRead)when(context.getBean(PokemonFactRepository.class).findByFormId(7,RELEASE)).thenThrow(new IllegalStateException("受控资料故障"));
        var run=newRun(team,30,!failedRead);String id=run.path("runId").asText();waitFor(id,"FAILED");
        trainer.request(get("/chat/runs/"+id+"/team-change"),null,404);assertEquals(before,snapshot(team));assertNoAnswer(run);assertSingleTerminal(id,AgentEventType.RUN_FAILED);
    }

    JsonNode multiMemberTeam() throws Exception {
        var build=Map.of("level",50,"nature","modest","ability","torrent","item","leftovers","moves",List.of("surf"),
                "evs",Map.of("hp",4,"atk",0,"def",0,"spa",252,"spd",0,"spe",252));
        var secondBuild=new HashMap<String,Object>(build);secondBuild.put("ability","blaze");
        return trainer.request(post("/teams"),Map.of("name","多成员练习队伍","rulesetId","casual-v1","dataReleaseId",RELEASE,
                "members",List.of(Map.of("formId",7,"slot",1,"build",build),Map.of("formId",4,"slot",2,"build",secondBuild))),201);
    }
    @Test void multiMemberFieldsArePreviewedAndSavedAtomicallyWithSlots() throws Exception {
        long team=multiMemberTeam().path("id").asLong();var before=snapshot(team);
        var run=newRun(team,30,"multi");String id=run.path("runId").asText();var proposal=preview(id).path("preview");
        assertEquals(before,snapshot(team));assertEquals(6,proposal.path("changes").size());
        assertTrue(proposal.path("changes").findValues("slot").stream().anyMatch(n->n.asInt()==2));
        decide(id,proposal.path("proposalId").asText(),true,200);waitFor(id,"COMPLETED");
        var after=snapshot(team);var members=new ArrayList<>(before.team().members());var first=members.getFirst();var second=members.get(1);
        members.set(0,new TeamMember(first.id(),first.formId(),first.slot(),new TeamBuild(first.build().moves(),first.build().ability(),first.build().item(),"timid",51,
                new TeamBuild.Stats(0,0,4,252,0,252),new TeamBuild.Stats(31,0,31,31,31,31))));
        members.set(1,new TeamMember(second.id(),second.formId(),second.slot(),new TeamBuild(List.of("surf","protect"),second.build().ability(),"",second.build().nature(),second.build().level(),second.build().evs(),second.build().ivs())));
        var expected=new SavedTeam(before.team().id(),before.team().ownerId(),before.team().name(),before.team().rulesetId(),before.team().dataReleaseId(),members);
        assertEquals(expected,after.team());assertEquals(before.revision()+1,after.revision());
        var outcome=trainer.request(get("/chat/runs/"+id+"/team-change"),null,200);
        assertEquals("APPLIED",outcome.path("status").asText());assertTrue(outcome.path("message").asText().contains("第1位 · 杰尼龟"));assertTrue(outcome.path("message").asText().contains("第2位 · 小火龙"));
        assertEquals(after,new SqliteSavedTeamRepository(new ObjectMapper(),directory.resolve("teams.sqlite").toString()).snapshotForOwner(team,trainer.owner));
    }
    @Test void invalidSecondMemberRejectsTheEntireMultiChangeWithoutPreviewOrWrite() throws Exception {
        long team=multiMemberTeam().path("id").asLong();var before=snapshot(team);var run=newRun(team,30,"invalid-multi");String id=run.path("runId").asText();
        waitFor(id,"FAILED");assertEquals(before,snapshot(team));assertNoAnswer(run);
        trainer.request(get("/chat/runs/"+id+"/team-change"),null,404);assertSingleTerminal(id,AgentEventType.RUN_FAILED);
    }
}
