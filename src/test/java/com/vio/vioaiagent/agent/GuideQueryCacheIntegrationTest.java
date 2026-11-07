package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.guides.LocalGuideSearch;
import com.vio.vioaiagent.security.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuideQueryCacheIntegrationTest {
    @TempDir Path directory;
    public static class SearchFixture {
        final LocalGuideSearch search;
        SearchFixture(LocalGuideSearch search){this.search=search;}
        @Tool(description="Find local guide excerpts")
        public List<LocalGuideSearch.Hit> searchPokemonGuides(@ToolParam(description="Query") String query,
                                                              @ToolParam(description="Game release") String gameReleaseId) {
            return search.search(query,gameReleaseId);
        }
    }
    static ChatResponse call(String id,String query) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("private planning",Map.of(),List.of(
                new AssistantMessage.ToolCall(id,"function","searchPokemonGuides","{\"query\":\""+query+"\",\"gameReleaseId\":\"pokemon-scarlet-violet\"}"))))));
    }
    @Test void emptyFirstQueryCannotPoisonTheNextRealGuideSearchOrItsVerifiedStreamingAnswer() throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules();
        var search=new LocalGuideSearch(mapper,new DefaultResourceLoader(),"classpath:pokemon-guides/expanded-v1.json",directory.resolve("guides.sqlite").toString());
        assertTrue(search.search("配队思路","pokemon-scarlet-violet").isEmpty());
        var expected=search.search("队伍角色分工","pokemon-scarlet-violet");assertFalse(expected.isEmpty());
        var masker=new ParameterMasker();
        var gateway=new ToolExecutionGateway(new HitlManager(),new PathGuard(List.of(directory.toString())),new CommandGuard(List.of()),masker,new AuditLogger(masker));
        var events=new ArrayList<ToolGovernanceEvent>();
        ToolExecutionContextHolder.set(new ToolExecutionContext("fixture-owner","guide-cache-run",Set.of("trainer"),"fixture-trace","one-run-key",events::add));
        try {
            var tools=Arrays.stream(ToolCallbacks.from(new SearchFixture(search))).map(gateway::wrap).toArray(ToolCallback[]::new);
            var model=mock(ChatModel.class);
            when(model.call(any(Prompt.class))).thenReturn(call("first","配队思路"),call("second","队伍角色分工"),
                    new ChatResponse(List.of(new Generation(new AssistantMessage("Use the returned local excerpts.")))));
            var agent=new VioManus(tools,null,model);agent.setToolExecutionGateway(gateway);
            var chunks=new ArrayList<String>();agent.setAnswerDeltaSink(chunks::add);
            agent.run("检索核心成员的组队攻略，给出原文和来源");
            String answer=agent.getFinalAnswer();assertNotNull(answer);
            assertTrue(answer.contains("原文节选"));assertFalse(answer.contains("没有取得"));
            for(var hit:expected)assertTrue(answer.contains(hit.document().title()));
            assertEquals(answer,String.join("",chunks));assertTrue(chunks.size()>1);
            assertEquals(2,events.stream().filter(e->e.eventType().equals("tool.started")).count());
            assertEquals(0,events.stream().filter(e->e.eventType().equals("tool.deduplicated")).count());
            verify(model,times(3)).call(any(Prompt.class));verify(model,never()).stream(any(Prompt.class));
        } finally {ToolExecutionContextHolder.clear();gateway.shutdown();}
    }
}
