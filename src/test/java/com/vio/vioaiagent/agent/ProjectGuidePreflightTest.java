package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.guides.LocalGuideSearch;
import com.vio.vioaiagent.security.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.*;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectGuidePreflightTest {
    @TempDir Path directory;
    static final String QUESTION="请说明本项目队伍修改预览、用户确认与实际保存的关系，用户取消或同时编辑时应如何处理；找不到本地来源时说明，不要编造。这里只解释流程，不要读取或修改任何队伍。";
    public static class Search {
        final LocalGuideSearch index;final AtomicInteger calls=new AtomicInteger();String mode="normal";
        Search(LocalGuideSearch index){this.index=index;}
        @Tool(description="Find local source notes")
        public List<LocalGuideSearch.Hit> searchPokemonGuides(@ToolParam(description="Query") String query,@ToolParam(description="Game") String gameReleaseId){
            calls.incrementAndGet();if(mode.equals("error"))throw new IllegalStateException("fixture-private-detail");
            if(mode.equals("empty"))return List.of();return index.search(query,gameReleaseId);
        }
    }
    class Fixture implements AutoCloseable {
        final HitlManager hitl=new HitlManager();final ToolExecutionGateway gateway;
        final Search search;final ToolCallback[] tools;final List<ToolGovernanceEvent> events=new ArrayList<>();
        final ChatModel model=mock(ChatModel.class);final List<Prompt> prompts=new ArrayList<>();
        Fixture(String role) {
            search=new Search(new LocalGuideSearch(new ObjectMapper(),new DefaultResourceLoader(),"classpath:pokemon-guides/expanded-v1.json",directory.resolve(UUID.randomUUID()+".sqlite").toString()));
            var masker=new ParameterMasker();gateway=new ToolExecutionGateway(hitl,new PathGuard(List.of(directory.toString())),new CommandGuard(List.of()),masker,new AuditLogger(masker));
            tools=Arrays.stream(ToolCallbacks.from(search)).map(gateway::wrap).toArray(ToolCallback[]::new);
            ToolExecutionContextHolder.set(new ToolExecutionContext("fixture-owner","run-project-source",Set.of(role),"fixture-trace",null,events::add));
            when(model.call(any(Prompt.class))).thenAnswer(call->{prompts.add(call.getArgument(0));return new ChatResponse(List.of(new Generation(new AssistantMessage("模型没有选工具，并声称已经保存队伍。"))));});
        }
        VioManus agent(ToolCallback[] exposed) {
            var a=new VioManus(exposed,null,model);a.setToolExecutionGateway(gateway);a.setGuideGameReleaseId("pokemon-scarlet-violet");return a;
        }
        public void close(){ToolExecutionContextHolder.clear();gateway.shutdown();hitl.shutdown();}
    }
    @Test void sourceSearchRunsThroughGatewayBeforeTheFirstModelCallAndPublishesVerifiedExcerpts() {
        try(var f=new Fixture("trainer")) {
            var agent=f.agent(f.tools);agent.setCurrentUserRequest(QUESTION);var deltas=new ArrayList<String>();agent.setAnswerDeltaSink(deltas::add);
            agent.run(QUESTION);String answer=agent.getFinalAnswer();assertNotNull(answer);
            assertEquals(1,f.search.calls.get());assertTrue(answer.contains("从AI建议到队伍保存的确认流程"));
            assertFalse(answer.contains("声称已经保存"));assertEquals(answer,String.join("",deltas));assertTrue(deltas.size()>1);
            assertEquals(1,f.prompts.size());assertTrue(f.prompts.getFirst().getInstructions().stream().anyMatch(m->m instanceof ToolResponseMessage));
            assertEquals(1,f.events.stream().filter(e->e.eventType().equals("tool.started")).count());
            assertTrue(f.events.stream().allMatch(e->ProjectGuideRequest.ORIGIN.equals(e.attributes().get("selectionOrigin"))));
            verify(f.model,never()).stream(any(Prompt.class));
        }
    }
    @ParameterizedTest @ValueSource(strings={"empty","error"})
    void emptyOrFailedLookupDoesNotPublishModelClaimsOrRawErrors(String mode) {
        try(var f=new Fixture("trainer")) {
            f.search.mode=mode;var agent=f.agent(f.tools);agent.setCurrentUserRequest(QUESTION);agent.run(QUESTION);
            // The gateway retains its single retry for failed idempotent, low-risk reads.
            assertEquals(mode.equals("error")?2:1,f.search.calls.get());assertTrue(agent.getFinalAnswer().contains("没有取得"));
            assertEquals(mode.equals("error")?2:1,f.events.stream().filter(e->e.eventType().equals("tool.started")).count());
            assertFalse(agent.getFinalAnswer().contains("fixture-private-detail"));assertFalse(agent.getFinalAnswer().contains("已经保存队伍"));
        }
    }
    @Test void deniedPrincipalDoesNotReachTheActualSearchImplementation() {
        try(var f=new Fixture("unprivileged")) {
            var agent=f.agent(f.tools);agent.setCurrentUserRequest(QUESTION);agent.run(QUESTION);
            assertEquals(0,f.search.calls.get());assertTrue(agent.getFinalAnswer().contains("没有取得"));
            assertFalse(f.events.stream().anyMatch(e->e.eventType().equals("approval.required")));
        }
    }
    @Test void savedBackgroundCannotTriggerSearchForTheRawGreeting() {
        try(var f=new Fixture("trainer")) {
            var agent=f.agent(f.tools);agent.setCurrentUserRequest("你好");agent.run("你好\n已保存的偏好背景："+QUESTION);
            assertEquals(0,f.search.calls.get());assertTrue(f.events.isEmpty());
        }
    }
    @Test void absentToolAndMissingAuthoritativeVersionCannotCreateAnImplicitCapability() {
        try(var f=new Fixture("trainer")) {
            var agent=f.agent(new ToolCallback[0]);agent.setCurrentUserRequest(QUESTION);agent.run(QUESTION);assertEquals(0,f.search.calls.get());assertTrue(f.events.isEmpty());
        }
        try(var f=new Fixture("trainer")) {
            var agent=f.agent(f.tools);agent.setGuideGameReleaseId(null);agent.setCurrentUserRequest(QUESTION);agent.run(QUESTION);
            assertEquals(0,f.search.calls.get());assertTrue(f.events.isEmpty());assertTrue(agent.getFinalAnswer().contains("没有取得"));
        }
        try(var f=new Fixture("trainer")) {
            var agent=f.agent(f.tools);agent.setGuideGameReleaseId("pokemon-sword-shield");agent.setCurrentUserRequest(QUESTION);agent.run(QUESTION);
            assertEquals(0,f.search.calls.get());assertTrue(f.events.isEmpty());assertTrue(agent.getFinalAnswer().contains("没有取得"));
        }
    }
}
