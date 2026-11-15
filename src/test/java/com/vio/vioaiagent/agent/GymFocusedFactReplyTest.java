package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.run.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GymFocusedFactReplyTest {
    @Test void resettingTrainingConditionsUsesNoModelOrTools() throws Exception {
        var model=mock(org.springframework.ai.chat.model.ChatModel.class);
        var tool=mock(org.springframework.ai.tool.ToolCallback.class);
        var executor=new DefaultRunExecutor(model,new org.springframework.ai.tool.ToolCallback[]{tool},null,null,null,null,false);
        String answer=executor.execute(context("清空培养条件",List.of()));
        assertTrue(answer.contains("重新收集条件"));assertTrue(answer.contains("没有删除历史消息"));verifyNoInteractions(model,tool);
    }
    AgentRunContext context(String q,List<Message> history){return new AgentRunContext(AgentRun.create(UUID.randomUUID().toString(),"s","c","u",q,"pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","trace",null,Instant.now().plusSeconds(30)),null,history);}
    @Test void focusedFollowupUsesSelectedGymAndAvoidsRepeatingItsRoster(){
        var ctx=context("他的太晶成员是哪只？",List.of(new UserMessage("请只查询青木初战资料。")));
        String answer=GymDialogueRouter.reply(ctx,new org.springframework.ai.tool.ToolCallback[0]).orElseThrow();
        assertTrue(answer.contains("姆克鹰"));assertTrue(answer.contains("一般／飞行"));assertTrue(answer.contains("太晶属性 **一般**"));
        assertFalse(answer.contains("树枕尾熊"));assertFalse(answer.contains("| 成员 |"));assertTrue(answer.contains("gyms.shtml"));assertTrue(GymAnswerReview.check(GymTurnPlan.from(ctx),answer).accepted());
    }
    @Test void rematchIdentityAndLevelComeFromTheSeparatePinnedRoster(){
        var ctx=context("他的王牌是哪只？",List.of(new UserMessage("寇沙重赛资料")));
        String answer=GymDialogueRouter.reply(ctx,new org.springframework.ai.tool.ToolCallback[0]).orElseThrow();
        assertTrue(answer.contains("树才怪"));assertTrue(answer.contains("66"));assertTrue(answer.contains("视察重赛"));assertTrue(answer.contains("gymrematches.shtml"));assertTrue(answer.contains("不代表已解锁"));
    }
    @Test void mixedRequestsAndNegatedAceLookupsAreNotShortened(){
        for(String q:List.of("他的太晶成员及全部招式是什么？","为什么他的太晶成员用一般属性？","不要看太晶成员，只给完整队伍资料")){
            var ctx=context(q,List.of(new UserMessage("青木初战资料")));
            assertTrue(GymFocusedFactReply.reply(GymTurnPlan.from(ctx),q).isEmpty());
        }
    }
    @Test void historicalAssistantTextCannotSupplyTheGym(){
        var ctx=context("他的太晶成员是哪只？",List.of(new AssistantMessage("青木初战")));
        assertTrue(GymTurnPlan.from(ctx).target().isEmpty());assertTrue(GymFocusedFactReply.reply(GymTurnPlan.from(ctx),ctx.run().prompt()).isEmpty());
    }
    @Test void executorPublishesFocusedFactsWithoutCallingModelsOrTools(){
        var model=mock(org.springframework.ai.chat.model.ChatModel.class);var tool=mock(org.springframework.ai.tool.ToolCallback.class);
        var executor=new DefaultRunExecutor(model,new org.springframework.ai.tool.ToolCallback[]{tool},null,null,null,null,false);
        String answer=executor.execute(context("青木的太晶成员是哪只？",List.of()));
        assertTrue(answer.contains("姆克鹰"));assertTrue(answer.contains("未明确阶段"));verifyNoInteractions(model,tool);
    }
    @Test void battleModeFollowupsReadTheSelectedRosterWithoutInventingMoveMechanics() {
        var model=mock(org.springframework.ai.chat.model.ChatModel.class);
        var tool=mock(org.springframework.ai.tool.ToolCallback.class);
        var executor=new DefaultRunExecutor(model,new org.springframework.ai.tool.ToolCallback[]{tool},null,null,null,null,false);
        String answer=executor.execute(context("这场是单打还是双打？",List.of(new UserMessage("先查莱姆的视察重赛队伍。"))));
        assertTrue(answer.contains("莱姆的视察重赛是双打"));
        assertTrue(answer.contains("gymrematches.shtml"));
        assertFalse(answer.contains("破音"));
        assertFalse(answer.contains("爆音波"));
        verifyNoInteractions(model,tool);
        String singles=GymDialogueRouter.reply(context("这场采用什么对战模式？",List.of(new UserMessage("青木初战资料"))),new org.springframework.ai.tool.ToolCallback[0]).orElseThrow();
        assertTrue(singles.contains("青木的初战是单打"));
        assertTrue(GymDialogueRouter.reply(context("这场是单打还是双打？",List.of(new AssistantMessage("莱姆重赛"))),new org.springframework.ai.tool.ToolCallback[0]).isEmpty());
    }
    @Test void modeQuestionsWithTacticalActionsKeepTheirExistingPlanner() {
        for(String question:List.of("这场是单打还是双打？再推荐队伍。","为什么这场是单打还是双打？","不要回答这场是单打还是双打。"))
            assertFalse(GymFocusedFactReply.requestsBattleMode(question),question);
    }
}
