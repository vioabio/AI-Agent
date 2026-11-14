package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.pokemon.team.TeamWorkflowService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TrainingStatProjectionTest {
    String answer(String condition){return TrainingStatProjection.describe(TrainingPlanAnswerBlockTest.read("u"),TrainingIntentState.resolve(List.of(),condition));}
    @Test void currentStatsUseExactSavedFieldsAndDoNotMutateAnything(){
        var read=TrainingPlanAnswerBlockTest.read("u");String before=read.snapshot().toString();
        String text=TrainingStatProjection.describe(read,TrainingIntentState.resolve(List.of(),null));
        assertTrue(text.contains("| 1 | 保存配置 Lv.51 | 121 | 62 | 87 | 113 | 86 | 96 |"));
        assertTrue(text.contains("不代表游戏内实际面板"));assertTrue(text.contains("不能据此判断先手或胜率"));assertEquals(before,read.snapshot().toString());
    }
    @Test void allowedTargetChangesOnlyLevelAndIsMarkedHypothetical(){
        String text=answer("目标等级：55；练级：允许到60级");
        assertTrue(text.contains("| 1 | 仅等级变为 55 的假设 | 130 | 66 | 94 | 122 | 92 | 104 |"));assertTrue(text.contains("其余保存参数不变"));
    }
    @Test void blockedUnknownLowerAndContradictoryTargetsShowCurrentStatsOnly(){
        for(String text:List.of("目标等级：55；练级：不允许","目标等级：55；练级：未知","目标等级：49；练级：允许","目标等级：65；练级：允许到60级","目标等级：101；练级：允许")){
            String answer=answer(text);assertTrue(answer.contains("本次只列保存配置"));assertFalse(answer.contains("| 1 | 仅等级变为"));
        }
    }
    @Test void mismatchedOrExpiredSnapshotsDoNotGenerateNumbers(){
        var read=TrainingPlanAnswerBlockTest.read("u");
        for(var invalid:List.of(new TeamWorkflowService.ReadTeam(read.snapshot(),List.of(read.facts().getLast(),read.facts().getFirst()),List.of(),read.expiresAt()),new TeamWorkflowService.ReadTeam(read.snapshot(),read.facts(),List.of(),Instant.now().minusSeconds(1)))){
            String text=TrainingStatProjection.describe(invalid,TrainingIntentState.resolve(List.of(),null));assertTrue(text.contains("本次不提供面板数值"));assertFalse(text.contains("| HP |"));
        }
    }
    @Test void cancellationNeverReturnsACompletedStatTable(){
        Thread.currentThread().interrupt();try{assertThrows(java.util.concurrent.CancellationException.class,()->answer(null));}finally{Thread.interrupted();}
    }
}
