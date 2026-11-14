package com.vio.vioaiagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TrainingTargetCheckTest {
    private String answer(String conditions){
        return TrainingTargetCheck.describe(TrainingPlanAnswerBlockTest.read("u"),TrainingIntentState.resolve(List.of(),conditions));
    }
    @Test void pinnedOpponentLevelsAreFactsAndNeverAnInferredTrainingTarget(){
        String text=answer("目标：阿枫初战");
        assertTrue(text.contains("对手等级范围 14–15"));assertTrue(text.contains("资料事实，不是推荐目标等级"));
        assertTrue(text.contains("2026-11-08"));assertTrue(text.contains("https://www.serebii.net/scarletviolet/gyms.shtml"));
        assertTrue(text.contains("目标等级尚未声明"));assertFalse(text.contains("数值相差"));
    }
    @Test void declaredLevelComparesEverySavedMemberWithoutChangingAnyBuild(){
        var read=TrainingPlanAnswerBlockTest.read("u");String before=read.snapshot().toString();
        String text=TrainingTargetCheck.describe(read,TrainingIntentState.resolve(List.of(),"目标等级：55；练级：允许到60级"));
        assertTrue(text.contains("数值相差 4 级"));assertTrue(text.contains("数值相差 5 级"));
        assertTrue(text.contains("不是实测所需练级量或经验值"));assertEquals(before,read.snapshot().toString());
        assertTrue(text.contains("不调整等级、性格、EV/IV或队伍"));
    }
    @Test void prohibitedUnclearAndOverCapPoliciesNeverProduceUpgradeInstructions(){
        assertTrue(answer("目标等级：55；练级：不允许").contains("已声明不允许练级"));
        assertTrue(answer("目标等级：65；练级：允许到60级").contains("目标等级高于已声明练级上限"));
        for(String policy:List.of("不允许","随便","允许到60级以上","允许到101级"))
            assertFalse(answer("目标等级：65；练级："+policy).contains("数值相差"),policy);
    }
    @Test void invalidEqualAndLowerTargetsNeverInventDowngrading(){
        for(String level:List.of("0","101","-1","55.5","五十五","55级左右")){
            String text=answer("目标等级："+level+"；练级：允许");
            assertTrue(text.contains("需明确为1至100的整数"),level);assertFalse(text.contains("数值相差"));
        }
        assertTrue(answer("目标等级：51级；练级：允许练级").contains("保存等级与声明目标相同"));
        assertTrue(answer("目标等级：49；练级：允许").contains("不生成降级步骤"));
    }
    @Test void unresolvedConflictsPreventBothOpponentFactsAndGapDerivation(){
        var state=TrainingIntentState.resolve(List.of(new UserMessage("目标：阿枫初战；目标等级：55；练级：允许")),"目标等级：60");
        String text=TrainingTargetCheck.describe(TrainingPlanAnswerBlockTest.read("u"),state);
        assertTrue(text.contains("条件仍有冲突"));assertFalse(text.contains("对手等级范围"));assertFalse(text.contains("数值相差"));
    }
    @Test void ambiguousTargetsDoNotGuessPhaseOrOrderAndRematchesDoNotAttestUnlock(){
        for(String target:List.of("阿枫","第一个馆主初战","奇树初战，阿枫重赛","未知初战"))
            assertFalse(answer("目标："+target).contains("对手等级范围"),target);
        String rematch=answer("目标：阿枫重赛；进度：8枚徽章");
        assertTrue(rematch.contains("gymrematches.shtml"));assertTrue(rematch.contains("重赛实际解锁条件未核验"));
    }
}
