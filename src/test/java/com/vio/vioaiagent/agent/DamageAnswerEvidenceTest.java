package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DamageAnswerEvidenceTest {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static ObjectNode result() throws Exception {
        return (ObjectNode) MAPPER.readTree(Path.of("evaluation/campus/s01/damage-reference.json").toFile()).path("result").deepCopy();
    }
    @Test void rendersActualEngineNumbersAndAssumptionsRatherThanModelProse() throws Exception {
        var policy = new DamageAnswerEvidence();
        policy.begin("请调用calculateBattleDamage计算伤害");
        policy.observe("lookupPokemonResource",result().toString());
        assertFalse(policy.complete());
        policy.observe("calculateBattleDamage",result().toString());
        assertTrue(policy.complete());
        assertTrue(policy.verifiedSummary().contains("164～194 HP"));
        assertTrue(policy.verifiedSummary().contains("142.61%～168.70%"));
        assertTrue(policy.verifiedSummary().contains("特攻252"));
        assertFalse(policy.verifiedSummary().contains("必定击倒"));
    }
    @Test void inconsistentPercentagesUnsupportedVersionsAndMalformedResultsAreNotEvidence() throws Exception {
        var policy = new DamageAnswerEvidence();
        ObjectNode value = result();
        ((ObjectNode)value.path("percent")).put("minimum",99);
        policy.observe("calculateBattleDamage",value.toString());
        assertFalse(policy.complete());
        value = result(); value.put("engineVersion","unverified");
        policy.observe("calculateBattleDamage",value.toString());
        assertFalse(policy.complete());
        value = result(); ((ObjectNode)value.path("assumptions").path("attacker").path("ivs")).remove("hp");
        policy.observe("calculateBattleDamage",value.toString());
        assertFalse(policy.complete());
        policy.observe("calculateBattleDamage",result().toString());
        assertTrue(policy.complete());
        policy.observe("calculateBattleDamage","[tool.error] 计算失败");
        assertFalse(policy.complete());
    }
}
