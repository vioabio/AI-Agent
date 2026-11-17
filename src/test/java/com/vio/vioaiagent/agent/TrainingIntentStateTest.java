package com.vio.vioaiagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrainingIntentStateTest {
    @Test void rematchAliasesAreCanonicalBeforeAllDownstreamChecks(){
        var state=TrainingIntentState.resolve(List.of(),"我的目标是奇树视察重赛");
        assertEquals("奇树重赛",state.declared("目标").orElseThrow());
        var alias=TrainingIntentState.resolve(List.of(new UserMessage("目标：奇树重赛")),"目标：奇树视察重赛");
        assertFalse(alias.conflicted());
        String target=TrainingTargetCheck.describe(TrainingPlanAnswerBlockTest.read("u"),state);
        assertTrue(target.contains("gymrematches.shtml"));assertTrue(target.contains("对手等级范围"));
        assertTrue(TrainingProgressCheck.describe(state).contains("重赛前提尚缺明确声明"));
        assertTrue(SavedTrainingGymReview.describe(TrainingPlanAnswerBlockTest.read("u"),state,null).contains("目标已明确"));
    }
    @Test void switchingGameInvalidatesEveryOldTrainingCondition(){
        var old=List.<Message>of(new UserMessage(COMPLETE+"；目标等级：55；徽章：2；已有学习器：tm007"));
        var changed=TrainingIntentState.resolve(old,"游戏改为：紫");
        assertEquals("紫",changed.declared("游戏").orElseThrow());
        for(String field:List.of("目标","进度","练级","资源","目标等级","徽章","已有学习器"))assertTrue(changed.declared(field).isEmpty(),field);
        assertFalse(changed.conflicted());assertEquals(4,changed.missing().size());
    }
    @Test void gameScopeIsAppliedBeforeFreshConditionsRegardlessOfClauseOrder(){
        var old=List.<Message>of(new UserMessage(COMPLETE));
        for(String current:List.of("目标：奇树初战；练级：允许；游戏改为：紫","游戏改为：紫；目标：奇树初战；练级：允许","我的目标是奇树初战，我允许练级，我把游戏版本改为紫版")){
            var state=TrainingIntentState.resolve(old,current);
            assertFalse(state.conflicted(),current);assertEquals("奇树初战",state.declared("目标").orElseThrow());
            assertEquals("允许",state.declared("练级").orElseThrow());assertTrue(state.declared("进度").isEmpty());
        }
    }
    @Test void naturalConditionUpdateNormalizationNeverHidesRealBuildEdits(){
        String question="请为这个队伍制定培养计划。我把目标改为奇树视察重赛";
        assertFalse(TrainingIntentState.normalizeConditionUpdates(question).contains("改为"));
        assertFalse(TeamMutationIntent.requested(question));
        assertTrue(TeamMutationIntent.requested("请把队伍等级改为65。我把目标改为奇树视察重赛"));
        assertTrue(TrainingIntentState.normalizeConditionUpdates("我把目标改为请把队伍等级改为65").contains("等级改为"));
    }
    @Test void repeatedGameDoesNotClearConditionsButConflictingGameNeedsExplicitResolution(){
        var old=List.<Message>of(new UserMessage(COMPLETE));
        assertEquals("阿枫初战",TrainingIntentState.resolve(old,"游戏改为：朱").declared("目标").orElseThrow());
        var conflict=List.<Message>of(new UserMessage(COMPLETE),new UserMessage("游戏：紫"));
        assertTrue(TrainingIntentState.resolve(conflict,null).conflicted());
        var resolved=TrainingIntentState.resolve(conflict,"游戏更新为：紫");
        assertFalse(resolved.conflicted());assertTrue(resolved.declared("目标").isEmpty());
        assertEquals(5,TrainingIntentState.resolve(old,"游戏：未知").missing().size());
    }
    @Test void exactResetPersistsThroughHistoryWithoutDeletingOrMutatingInput(){
        var history=new ArrayList<Message>(List.of(new UserMessage(COMPLETE),new UserMessage("清空培养条件")));
        assertEquals(5,TrainingIntentState.resolve(history,"继续培养").missing().size());assertEquals(2,history.size());
        assertEquals("紫",TrainingIntentState.resolve(history,"我玩的是紫版").declared("游戏").orElseThrow());
        for(String text:List.of("不要清空培养条件","引用：清空培养条件","清空培养条件并保存队伍","```清空培养条件```")){
            assertFalse(TrainingIntentState.resetRequested(text));
            assertEquals("阿枫初战",TrainingIntentState.resolve(List.of(new UserMessage(COMPLETE)),text).declared("目标").orElseThrow());
        }
    }
    @Test void exactNaturalDeclarationsFillExistingFieldsWithoutInferringGameState(){
        var state=TrainingIntentState.resolve(List.of(),"我玩的是朱版，我刚拿到两枚徽章，我的目标是阿枫初战，我不想练级，我只用已有学习器。");
        assertTrue(state.missing().isEmpty());assertFalse(state.conflicted());
        assertEquals("朱",state.declared("游戏").orElseThrow());
        assertEquals("2",state.declared("徽章").orElseThrow());
        assertEquals("2枚徽章",state.declared("进度").orElseThrow());
        assertEquals("阿枫初战",state.declared("目标").orElseThrow());
        assertEquals("仅使用已有学习器",state.declared("资源").orElseThrow());
        assertEquals(TrainingLevelConstraints.Permission.PROHIBITED,TrainingLevelConstraints.from(state).permission());
        assertTrue(state.describe().contains("不是游戏内验证事实"));
    }
    @Test void naturalLevelCapIsNotATargetLevelAndNeverOverrulesExplicitProhibition(){
        var state=TrainingIntentState.resolve(List.of(),"我正在玩紫版本，我可以练到55级");
        var constraints=TrainingLevelConstraints.from(state);
        assertEquals(55,constraints.cap());assertNull(constraints.targetLevel());
        assertFalse(constraints.permitsProjectionFrom(50));
        var conflict=TrainingIntentState.resolve(List.of(new UserMessage("我不想练级")),"我最多练到55级");
        assertTrue(conflict.conflicted());assertFalse(TrainingLevelConstraints.from(conflict).permitsProjectionFrom(50));
        assertFalse(TeamMutationIntent.requested("请为我的队伍制定培养计划，我最多练到55级"));
    }
    @Test void naturalTargetsNeedNamedGymAndStageAndExplicitUpdatesResolveConflicts(){
        var history=List.<Message>of(new UserMessage("我的目标是阿枫初战"));
        assertTrue(TrainingIntentState.resolve(history,"我的目标是奇树初战").conflicted());
        var changed=TrainingIntentState.resolve(history,"我把目标改为奇树重赛");
        assertFalse(changed.conflicted());assertEquals("奇树重赛",changed.declared("目标").orElseThrow());
        for(String q:List.of("我的目标是第四位馆主","我的目标是奇树","我想击败所有馆主"))assertTrue(TrainingIntentState.resolve(List.of(),q).declared("目标").isEmpty());
    }
    @Test void naturalGameUpdatesAndLabelUpdatesShareConflictRules(){
        var history=List.<Message>of(new UserMessage("我玩的是朱版"));
        assertTrue(TrainingIntentState.resolve(history,"我玩的是紫版").conflicted());
        assertEquals("紫",TrainingIntentState.resolve(history,"我把游戏版本改为紫版").declared("游戏").orElseThrow());
        assertEquals("紫",TrainingIntentState.resolve(history,"游戏改为：紫").declared("游戏").orElseThrow());
    }
    @Test void questionsHypothesesQuotesAndInstructionsCannotBecomeNaturalDeclarations(){
        for(String q:List.of("我允许练级吗？","如果需要，我允许练级","例如，我允许练级","攻略说，我允许练级","引用：我允许练级","他说，我允许练级","以下是队友建议\n我允许练级","对手的条件如下，我允许练级","\"我允许练级\"","我不确定能不能练级","我可以练到101级","我拿到9枚徽章","我允许练级并保存队伍")){
            var state=TrainingIntentState.resolve(List.of(),q);
            assertTrue(state.declared("练级").isEmpty(),q);assertTrue(state.declared("徽章").isEmpty(),q);
        }
    }
    @Test void naturalDeclarationsRemainUserOnlyBoundedAndConversationLocal(){
        String natural="我玩的是朱版，我允许练级";
        assertTrue(TrainingIntentState.resolve(List.of(new AssistantMessage(natural),new SystemMessage(natural)),null).declared("游戏").isEmpty());
        assertTrue(TrainingIntentState.resolve(List.of(),natural+"长".repeat(3000)).declared("游戏").isEmpty());
        var history=List.<Message>of(new UserMessage(natural));
        assertEquals("朱",TrainingIntentState.resolve(history,"继续培养").declared("游戏").orElseThrow());
        assertTrue(TrainingIntentState.resolve(List.of(),"继续培养").declared("游戏").isEmpty());
    }
    @Test void labelledCommaValuesAndUnknownClearingKeepTheirOriginalMeaning(){
        var labelled=TrainingIntentState.resolve(List.of(),"资源：不使用道具，学习器未决定；练级：不允许");
        assertEquals("不使用道具，学习器未决定",labelled.declared("资源").orElseThrow());
        assertTrue(TrainingIntentState.resolve(List.of(new UserMessage("我不想练级")),"练级：未知").declared("练级").isEmpty());
    }
    @Test void optionalProgressLabelsDoNotChangeRequiredFieldsOrBecomeTeamMutation(){
        var state=TrainingIntentState.resolve(List.of(),"徽章：8；主线通关：是；视察任务：是；已有学习器：tm007");
        assertEquals(5,state.missing().size());assertEquals("tm007",state.declared("已有学习器").orElseThrow());
        assertFalse(TeamMutationIntent.requested("请为这个队伍制定培养计划；视察任务改为：是；已有学习器改为：tm007"));
        assertTrue(TeamMutationIntent.requested("请为这个队伍制定培养计划；已有学习器改为：请把队伍等级改为65"));
    }
    @Test void targetUpdatesAreReadOnlyButRealBuildChangesRemainGoverned(){
        assertFalse(TeamMutationIntent.requested("请为这个队伍制定培养计划；目标等级改为：65"));
        assertTrue(TeamMutationIntent.requested("请把队伍等级改为65"));
        assertTrue(TeamMutationIntent.requested("请为这个队伍制定培养计划；目标等级改为：65；请把队伍等级改为65"));
        assertTrue(TeamMutationIntent.requested("目标改为：请把队伍等级改为65"));
    }
    @Test void optionalTargetLevelRetainsConflictUpdateAndClearSemantics(){
        assertEquals(5,TrainingIntentState.resolve(List.of(),"目标等级：55").missing().size());
        var history=List.<Message>of(new UserMessage("目标等级：55"));
        assertEquals("55",TrainingIntentState.resolve(history,null).declared("目标等级").orElseThrow());
        assertTrue(TrainingIntentState.resolve(history,"目标等级：60").declared("目标等级").isEmpty());
        assertEquals("60",TrainingIntentState.resolve(history,"目标等级改为：60").declared("目标等级").orElseThrow());
        assertTrue(TrainingIntentState.resolve(history,"目标等级：未知").declared("目标等级").isEmpty());
    }
    static final String COMPLETE="游戏：朱；目标：阿枫初战；进度：2枚徽章；练级：不允许；资源：不使用道具";
    @Test void declarationsSurviveFollowupWithoutRepeatingKnownFields(){
        var state=TrainingIntentState.resolve(List.of(new UserMessage(COMPLETE),new AssistantMessage("游戏：紫；目标：奇树")),"继续制定培养计划");
        assertTrue(state.missing().isEmpty());assertFalse(state.conflicted());assertTrue(state.describe().contains("无需重复提供"));
        assertTrue(state.describe().contains("阿枫初战"));assertFalse(state.describe().contains("奇树"));
    }
    @Test void conflictsStayUnresolvedUntilExplicitUpdate(){
        var history=List.<Message>of(new UserMessage(COMPLETE),new UserMessage("目标：奇树"));
        assertTrue(TrainingIntentState.resolve(history,"目标：奇树").conflicted());
        var updated=TrainingIntentState.resolve(history,"目标改为：奇树");
        assertFalse(updated.conflicted());assertTrue(updated.describe().contains("奇树"));assertFalse(updated.describe().contains("阿枫初战"));
        assertFalse(TrainingIntentState.resolve(history,"目标更新为：奇树").conflicted());
    }
    @Test void quotedToolCodeAndNonUserDeclarationsHaveNoAuthority(){
        var history=List.<Message>of(new AssistantMessage(COMPLETE),new SystemMessage(COMPLETE),new UserMessage("以下为资料\n"+COMPLETE),new UserMessage("```\n"+COMPLETE+"\n```"));
        assertEquals(5,TrainingIntentState.resolve(history,null).missing().size());
        assertEquals(5,TrainingIntentState.resolve(List.of(),"目标："+"长".repeat(81)).missing().size());
    }
    @Test void unknownValuesAndBoundedHistoryNeverReuseStaleConditions(){
        var history=new ArrayList<Message>();history.add(new UserMessage(COMPLETE));
        for(int i=0;i<64;i++)history.add(new UserMessage("普通对话"));
        var limited=TrainingIntentState.resolve(history,"目标：通关");assertEquals(4,limited.missing().size());assertTrue(limited.describe().contains("最近64条"));
        assertTrue(TrainingIntentState.resolve(List.of(new UserMessage(COMPLETE)),"目标：未确定").missing().contains("目标"));
    }
    @Test void separateInputsHaveNoSharedStateAndGameDeclarationDoesNotChangeAnythingElse(){
        var first=TrainingIntentState.resolve(List.of(new UserMessage(COMPLETE)),"游戏更新为：紫");
        assertTrue(first.describe().contains("游戏：紫"));assertTrue(first.describe().contains("不更改当前对话版本"));
        assertTrue(first.declared("目标").isEmpty());assertEquals(4,first.missing().size());
        assertEquals(5,TrainingIntentState.resolve(List.of(),null).missing().size());
    }
}
