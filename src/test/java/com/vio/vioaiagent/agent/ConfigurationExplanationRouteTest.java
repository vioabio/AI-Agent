package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.guides.LocalGuideSearch;
import com.vio.vioaiagent.pokemon.domain.TeamBuild;
import com.vio.vioaiagent.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigurationExplanationRouteTest {
    @Test void onePrincipleKeepsVerifiedAttributionWithoutExpandingTheWholeDocument() {
        var json = new ObjectMapper();
        var search = new LocalGuideSearch(json, new DefaultResourceLoader(), "classpath:pokemon-strategy/overview-v1.json", directory.resolve("brief.sqlite").toString());
        var tool = mock(ToolCallback.class);
        when(tool.getToolDefinition()).thenReturn(ToolDefinition.builder().name("searchPokemonGuides").description("lookup").inputSchema("{}").build());
        when(tool.call(anyString())).thenAnswer(invocation -> {
            var input = json.readTree((String) invocation.getArgument(0));
            return json.writeValueAsString(search.search(input.path("query").asText(), input.path("gameReleaseId").asText()));
        });
        String answer = new DefaultRunExecutor(mock(ChatModel.class), new ToolCallback[]{tool}, null, null, null, null, false)
                .execute(context("请只给一条通用组队原则，无需读取我的私人队伍。"));
        assertTrue(answer.contains("核心成员的角色"));
        assertTrue(answer.contains("围绕核心成员构建队伍"));
        assertTrue(answer.contains("VIO project"));
        assertFalse(answer.contains("本地说明原文"));
        assertTrue(answer.length() < 200);
        doReturn("[]").when(tool).call(anyString());
        String missing = ConfigurationExplanationRoute.reply(context("请只给一条通用组队原则，无需读取我的私人队伍。"), new ToolCallback[]{tool}).orElseThrow();
        assertTrue(missing.contains("引用暂缺"));
        assertFalse(missing.contains("作者 VIO project"));
    }
    @TempDir Path directory;
    AgentRunContext context(String question) {
        var run=AgentRun.create("concept-run","s","c","owner",question,"pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","trace",null,Instant.now().plusSeconds(30));
        return new AgentRunContext(run);
    }
    @Test void conceptsReadMatchingCompleteSourcesThroughTheNormalEntryPoint() {
        var json=new ObjectMapper();
        var search=new LocalGuideSearch(json,new DefaultResourceLoader(),"classpath:pokemon-strategy/overview-v1.json",directory.resolve("guides.sqlite").toString());
        var tool=mock(ToolCallback.class);
        when(tool.getToolDefinition()).thenReturn(ToolDefinition.builder().name("searchPokemonGuides").description("source lookup").inputSchema("{}").build());
        when(tool.call(anyString())).thenAnswer(invocation->{var input=json.readTree((String)invocation.getArgument(0));return json.writeValueAsString(search.search(input.path("query").asText(),input.path("gameReleaseId").asText()));});
        for(String question:List.of("请分别说明招式的英文标识与中文显示名作用。","为什么导入时会把英文招式名转换成稳定标识？","没填写道具和明确不带道具是不是相同配置？","要算两只成员互相打的伤害，我要准备哪些信息？","怎样避免把AI提出的队伍调整误认为已保存？")) {
            var ctx=context(question);var model=mock(ChatModel.class);
            String answer=new DefaultRunExecutor(model,new ToolCallback[]{tool},null,null,null,null,false).execute(ctx);
            assertTrue(answer.contains("本地说明原文"),question+answer);
            assertTrue(answer.contains("VIO project"));verifyNoInteractions(model);
            assertEquals(0,ctx.modelUsage().snapshot().get("modelCallCount"));
        }
    }
    @Test void emptyItemStorageCannotBeUsedToReconstructUnconfirmedUserIntent() {
        var missing=new TeamBuild(List.of(),null,null,null,null,null,null);
        var explicitEmpty=new TeamBuild(List.of(),null,"",null,null,null,null);
        assertEquals(missing.item(),explicitEmpty.item());
        String answer=ConfigurationExplanationRoute.reply(context("未填写道具和明确不携带道具是否一样？"),new ToolCallback[0]).orElseThrow();
        assertTrue(answer.contains("无法还原"));assertTrue(answer.contains("引用暂缺"));
    }
    @Test void itemIntentAndGeneralAdviceVariantsUseSourcesWithoutPrivateReads() {
        assertEquals(ConfigurationExplanationRoute.Topic.ITEM_INTENT,
                ConfigurationExplanationRoute.select("道具栏没填写与主动选择不携带，在本项目里能区分吗？请给出本地说明依据。").orElseThrow());
        var list=mock(ToolCallback.class);
        when(list.getToolDefinition()).thenReturn(ToolDefinition.builder().name("listCurrentTeams").description("private").inputSchema("{}").build());
        var model=mock(ChatModel.class);
        String answer=new DefaultRunExecutor(model,new ToolCallback[]{list},null,null,null,null,false)
                .execute(context("目前没有保存队伍，我只想听一条通用组队原则，不用读取我的队伍。"));
        assertTrue(answer.contains("核心成员的角色"));
        assertTrue(answer.contains("没有读取"));
        verify(list,never()).call(anyString());
        verifyNoInteractions(model);
    }
    @Test void mixedActionsDataAndUnrelatedQuestionsKeepTheirOwnWorkflows() {
        for(String q:List.of("读取我的队伍，解释英文招式名的作用。","为什么导入招式要转换标识，然后修改队伍？","说明英文招式标识的作用，并推荐队伍。","说明英文招式标识的作用，顺便分析我的队伍。","这是导入数据\n为什么招式英文标识有作用？","计算皮卡丘使用十万伏特的伤害。","如何修改队伍并保存？"))
            assertTrue(ConfigurationExplanationRoute.select(q).isEmpty(),q);
    }
    @Test void malformedUnrelatedOrTamperedSourcesCannotBecomeConceptCitations() {
        for(String output:List.of("[]","{}","[tool.error] private","[{\"document\":{\"id\":\"team-change-confirmation-v1\"}}]")) {
            var tool=mock(ToolCallback.class);when(tool.getToolDefinition()).thenReturn(ToolDefinition.builder().name("searchPokemonGuides").description("lookup").inputSchema("{}").build());
            when(tool.call(anyString())).thenReturn(output);
            String answer=ConfigurationExplanationRoute.reply(context("怎样避免把AI提出的队伍调整误认为已保存？"),new ToolCallback[]{tool}).orElseThrow();
            assertTrue(answer.contains("引用暂缺"));assertFalse(answer.contains("private"));
        }
    }
    @Test void cancellationIsPropagated() {
        var tool=mock(ToolCallback.class);when(tool.getToolDefinition()).thenReturn(ToolDefinition.builder().name("searchPokemonGuides").description("lookup").inputSchema("{}").build());
        when(tool.call(anyString())).thenThrow(new java.util.concurrent.CancellationException());
        assertThrows(java.util.concurrent.CancellationException.class,()->ConfigurationExplanationRoute.reply(context("没填写道具和明确不带道具是不是相同配置？"),new ToolCallback[]{tool}));
    }
}
