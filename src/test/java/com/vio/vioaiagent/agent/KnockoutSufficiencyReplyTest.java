package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.agent.model.AgentState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnockoutSufficiencyReplyTest {
    @Test void standaloneToolAgentUsesTheSameReplyBeforePlanning() {
        var tool=mock(ToolCallback.class);
        var agent=new ToolCallAgent(new ToolCallback[]{tool});
        String question="知道了属性4倍克制，可以断言一定一击击倒吗？";
        agent.setCurrentUserRequest(question);
        var deltas=new ArrayList<String>();agent.setAnswerDeltaSink(deltas::add);
        assertFalse(agent.think());
        assertEquals(AgentState.FINISHED,agent.getState());
        assertEquals(DamageDialogueRoute.knockoutSufficiencyReply(question).orElseThrow(),agent.getFinalAnswer());
        assertEquals(agent.getFinalAnswer(),String.join("",deltas));
        verifyNoInteractions(tool);
    }
    @Test void multiplierQuestionsAnswerDirectlyWithoutUnrelatedReadsOrModelGeneration() {
        for (String question : List.of("知道了属性4倍克制，可以断言一定一击击倒吗？",
                "四倍克制是否意味着必定秒杀？", "属性克制能否保证OHKO？", "2倍克制可以保证一击击倒吗？")) {
            var run=AgentRun.create(UUID.randomUUID().toString(),"s","c","owner",question,
                    "pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","trace",null,Instant.now().plusSeconds(30));
            var model=mock(ChatModel.class);var tool=mock(ToolCallback.class);var deltas=new ArrayList<String>();
            String answer=new DefaultRunExecutor(model,new ToolCallback[]{tool},null,null,null,null,false)
                    .execute(new AgentRunContext(run,deltas::add,List.of()));
            assertTrue(answer.startsWith("仅凭属性克制倍率无法判断"));
            assertTrue(answer.contains("双方等级"));assertTrue(answer.contains("伤害区间"));
            assertTrue(answer.contains("输入契约"));assertEquals(answer,String.join("",deltas));
            assertFalse(answer.contains("冰冻光束"));assertFalse(answer.contains("激流"));
            verifyNoInteractions(model,tool);
        }
    }

    @Test void explicitCalculationBuildAndSourceRequestsRemainOnTheirExistingRoutes() {
        for (String question : List.of("请计算4倍克制下的伤害，能否一击击倒？", "引用本地原文解释4倍克制能否保证一击击倒。",
                "推荐一套4倍克制并一击击倒的配招。", "修改我的队伍以保证4倍克制一击击倒。",
                "调用calculateBattleDamage验证4倍克制是否一击击倒。", "我的test1队伍4倍克制对手，能否一击击倒？",
                "核对成员配置，4倍克制是否能保证OHKO？",
                "知道了属性4倍克制，可以断言一定一击击倒吗？顺便介绍奇树。",
                "4倍克制能否保证一击击倒？{\"level\":50}", "4倍克制能否一击击倒？\n再查询其他资料"))
            assertFalse(DamageDialogueRoute.isKnockoutSufficiencyQuestion(question),question);
    }
}
