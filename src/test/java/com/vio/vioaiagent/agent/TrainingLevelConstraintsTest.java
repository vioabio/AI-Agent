package com.vio.vioaiagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TrainingLevelConstraintsTest {
    TrainingLevelConstraints parse(String text){return TrainingLevelConstraints.from(TrainingIntentState.resolve(List.of(),text));}
    @Test void explicitTargetsCapsAndPermissionsAreBounded(){
        var parsed=parse("目标等级：55级；练级：允许到60级");assertEquals(55,parsed.targetLevel());assertEquals(60,parsed.cap());assertTrue(parsed.permitsProjectionFrom(51));
        for(String invalid:List.of("0","101","55左右","-1"))assertNull(parse("目标等级："+invalid).targetLevel());
        assertEquals(TrainingLevelConstraints.Permission.UNKNOWN,parse("练级：允许到101级").permission());
    }
    @Test void unknownProhibitedAndConflictedConditionsCannotAuthorizeProjection(){
        for(String policy:List.of("不允许","不允许练级","禁止练级","不练级","不能练级","随便","允许到60级以上"))assertFalse(parse("目标等级：55；练级："+policy).permitsProjectionFrom(50));
        var state=TrainingIntentState.resolve(List.of(new UserMessage("目标等级：55；练级：允许")),"目标等级：60");assertFalse(TrainingLevelConstraints.from(state).permitsProjectionFrom(50));
    }
    @Test void equalLowerAndOverCapTargetsDoNotCreateUpgradeScenarios(){
        for(int target:List.of(49,50,61))assertFalse(parse("目标等级："+target+"；练级：允许到60级").permitsProjectionFrom(50));
    }
}
