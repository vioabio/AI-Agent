package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GymDialogueRouterTest {
    private AgentRunContext context(String question, List<Message> history) {
        var run=AgentRun.create(UUID.randomUUID().toString(),"s","conversation-a","owner-a",question,
                "pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","trace",null,Instant.now().plusSeconds(30));
        return new AgentRunContext(run,null,history);
    }

    @ParameterizedTest
    @CsvSource({
        "有什么很好克制该馆主的队伍吗,RECOMMEND_TEAM",
        "推荐一套应对阿枫的阵容,RECOMMEND_TEAM",
        "哪些成员适合组队打这个馆主,RECOMMEND_TEAM",
        "看看有什么合适的应对队伍,RECOMMEND_TEAM",
        "阿枫应该怎样打,GENERAL_ADVICE",
        "如何克制这个馆主,GENERAL_ADVICE",
        "读取我的test1队伍分析阿枫,SAVED_TEAM_ANALYSIS",
        "帮我计算打阿枫的伤害,DAMAGE",
        "确认修改阿枫应对队伍,MUTATION",
        "阿枫的队伍和招式是什么,CATALOG"
    })
    void distinguishesUserTasks(String question, GymTurnPlan.Intent expected) {
        assertEquals(expected,GymTurnPlan.classify(question));
    }

    @Test void exactReportedQuestionCannotBecomeCatalogAndRunsThroughExecutor() {
        String question="有什么很好克制该馆主的队伍吗";
        var history=List.<Message>of(new UserMessage("看下阿枫的队伍配置"),new AssistantMessage("### 朱／紫馆主资料 · 初战\n**阿枫**"));
        var ctx=context(question,history);
        var plan=GymTurnPlan.from(ctx);
        assertEquals("owner-a",plan.ownerId());assertEquals("conversation-a",plan.conversationId());
        assertEquals("katy",plan.target().orElseThrow().gymId());
        assertTrue(GymCatalogAnswer.reply(question,"pokemon-scarlet-violet").isEmpty());
        var model=mock(ChatModel.class);var deltas=new ArrayList<String>();
        var executor=new DefaultRunExecutor(model,new ToolCallback[0],null,null,null,null,false);
        String answer=executor.execute(new AgentRunContext(ctx.run(),deltas::add,history));
        assertTrue(answer.contains("阿枫初战"));assertTrue(answer.contains("队伍推荐的缺口"));
        assertFalse(answer.contains("| 馆主 |"));assertEquals(answer,String.join("",deltas));verifyNoInteractions(model);
        assertTrue(GymAnswerReview.check(plan,answer).accepted());
    }

    @Test void historicalModelTextCannotChooseGymOrProgress() {
        var plan=GymTurnPlan.from(context("有什么克制该馆主的队伍",List.of(new AssistantMessage("**阿枫**\n### 馆主资料 · 视察重赛"))));
        assertTrue(plan.target().isEmpty());
        String answer=GymDialogueRouter.reply(context("有什么克制该馆主的队伍",List.of()),new ToolCallback[0]).orElseThrow();
        assertTrue(answer.contains("哪位馆主"));assertFalse(answer.contains("已有徽章"));
    }

    @Test void switchesTargetAndStageWithoutLeakingThePreviousSelection() {
        var past=List.<Message>of(new UserMessage("阿枫重赛阵容"));
        var target=GymTurnPlan.from(context("有什么克制奇树的队伍",past)).target().orElseThrow();
        assertEquals("iono",target.gymId());assertEquals("initial",target.phase());assertTrue(target.defaultStage());
        target=GymTurnPlan.from(context("不要重赛，只看初战怎么打",past)).target().orElseThrow();
        assertEquals("initial",target.phase());assertFalse(target.defaultStage());
    }

    @Test void ambiguityAndNewConversationDoNotReuseAnOldTarget() {
        var past=List.<Message>of(new UserMessage("阿枫"),new UserMessage("奇树和寇沙呢"));
        assertTrue(GymTurnPlan.from(context("有什么克制该馆主的队伍",past)).target().isEmpty());
        assertTrue(GymTurnPlan.from(context("有什么克制该馆主的队伍",List.of())).target().isEmpty());
    }

    @Test void reviewerRejectsWrongTaskWrongTargetAndEmptyAnswer() {
        var plan=GymTurnPlan.from(context("有什么克制阿枫的队伍",List.of()));
        assertTrue(GymAnswerReview.check(plan,"| 馆主 | 擅长属性 |\n| 阿枫 | 虫 |").issues().contains("catalog_instead_of_task"));
        assertFalse(GymAnswerReview.check(plan,"### 奇树\n队伍建议").accepted());
        assertFalse(GymAnswerReview.check(plan,"").accepted());
    }

    @Test void unrelatedRequestsAndOtherGameStayOutsideThisRouter() {
        assertTrue(GymDialogueRouter.reply(context("介绍一下皮卡丘",List.of(new UserMessage("阿枫"))),new ToolCallback[0]).isEmpty());
        assertTrue(GymDialogueRouter.reply(context("推荐一套OU队伍",List.of(new UserMessage("阿枫"))),new ToolCallback[0]).isEmpty());
        var run=AgentRun.create("other","s","c","owner","推荐阿枫的应对队伍","pokemon-sword-shield","singles","r","zh-CN","t",null,Instant.now().plusSeconds(30));
        assertTrue(GymDialogueRouter.reply(new AgentRunContext(run),new ToolCallback[0]).isEmpty());
    }
}
