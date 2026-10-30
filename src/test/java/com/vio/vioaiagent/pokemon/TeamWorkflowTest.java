package com.vio.vioaiagent.pokemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.domain.*;
import com.vio.vioaiagent.pokemon.repository.*;
import com.vio.vioaiagent.pokemon.service.*;
import com.vio.vioaiagent.pokemon.team.TeamWorkflowService;
import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.security.HitlManager;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeamWorkflowTest {
    @TempDir Path directory;
    static final String RELEASE="pokeapi-reference-v1", OWNER="local:trainer-a";
    static final TeamWorkflowService.BuildChange CHANGE=new TeamWorkflowService.BuildChange(1,null,null,null,null,51,null,null);
    Fixture fixture() { return new Fixture(directory.resolve(UUID.randomUUID()+".sqlite")); }
    class Fixture implements AutoCloseable {
        final SqliteSavedTeamRepository teams;
        final InMemoryRunRepository runs=new InMemoryRunRepository();
        final HitlManager hitl=new HitlManager();
        final PokemonResourceService resources=mock(PokemonResourceService.class);
        final TeamWorkflowService workflow;
        final SavedTeam team;
        AgentRun run;
        Fixture(Path path) {
            teams=new SqliteSavedTeamRepository(new ObjectMapper(),path.toString());
            team=teams.create(new SavedTeam(0,OWNER,"练习队", "casual-v1",RELEASE,List.of(new TeamMember(0,7,1,new TeamBuild(List.of("surf"),"torrent","leftovers","modest",50,null,null)))));
            when(resources.findFactByFormId(RELEASE,7)).thenReturn(new PokemonFact(7,7,7,"squirtle","杰尼龟","squirtle","杰尼龟",RELEASE,"pokemon-scarlet-violet","https://pokeapi.co/api/v2/pokemon/7/","sha256:fixture","LOCAL_REFERENCE","水",null,44,48,65,50,64,43));
            workflow=new TeamWorkflowService(resources,teams,runs,hitl);
            run=newRun(OWNER,Instant.now().plusSeconds(60));
            bind();
        }
        AgentRun newRun(String owner,Instant deadline) {
            return newRun(owner,deadline,"将第一名等级改为51，请预览");
        }
        AgentRun newRun(String owner,Instant deadline,String prompt) {
            var value=AgentRun.create("run-"+UUID.randomUUID(),"session","conversation",owner,prompt, "pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN",null,RELEASE,null,null,"v1","trace",null,deadline);
            runs.save(value); value.transitionTo(AgentRunStatus.RUNNING); runs.save(value); return value;
        }
        void bind() {
            ToolExecutionContextHolder.set(new ToolExecutionContext(run.userId(),run.id(),Set.of("trainer"),"trace",null,event->{
                if(event.eventType().equals("approval.required")) { run.transitionTo(AgentRunStatus.WAITING_APPROVAL);runs.save(run); }
            }));
        }
        TeamWorkflowService.Preview preview() { workflow.readCurrentTeam(team.id());return workflow.previewTeamChanges(team.id(),List.of(CHANGE),"按用户要求修改练习等级，不推断胜率"); }
        void approve(TeamWorkflowService.Preview preview) {
            assertTrue(hitl.decide(preview.proposalId(),run.userId(),run.id(),true,"").allowed());
            run.transitionTo(AgentRunStatus.RUNNING);runs.save(run);
        }
        AgentRunContext context() {
            return new AgentRunContext(run,null,List.of(),new RunMutationBoundary() {
                public void check() { if(run.status()!=AgentRunStatus.RUNNING || !Instant.now().isBefore(run.deadlineAt()))throw new CancellationException(); }
                public <T>T write(Supplier<T> action) { check();return action.get(); }
            });
        }
        public void close() { ToolExecutionContextHolder.clear(); hitl.shutdown(); }
    }
    @Test void previewNeverWritesAndConfirmedReceiptIsIdempotent() {
        try(var f=fixture()) {
            var before=f.teams.snapshotForOwner(f.team.id(),OWNER);var preview=f.preview();
            assertEquals(50,f.teams.findByIdForOwner(f.team.id(),OWNER).orElseThrow().members().getFirst().build().level());
            assertEquals("等级",preview.changes().getFirst().field());
            assertThrows(IllegalStateException.class,()->f.workflow.finishIfPending(f.context()));
            f.approve(preview);String answer=f.workflow.finishIfPending(f.context());
            assertTrue(answer.contains("已保存并回读"));
            var saved=f.teams.snapshotForOwner(f.team.id(),OWNER);
            assertEquals(51,saved.team().members().getFirst().build().level());
            assertEquals("modest",saved.team().members().getFirst().build().nature());
            assertEquals(before.revision()+1,saved.revision());
            assertEquals(answer,f.workflow.finishIfPending(f.context()));
            assertEquals(saved.revision(),f.teams.snapshotForOwner(f.team.id(),OWNER).revision());
            assertEquals("APPLIED",f.workflow.outcome(f.run.id(),OWNER).status());
        }
    }
    @Test void gatewayPreviewCapturesInputsButStillWaitsForExplicitConfirmation() throws Exception {
        try(var f=fixture()) {
            var masker=new com.vio.vioaiagent.security.ParameterMasker();
            var gateway=new ToolExecutionGateway(f.hitl,new com.vio.vioaiagent.security.PathGuard(List.of(directory.toString())),new com.vio.vioaiagent.security.CommandGuard(List.of()),masker,new com.vio.vioaiagent.security.AuditLogger(masker));
            var captured=new ArrayList<ToolGovernanceEvent>();var parent=ToolExecutionContextHolder.peek();
            var context=parent.withEventSink(event->{captured.add(event);parent.eventSink().accept(event);});
            gateway.wrap(org.springframework.ai.support.ToolCallbacks.from(f.workflow));
            try {
                var json=new ObjectMapper();String read=json.writeValueAsString(Map.of("teamId",f.team.id()));
                assertEquals(ToolGovernanceResult.Status.EXECUTED,gateway.execute("readCurrentTeam",read,context).status());
                String input=json.writeValueAsString(Map.of("teamId",f.team.id(),"changes",List.of(Map.of("slot",1,"level",51)),"rationale","private-preview-reason"));
                assertEquals(ToolGovernanceResult.Status.EXECUTED,gateway.execute("previewTeamChanges",input,context).status());
                var started=captured.stream().filter(e->e.eventType().equals("tool.started")&&e.toolName().equals("previewTeamChanges")).findFirst().orElseThrow();
                var proof=(Map<?,?>)started.attributes().get("inputEvidence");
                assertEquals("COMPLETE",proof.get("coverage"));assertFalse(proof.toString().contains("private-preview-reason"));
                var finished=captured.stream().filter(e->e.eventType().equals("tool.finished")&&e.toolName().equals("previewTeamChanges")).findFirst().orElseThrow();
                assertEquals(finished.attributes().get("parameterDigest"),proof.get("parameterDigest"));
                assertEquals(AgentRunStatus.WAITING_APPROVAL,f.run.status());
                assertEquals(f.team,f.teams.findByIdForOwner(f.team.id(),OWNER).orElseThrow());
                assertThrows(IllegalStateException.class,()->f.workflow.finishIfPending(f.context()));
                var preview=f.workflow.outcome(f.run.id(),OWNER).preview();f.approve(preview);f.workflow.finishIfPending(f.context());
                var saved=f.teams.findByIdForOwner(f.team.id(),OWNER).orElseThrow();
                var old=f.team.members().getFirst().build();
                assertEquals(new TeamBuild(old.moves(),old.ability(),old.item(),old.nature(),51,old.evs(),old.ivs()),saved.members().getFirst().build());
            } finally {gateway.shutdown();}
        }
    }
    @Test void noReadNoPreviewAndOtherOwnerCannotReadOrConfirm() {
        try(var f=fixture()) {
            assertThrows(IllegalStateException.class,()->f.workflow.previewTeamChanges(f.team.id(),List.of(CHANGE),"修改"));
            var preview=f.preview();
            assertFalse(f.hitl.decide(preview.proposalId(),"local:trainer-b",f.run.id(),true,"").allowed());
            assertThrows(PokemonResourceNotFoundException.class,()->f.workflow.outcome(f.run.id(),"local:trainer-b"));
            f.run=f.newRun("local:trainer-b",Instant.now().plusSeconds(30));f.bind();
            assertThrows(PokemonResourceNotFoundException.class,()->f.workflow.readCurrentTeam(f.team.id()));
            assertEquals(50,f.teams.findByIdForOwner(f.team.id(),OWNER).orElseThrow().members().getFirst().build().level());
        }
    }
    @Test void ordinaryEditAndEvenRestoredOriginalContentInvalidateOldPreview() {
        try(var f=fixture()) {
            var preview=f.preview(); var original=f.team;
            f.teams.updateForOwner(original.id(),OWNER,new SavedTeam(original.id(),OWNER,"新队名",original.rulesetId(),RELEASE,original.members()));
            f.teams.updateForOwner(original.id(),OWNER,original); // ABA must still conflict.
            f.approve(preview);
            assertTrue(f.workflow.finishIfPending(f.context()).contains("未保存"));
            assertEquals("CONFLICT",f.workflow.outcome(f.run.id(),OWNER).status());
            assertEquals(50,f.teams.findByIdForOwner(original.id(),OWNER).orElseThrow().members().getFirst().build().level());
        }
    }
    @Test void rejectedAndCancelledRunsCannotWriteAndDefaultContextsDenyWrites() {
        try(var f=fixture()) {
            var preview=f.preview();f.hitl.decide(preview.proposalId(),OWNER,f.run.id(),false,"取消");
            assertThrows(IllegalStateException.class,()->f.workflow.finishIfPending(f.context()));
            assertEquals("DENIED",f.workflow.outcome(f.run.id(),OWNER).status());
        }
        try(var f=fixture()) {
            var preview=f.preview();f.approve(preview);f.run.cancel();
            assertThrows(CancellationException.class,()->f.workflow.finishIfPending(f.context()));
            assertEquals(50,f.teams.findByIdForOwner(f.team.id(),OWNER).orElseThrow().members().getFirst().build().level());
        }
        try(var f=fixture()) {
            var preview=f.preview();f.approve(preview);
            assertThrows(IllegalStateException.class,()->f.workflow.finishIfPending(new AgentRunContext(f.run)));
        }
    }
    @Test void sqliteCommitBoundaryRollsBackAndTwoConnectionsCannotBothApply() {
        try(var f=fixture()) {
            var before=f.teams.snapshotForOwner(f.team.id(),OWNER);
            var replacement=new SavedTeam(f.team.id(),OWNER,"替换",f.team.rulesetId(),RELEASE,f.team.members());
            AtomicInteger checks=new AtomicInteger();
            assertThrows(CancellationException.class,()->f.teams.compareAndSet(before,replacement,()->{if(checks.incrementAndGet()==2)throw new CancellationException("deadline crossed before commit");}));
            assertEquals(before,f.teams.snapshotForOwner(f.team.id(),OWNER));
            // Same-database connection below sees the durable revision, not an in-process hash.
            var sharedPath=directory.resolve("shared.sqlite");
            var first=new SqliteSavedTeamRepository(new ObjectMapper(),sharedPath.toString());
            var second=new SqliteSavedTeamRepository(new ObjectMapper(),sharedPath.toString());
            var row=first.create(f.team);var snapshot=first.snapshotForOwner(row.id(),OWNER);
            assertTrue(first.compareAndSet(snapshot,row,()->{}).isPresent());
            assertTrue(second.compareAndSet(snapshot,row,()->{}).isEmpty());
        }
    }
    @Test void existingSqliteTeamsGainRevisionsWithoutLosingTheirPayload() throws Exception {
        Path path=directory.resolve("legacy.sqlite");
        var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+path);
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(source);
        jdbc.execute("CREATE TABLE local_team(id INTEGER PRIMARY KEY AUTOINCREMENT,owner_id TEXT NOT NULL,payload TEXT NOT NULL)");
        var old=new SavedTeam(1,OWNER,"旧队伍","casual-v1",RELEASE,List.of(new TeamMember(0,7,1)));
        jdbc.update("INSERT INTO local_team(id,owner_id,payload) VALUES(1,?,?)",OWNER,new ObjectMapper().writeValueAsString(old));
        var migrated=new SqliteSavedTeamRepository(new ObjectMapper(),path.toString());
        var snapshot=migrated.snapshotForOwner(1,OWNER);
        assertEquals(old,snapshot.team());assertEquals(0,snapshot.revision());
        migrated.updateForOwner(1,OWNER,old);
        assertEquals(1,new SqliteSavedTeamRepository(new ObjectMapper(),path.toString()).snapshotForOwner(1,OWNER).revision());
    }
    @Test void failedFactsAndFailedCalculationNeverBecomeEvidenceForAProposal() {
        try(var f=fixture()) {
            when(f.resources.findFactByFormId(RELEASE,7)).thenThrow(new IllegalStateException("图鉴暂不可用"));
            assertThrows(IllegalStateException.class,()->f.workflow.readCurrentTeam(f.team.id()));
            assertThrows(IllegalStateException.class,()->f.workflow.previewTeamChanges(f.team.id(),List.of(CHANGE),"修改"));
        }
        try(var f=fixture()) {
            f.workflow.readCurrentTeam(f.team.id());
            f.workflow.observeCalculation("calculateBattleDamage","[tool.error] 计算失败");
            var preview=f.workflow.previewTeamChanges(f.team.id(),List.of(CHANGE),"雨天让冰招威力提升2倍");
            assertTrue(preview.calculationEvidence().contains("未获得"));
            assertFalse(preview.rationale().contains("冰招威力提升"));
        }
    }
    @Test void requestedCalculationMustSucceedInThisRunBeforePreview() throws Exception {
        try(var f=fixture()) {
            f.run=f.newRun(OWNER,Instant.now().plusSeconds(60),"请先calculateBattleDamage计算再预览修改");f.bind();
            f.workflow.readCurrentTeam(f.team.id());
            assertThrows(IllegalStateException.class,()->f.workflow.previewTeamChanges(f.team.id(),List.of(CHANGE),"按要求调整"));
            var result=new ObjectMapper().readTree(Path.of("evaluation/campus/s01/damage-reference.json").toFile()).path("result").toString();
            f.workflow.observeCalculation("calculateBattleDamage",result);
            var preview=f.workflow.previewTeamChanges(f.team.id(),List.of(CHANGE),"按要求调整");
            assertTrue(preview.calculationEvidence().contains("164～194"));
        }
    }
    @Test void parallelConfirmationsOnlyIncrementTheRevisionOnce() throws Exception {
        try(var f=fixture()) {
            var preview=f.preview();f.approve(preview);var before=f.teams.snapshotForOwner(f.team.id(),OWNER);
            var executor=Executors.newFixedThreadPool(2);
            try {
                var first=executor.submit(()->f.workflow.finishIfPending(f.context()));
                var second=executor.submit(()->f.workflow.finishIfPending(f.context()));
                assertEquals(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS));
                assertEquals(before.revision()+1,f.teams.snapshotForOwner(f.team.id(),OWNER).revision());
            } finally {executor.shutdownNow();}
        }
    }
    @Test void cancelledOrTimedOutWorkerCannotPerformALateSave() throws Exception {
        for(boolean timeout:List.of(false,true)) try(var f=fixture()) {
            var worker=Executors.newSingleThreadExecutor();var scheduler=Executors.newSingleThreadScheduledExecutor();
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var exited=new CountDownLatch(1);
            AtomicReference<TeamWorkflowService.Preview> preview=new AtomicReference<>();
            var service=new PokemonAgentRunService(f.runs,new InMemoryEventRepository(),new InMemoryConversationRepository(),new InMemoryChatMessageRepository(),context->{
                if(preview.get()!=null) {
                    entered.countDown();
                    try {
                        while(release.getCount()>0)try{release.await(30,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){ /* emulate a non-cooperative worker */ }
                        return f.workflow.finishIfPending(context);
                    } finally {exited.countDown();}
                }
                f.workflow.readCurrentTeam(f.team.id());preview.set(f.workflow.previewTeamChanges(f.team.id(),List.of(CHANGE),"等级调整"));return "等待确认";
            },worker,scheduler);
            try {
                var created=service.createRun(new CreateRunCommand(OWNER,"late-session",null,"修改队伍","pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN",null,RELEASE,null,null,"v1","late-trace",null,Duration.ofSeconds(timeout?2:15)));
                await(()->preview.get()!=null && service.findRun(created.runId(),OWNER).orElseThrow().status()==AgentRunStatus.WAITING_APPROVAL);
                f.hitl.decide(preview.get().proposalId(),OWNER,created.runId(),true,"");service.resolveApproval(created.runId(),OWNER,true,"");
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                if(!timeout) service.cancel(created.runId(),OWNER);
                await(()->service.findRun(created.runId(),OWNER).orElseThrow().status().isTerminal());
                release.countDown();assertTrue(exited.await(5,TimeUnit.SECONDS));
                assertEquals(timeout?AgentRunStatus.TIMED_OUT:AgentRunStatus.CANCELLED,service.findRun(created.runId(),OWNER).orElseThrow().status());
                assertEquals(50,f.teams.findByIdForOwner(f.team.id(),OWNER).orElseThrow().members().getFirst().build().level());
            } finally {release.countDown();service.shutdown();}
        }
    }
    @Test void invalidBuildDuplicateSlotsAndVersionMismatchAreRejected() {
        try(var f=fixture()) {
            f.workflow.readCurrentTeam(f.team.id());
            assertThrows(IllegalArgumentException.class,()->f.workflow.previewTeamChanges(f.team.id(),List.of(CHANGE,CHANGE),"修改"));
            var invalid=new TeamWorkflowService.BuildChange(1,null,null,null,null,101,null,null);
            assertThrows(IllegalArgumentException.class,()->f.workflow.previewTeamChanges(f.team.id(),List.of(invalid),"修改"));
            assertEquals(50,f.teams.findByIdForOwner(f.team.id(),OWNER).orElseThrow().members().getFirst().build().level());
            var foreign=new SavedTeam(0,OWNER,"版本不同","other-rules",RELEASE,f.team.members());
            assertThrows(IllegalArgumentException.class,()->f.workflow.readCurrentTeam(f.teams.create(foreign).id()));
        }
    }
    @Test void realRunApprovalResumeUsesWorkerBoundaryAndDoesNotCallModelAgain() throws Exception {
        try(var f=fixture()) {
            var events=new InMemoryEventRepository();var worker=Executors.newSingleThreadExecutor();var scheduler=Executors.newSingleThreadScheduledExecutor();
            AtomicInteger planning=new AtomicInteger();AtomicReference<TeamWorkflowService.Preview> preview=new AtomicReference<>();
            var service=new PokemonAgentRunService(f.runs,events,new InMemoryConversationRepository(),new InMemoryChatMessageRepository(),context->{
                String receipt=f.workflow.finishIfPending(context);if(receipt!=null)return receipt;
                planning.incrementAndGet();f.workflow.readCurrentTeam(f.team.id());
                preview.set(f.workflow.previewTeamChanges(f.team.id(),List.of(CHANGE),"用户明确要求的等级调整"));return "等待确认";
            },worker,scheduler);
            try {
                var created=service.createRun(new CreateRunCommand(OWNER,"session-new",null,"修改队伍","pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN",null,RELEASE,null,null,"v1","trace",null,Duration.ofSeconds(30)));
                await(()->preview.get()!=null && service.findRun(created.runId(),OWNER).orElseThrow().status()==AgentRunStatus.WAITING_APPROVAL);
                assertEquals(50,f.teams.findByIdForOwner(f.team.id(),OWNER).orElseThrow().members().getFirst().build().level());
                assertTrue(f.hitl.decide(preview.get().proposalId(),OWNER,created.runId(),true,"").allowed());
                service.resolveApproval(created.runId(),OWNER,true,"");
                await(()->service.findRun(created.runId(),OWNER).orElseThrow().status().isTerminal());
                assertEquals(AgentRunStatus.COMPLETED,service.findRun(created.runId(),OWNER).orElseThrow().status());
                assertEquals(51,f.teams.findByIdForOwner(f.team.id(),OWNER).orElseThrow().members().getFirst().build().level());
                assertEquals(1,planning.get());
            } finally { service.shutdown(); }
        }
    }
    static void await(java.util.function.BooleanSupplier check) throws InterruptedException {
        Instant deadline=Instant.now().plusSeconds(8);while(!check.getAsBoolean()&&Instant.now().isBefore(deadline))Thread.sleep(10);assertTrue(check.getAsBoolean());
    }
    @Test void controlledRunFailuresEndWithoutApprovalAnswerFactsOrSqliteWrites() throws Exception {
        // Real Run lifecycle, event/history repositories and SQLite. Controlled executor,
        // not a claim that a real model or HTTP network outage was exercised.
        for(boolean failedRead:List.of(true,false)) try(var f=fixture()) {
            ToolExecutionContextHolder.clear();
            var events=new InMemoryEventRepository();var messages=new InMemoryChatMessageRepository();
            var before=f.teams.snapshotForOwner(f.team.id(),OWNER);
            if(failedRead)when(f.resources.findFactByFormId(RELEASE,7)).thenThrow(new IllegalStateException("受控资料读取失败"));
            var worker=Executors.newSingleThreadExecutor();var scheduler=Executors.newSingleThreadScheduledExecutor();
            var service=new PokemonAgentRunService(f.runs,events,new InMemoryConversationRepository(),messages,context->{
                f.workflow.readCurrentTeam(f.team.id());
                f.workflow.observeCalculation("calculateBattleDamage","[tool.error] 受控计算失败");
                // The real workflow must refuse this after a failed required calculation.
                f.workflow.previewTeamChanges(f.team.id(),List.of(CHANGE),"按用户要求修改");
                return "不应返回的成功消息";
            },worker,scheduler);
            try {
                var created=service.createRun(new CreateRunCommand(OWNER,"failure-session",null,"请先calculateBattleDamage计算再预览修改", "pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN",null,RELEASE,null,null,"v1","failure-trace",null,Duration.ofSeconds(15)));
                await(()->service.findRun(created.runId(),OWNER).orElseThrow().status().isTerminal());
                assertEquals(AgentRunStatus.FAILED,service.findRun(created.runId(),OWNER).orElseThrow().status());
                assertEquals(before,f.teams.snapshotForOwner(f.team.id(),OWNER));
                var stream=events.findAfter(created.runId(),0);
                assertEquals(1,stream.stream().filter(event->event.type().isTerminal()).count());
                assertTrue(stream.stream().noneMatch(event->event.type().name().equals("APPROVAL_REQUIRED")));
                assertTrue(messages.findByConversationForUser(created.conversationId(),OWNER).stream().noneMatch(message->message.role().equals("assistant")));
                assertThrows(PokemonResourceNotFoundException.class,()->f.workflow.outcome(created.runId(),OWNER));
            } finally {service.shutdown();}
        }
    }
}
