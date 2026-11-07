package com.vio.vioaiagent.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DamageAdvicePolicyTest {
    static String archivedDraft() throws Exception {
        try(var input=DamageAdvicePolicyTest.class.getResourceAsStream("/agent/damage-advice-v1.json")) {
            assertNotNull(input);
            var fixture=new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
            String draft=fixture.path("draft").asText();
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(draft.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(fixture.path("draftSha256").asText(),hash);
            assertEquals("S2-DAMAGE-02",fixture.path("caseId").asText());
            assertEquals(1,fixture.path("repetition").asInt());
            return draft;
        }
    }
    @Test void archivedCandidateShowsTheSpecificGapWithoutReclassifyingPastResults() throws Exception {
        String draft=archivedDraft();
        assertTrue(new TeamAnswerEvidence().canPublishAdvice(draft));
        assertTrue(draft.contains("加入后区间会变"));
        assertFalse(DamageAdvicePolicy.canPublish(draft));
    }
    @Test void unsupportedConditionalOutcomeAssertionsNeedAnotherCalculation() {
        for(String text:new String[]{
                "当前条件不含光墙、钉子、异常状态与太晶化等，加入后区间会变。",
                "更换性格后伤害更高。", "换道具会提高伤害。", "伤害一定降低。", "移除后输出不变。",
                "Damage will change after adding a screen.", "Changing the item increases damage.",
                "The range would be higher.", "The damage may increase."}) {
            assertFalse(DamageAdvicePolicy.canPublish(text),text);
        }
    }
    @Test void verificationQuestionsAndExplicitlyWithheldConclusionsRemainAllowed() {
        for(String text:new String[]{
                "请重算后核对区间是否变化。", "核对伤害是否会提高。", "先比较伤害会不会变化，再决定配置。",
                "不能保证区间会变。", "不要断言伤害一定提高。",
                "Recalculate to determine whether damage will change.", "Do not assume damage will increase.",
                "保留当前配置作为比较基线，再根据目标对局逐项验证改动。"}) {
            assertTrue(DamageAdvicePolicy.canPublish(text),text);
        }
    }
    @Test void questionsOrNegationInOneClauseDoNotExcuseLaterAssertions() {
        for(String text:new String[]{
                "不能保证区间会变，但调整后伤害更高。",
                "是否变化需要核实；加入后区间会变。",
                "核对伤害是否会提高，但换道具会提高伤害。",
                "伤害是否变化仍需核实但伤害一定降低。",
                "不能保证伤害会变化但区间会降低。",
                "Do not assume damage will increase. The range will change."}) {
            assertFalse(DamageAdvicePolicy.canPublish(text),text);
        }
    }
    @Test void missingOrOversizedDraftsCannotPublish() {
        assertFalse(DamageAdvicePolicy.canPublish(null));assertFalse(DamageAdvicePolicy.canPublish(" "));
        assertFalse(DamageAdvicePolicy.canPublish("a".repeat(32_769)));
    }
}
