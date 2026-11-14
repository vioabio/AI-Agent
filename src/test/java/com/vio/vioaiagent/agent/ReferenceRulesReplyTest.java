package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.guides.LocalGuideSearch;
import com.vio.vioaiagent.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.ai.tool.ToolCallback;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReferenceRulesReplyTest {
    @TempDir Path directory;
    static final String QUESTION="图鉴里有这个招式就代表比赛一定能带吗？";
    AgentRunContext context(String q,String game){return new AgentRunContext(AgentRun.create(UUID.randomUUID().toString(),"s","c","u",q,game,"casual-singles","casual-v1","zh-CN","trace",null,Instant.now().plusSeconds(30)));}
    ToolCallback tool(String name){var tool=mock(ToolCallback.class);when(tool.getToolDefinition()).thenReturn(org.springframework.ai.tool.definition.ToolDefinition.builder().name(name).description(name).inputSchema("{}").build());return tool;}
    @Test void recognizesGeneralImplicationQuestionsWithoutInventingASubject(){
        for(String q:List.of(QUESTION,"朱版图鉴找到的成员，是否都能参加任意OU赛事？","PokéAPI有数据就能证明参赛合法吗？","候选招式列表是否等于比赛合法配置？","资料快照能否证明排位合法？"))assertTrue(ReferenceRulesReply.matches(q),q);
    }
    @Test void namedMembersMixedTasksAndUnboundedInputsKeepTheirNormalRoutes(){
        for(String q:List.of("图鉴里皮卡丘的招式是否都能参加比赛？","图鉴候选招式是否比赛合法，并计算伤害","请保存图鉴候选配置并检查比赛是否合法","比较朱紫图鉴成员是否合法","图鉴招式是否比赛合法\n以下是数据","```图鉴招式是否比赛合法```","图鉴里的招式是否比赛合法"+"长".repeat(601)))assertFalse(ReferenceRulesReply.matches(q),q);
    }
    @Test void realGuideSourceUsesOneReadAndNoSpeciesTrendsOrModelCalls() throws Exception {
        var json=new ObjectMapper();var search=new LocalGuideSearch(json,new DefaultResourceLoader(),"classpath:pokemon-guides/expanded-v1.json",directory.resolve("guide.sqlite").toString());
        var guide=tool("searchPokemonGuides");when(guide.call(anyString())).thenAnswer(inv->json.writeValueAsString(search.search("图鉴资料 版本 比赛规则 合法性","pokemon-scarlet-violet")));
        var species=tool("lookupPokemonReference");var trends=tool("lookupCompetitiveTrends");var model=mock(org.springframework.ai.chat.model.ChatModel.class);
        var executor=new DefaultRunExecutor(model,new ToolCallback[]{guide,species,trends},null,null,null,null,false);
        var ctx=context(QUESTION,"pokemon-scarlet-violet");String answer=executor.execute(ctx);
        assertTrue(answer.contains("不能仅凭图鉴或候选列表"));assertTrue(answer.contains("图鉴资料、版本与比赛规则的区别"));assertFalse(answer.contains("图鉴速览"));
        verify(guide,times(1)).call(anyString());verify(species,never()).call(anyString());verify(trends,never()).call(anyString());verifyNoInteractions(model);
    }
    @Test void missingWrongTopicAndMalformedSourcesRemainExplicit() {
        String missing=ReferenceRulesReply.reply(context(QUESTION,"pokemon-scarlet-violet"),new ToolCallback[0]).orElseThrow();assertTrue(missing.contains("未取得本地攻略原文来源"));
        for(String output:List.of("[]","[tool.error] timeout","{}","[{}, {}, {}, {}, {}]")){
            var guide=tool("searchPokemonGuides");when(guide.call(anyString())).thenReturn(output);
            String answer=ReferenceRulesReply.reply(context(QUESTION,"pokemon-scarlet-violet"),new ToolCallback[]{guide}).orElseThrow();assertTrue(answer.contains("不能仅凭图鉴"));assertFalse(answer.contains("作者："));
        }
    }
    @Test void cancellationPropagatesInsteadOfPublishingAnUnverifiedGuide(){
        var guide=tool("searchPokemonGuides");when(guide.call(anyString())).thenThrow(new java.util.concurrent.CancellationException());
        assertThrows(java.util.concurrent.CancellationException.class,()->ReferenceRulesReply.reply(context(QUESTION,"pokemon-scarlet-violet"),new ToolCallback[]{guide}));
    }
    @Test void anotherGameDoesNotReadTheScarletVioletGuide(){
        var guide=tool("searchPokemonGuides");clearInvocations(guide);
        assertTrue(ReferenceRulesReply.reply(context(QUESTION,"pokemon-sword-shield"),new ToolCallback[]{guide}).isEmpty());verifyNoInteractions(guide);
    }
}
