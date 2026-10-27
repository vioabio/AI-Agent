package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DamageAnswerEvidenceTest {
    @Test void referenceFailureExplainsRecoveryWithoutEchoingPrivateDetailsOrRequestingMoreStats() {
        var policy=new DamageAnswerEvidence();policy.observe("calculateBattleDamage","[tool.error] 工具执行失败：PokéAPI 正文读取超时 private-network-secret");
        assertFalse(policy.complete());assertTrue(policy.missingAnswer().contains("参考资料暂时无法读取"));assertFalse(policy.missingAnswer().contains("补齐"));assertFalse(policy.missingAnswer().contains("private-network-secret"));
        policy.observe("calculateBattleDamage","[tool.error] 本地资料缓存校验失败：private-path");assertTrue(policy.missingAnswer().contains("本地资料未通过校验"));assertFalse(policy.missingAnswer().contains("private-path"));
        var prerequisite=new DamageAnswerEvidence();prerequisite.begin("请调用calculateBattleDamage计算伤害");prerequisite.observe("lookupPokemonReference","[tool.error] PokéAPI 暂不可达");assertTrue(prerequisite.missingAnswer().contains("参考资料暂时无法读取"));
    }
    @Test void denialTimeoutAndGenericFailuresHaveBoundedMessages() {
        var policy=new DamageAnswerEvidence();policy.observe("calculateBattleDamage","[tool.denied] 缺少工具参数: $.request.field");assertTrue(policy.missingAnswer().contains("参数校验"));
        policy.observe("calculateBattleDamage","[tool.error] TOOL_EXECUTION_TIMEOUT: 5000ms secret");assertTrue(policy.missingAnswer().contains("执行超时"));assertFalse(policy.missingAnswer().contains("secret"));
        policy.observe("calculateBattleDamage","[tool.error] private-command-output");assertTrue(policy.missingAnswer().contains("执行失败"));assertFalse(policy.missingAnswer().contains("private-command-output"));
    }
    @Test void successfulRetryClearsEarlierFailure() throws Exception {
        var policy=new DamageAnswerEvidence();policy.observe("calculateBattleDamage","[tool.error] PokéAPI 暂不可达");policy.observe("calculateBattleDamage",result().toString());
        assertTrue(policy.complete());assertFalse(policy.verifiedSummary().contains("暂时无法读取"));policy.observe("calculateBattleDamage","not JSON");assertFalse(policy.missingAnswer().contains("暂时无法读取"));
    }
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
    @Test void implicitEngineAbilityIsClearlyPresentedAsAnAssumption() throws Exception {
        var value=result();value.putObject("conditionValidation").putArray("engineDefaultAbilitySides").add("attacker");
        var policy=new DamageAnswerEvidence();policy.observe("calculateBattleDamage",value.toString());
        assertTrue(policy.complete());assertTrue(policy.verifiedSummary().contains("引擎默认特性"));assertTrue(policy.verifiedSummary().contains("不表示用户已经选择"));
    }
}
