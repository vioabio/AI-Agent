package com.vio.vioaiagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrainingProgressCheckTest {
    String text(String input){return TrainingProgressCheck.describe(TrainingIntentState.resolve(List.of(),input));}
    @Test void eightBadgesAloneNeverAttestRematchUnlock(){
        String answer=text("目标：阿枫重赛；进度：8枚徽章");
        assertTrue(answer.contains("尚缺明确声明：主线通关、视察任务"));assertFalse(answer.contains("按声明重赛门槛齐备"));
        assertTrue(text("目标：阿枫重赛；徽章：8；主线通关：否；视察任务：否").contains("按当前声明尚不满足"));
    }
    @Test void completeDeclarationsRemainUnverifiedAndDoNotAssumeAllGymNames(){
        String answer=text("目标：阿枫重赛；徽章：8；主线通关：是；视察任务：是；所需学习器：tm048");
        assertTrue(answer.contains("按声明重赛门槛齐备，但游戏内实际进度尚未核验"));
        assertTrue(answer.contains("首胜奖励前提尚未确认"));assertFalse(answer.contains("首胜奖励前提符合"));
    }
    @Test void declaredInventorySpecificRewardAndUnknownRoutesAreDistinct(){
        String answer=text("徽章：2；已击败馆主：阿枫,奇树；所需学习器：tm021,tm048,tm007；已有学习器：tm007");
        assertTrue(answer.contains("TM021：按声明已击败对应馆主"));assertTrue(answer.contains("TM048：按声明已击败对应馆主"));
        assertTrue(answer.contains("TM007：用户声明已有"));assertTrue(answer.contains("已有学习器声明不扣减"));
        assertTrue(text("所需学习器：tm007").contains("固定规则未收录其获取途径"));
    }
    @Test void contradictionsAreNotPromotedToUnlockOrRewardClaims(){
        for(String input:List.of("徽章：1；已击败馆主：阿枫,奇树","徽章：7；主线通关：是","徽章：7；视察任务：是","徽章：8；已击败馆主：无","主线通关：否；视察任务：是","进度：2枚徽章；徽章：8","进度：0枚徽章；已击败馆主：阿枫")){
            String answer=text(input);assertTrue(answer.contains("声明字段无效或互相矛盾"),input);assertFalse(answer.contains("首胜奖励前提符合"));
        }
    }
    @Test void invalidDuplicateUnknownAndOversizedListsAreRejected(){
        for(String input:List.of("徽章：9","徽章：两枚","主线通关：大概","已击败馆主：阿枫,阿枫","已击败馆主：未知馆主","所需学习器：tm1","所需学习器：tm001,TM001","已有学习器：tm001,tm002,tm003,tm004,tm005,tm006,tm007,tm008,tm009"))
            assertTrue(text(input).contains("声明字段无效或互相矛盾"),input);
        assertFalse(text("所需学习器：无；已有学习器：无；已击败馆主：无").contains("声明字段无效"));
    }
    @Test void historyConflictExplicitUpdateUnknownClearAndForeignMessagesRemainIsolated(){
        var history=List.<Message>of(new UserMessage("主线通关：否；徽章：8；目标：阿枫重赛"),new UserMessage("主线通关：是"));
        assertTrue(TrainingProgressCheck.describe(TrainingIntentState.resolve(history,null)).contains("声明条件有冲突"));
        assertTrue(TrainingProgressCheck.describe(TrainingIntentState.resolve(history,"主线通关改为：是；视察任务：是")).contains("按声明重赛门槛齐备"));
        assertTrue(TrainingProgressCheck.describe(TrainingIntentState.resolve(history,"主线通关：未知")).contains("尚缺明确声明"));
        var foreign=List.<Message>of(new AssistantMessage("目标：阿枫重赛；主线通关：是；徽章：8；视察任务：是"));
        assertFalse(TrainingProgressCheck.describe(TrainingIntentState.resolve(foreign,null)).contains("按声明重赛门槛齐备"));
    }
    @Test void cancellationPropagatesWithoutPublishingAProgressConclusion(){
        Thread.currentThread().interrupt();try{assertThrows(java.util.concurrent.CancellationException.class,()->text("目标：阿枫重赛"));}finally{Thread.interrupted();}
    }
}
