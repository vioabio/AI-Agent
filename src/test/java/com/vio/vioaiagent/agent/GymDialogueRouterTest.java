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
    @Test void rematchPronounsAndStageComparisonUseTheCheckedCatalog() {
        var history = List.<Message>of(new UserMessage("先查看奇树初战的队伍。"));
        var model = mock(ChatModel.class);
        var executor = new DefaultRunExecutor(model, new ToolCallback[0], null, null, null, null, false);
        String roster = executor.execute(context("接着看她的重赛阵容。", history));
        assertTrue(roster.contains("奇树"));
        assertTrue(roster.contains("伦琴猫"));
        assertTrue(roster.contains("顽皮雷弹"));
        assertTrue(roster.contains("| 66 |"));
        assertFalse(roster.contains("巴布土拨"));
        var continued = List.<Message>of(history.getFirst(), new AssistantMessage("错误模型文字，最高65级"),
                new UserMessage("接着看她的重赛阵容。"));
        String levels = executor.execute(context("把刚才初战与重赛的最高等级分别告诉我。", continued));
        assertTrue(levels.contains("初战**：24级"));
        assertTrue(levels.contains("视察重赛**：66级"));
        assertTrue(levels.contains("gymrematches.shtml"));
        verifyNoInteractions(model);
        assertTrue(GymDialogueRouter.reply(context("如何修改重赛队伍里的性格？", history), new ToolCallback[0]).isEmpty());
    }

    @Test void currentGymNameRespectsTheLatestUserSelection() {
        var history = List.<Message>of(new UserMessage("先记录背景，我目前只讨论阿枫初战，本轮只回复收到。"),
                new UserMessage("现在换成古鲁夏初战，请列出他的成员和等级。"), new AssistantMessage("阿枫"));
        var model = mock(ChatModel.class);
        String name = new DefaultRunExecutor(model, new ToolCallback[0], null, null, null, null, false)
                .execute(context("当前谈的是哪位馆主？只回答名字。", history));
        assertEquals("古鲁夏", name);
        verifyNoInteractions(model);
    }

    @Test void battleModeFactsUseCatalogWithoutRequestingPlayerProgress() {
        for (String question : List.of("先看莱姆初战的对战模式。", "奇树初战是单打还是双打？")) {
            var ctx = context(question,List.of()); var model = mock(ChatModel.class);
            var executor = new DefaultRunExecutor(model,new ToolCallback[0],null,null,null,null,false);
            String answer = executor.execute(ctx);
            assertTrue(answer.contains(question.contains("莱姆")?"双打":"单打"));
            assertTrue(answer.contains("Serebii")); assertFalse(answer.contains("先确认你的冒险进度"));
            verifyNoInteractions(model);
        }
    }
    @Test void oneMemberFollowupCannotInventDoubleBattleMechanicsOrActionTables() {
        var history=List.<Message>of(new UserMessage("莱姆初战的对战模式"));
        var model=mock(ChatModel.class);
        var executor=new DefaultRunExecutor(model,new ToolCallback[0],null,null,null,null,false);
        String answer=executor.execute(context("我只有一名成员，可以据此给完整双打出招顺序吗？",history));
        assertTrue(answer.contains("莱姆"));assertTrue(answer.contains("尚不足以"));
        assertTrue(answer.contains("实际速度"));assertFalse(answer.contains("画皮"));verifyNoInteractions(model);
        assertTrue(GymDialogueRouter.reply(context("我不是只有一名成员，可以给完整双打出招顺序吗？",history),new ToolCallback[0]).isEmpty());
    }
    @Test void negativeActionsDoNotReplaceRosterAndBoundFollowupsNeverUseModelMemory() {
        var tools = new ToolCallback[0];
        var larry = List.<Message>of(new UserMessage("我只想看青木的队伍资料，不要推荐我方。"));
        String roster = GymDialogueRouter.reply(context(larry.getFirst().getText(), List.of()), tools).orElseThrow();
        assertTrue(roster.contains("树枕尾熊")); assertTrue(roster.contains("姆克鹰"));
        assertFalse(roster.contains("队伍推荐的缺口"));
        String ace = GymDialogueRouter.reply(context("他的太晶成员是哪只？", larry), tools).orElseThrow();
        assertTrue(ace.contains("姆克鹰")); assertTrue(ace.contains("太晶属性 **一般**")); assertFalse(ace.contains("长毛狗"));
        String rematch = GymDialogueRouter.reply(context("改看重赛，不需要分析我的队伍。", List.of(new UserMessage("请只查询寇沙初战资料。"))), tools).orElseThrow();
        assertTrue(rematch.contains("视察重赛")); assertTrue(rematch.contains("寇沙")); assertFalse(rematch.contains("没有唯一匹配"));
        String switched = GymDialogueRouter.reply(context("现在改问奇树，别沿用刚才的冰系馆主。", List.of(new UserMessage("古鲁夏初战资料"))), tools).orElseThrow();
        assertTrue(switched.contains("奇树")); assertTrue(switched.contains("梦妖魔")); assertFalse(switched.contains("古鲁夏"));
        String list = GymDialogueRouter.reply(context("请按参考等级顺序列出朱紫馆主，并说明不是强制挑战顺序。", List.of()), tools).orElseThrow();
        assertTrue(list.contains("不是强制挑战顺序"));
        String ordinal = GymDialogueRouter.reply(context("看刚才表格倒数第二位的初战配置。", List.of(new UserMessage("列出朱紫馆主"), new AssistantMessage(list))), tools).orElseThrow();
        assertTrue(ordinal.contains("莉普")); assertTrue(ordinal.contains("花洁夫人")); assertTrue(ordinal.contains("招式"));
    }

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
