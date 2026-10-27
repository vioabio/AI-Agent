package com.vio.vioaiagent.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildLearningPlanningTest {
    @Test void aBuildReviewWithLearningDetailsHasOnePrimaryChecker() {
        var evidence = new GuideAnswerEvidence();
        evidence.begin("请检查下列杰尼龟配招并展示逐招朱紫学习记录和配置等级提示。未提供字段保留null。");
        var instruction = evidence.planningInstruction();
        assertTrue(instruction.contains("优先调用checkPokemonBuild"));
        assertTrue(instruction.contains("配招报告已包含逐招学习记录"));
        assertFalse(instruction.contains("调用lookupPokemonLearnset"));
        assertTrue(instruction.contains("未提供字段为null"));
    }
    @Test void aStandaloneLearningQuestionKeepsTheExistingLearningTool() {
        var evidence = new GuideAnswerEvidence();
        evidence.begin("请查询杰尼龟水枪的朱紫学习途径和等级");
        assertTrue(evidence.planningInstruction().contains("lookupPokemonLearnset"));
        assertFalse(evidence.planningInstruction().contains("优先调用checkPokemonBuild"));
    }
}
