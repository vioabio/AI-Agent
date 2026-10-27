package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.pokemon.reference.ResourceReferenceFacts;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceAnswerEvidenceTest {
    final ObjectMapper mapper=new ObjectMapper();
    ObjectNode resource(String slug) throws Exception {for(var row:mapper.readTree(Path.of("src/main/resources/pokemon-mechanics/references-v1.json").toFile()).path("records"))if(row.path("slug").asText().equals(slug))return (ObjectNode)row.deepCopy();throw new AssertionError();}
    String arguments(JsonNode f) throws Exception{return mapper.writeValueAsString(Map.of("kind",f.path("kind").asText(),"slug",f.path("slug").asText(),"dataReleaseId","pokeapi-reference-v1"));}
    GuideAnswerEvidence evidence(){var e=new GuideAnswerEvidence();e.begin("请介绍雪妖女并给出培养搭配");return e;}
    void observe(GuideAnswerEvidence e,String slug) throws Exception{var f=resource(slug);e.observe("lookupPokemonResource",f.toString(),arguments(f));}
    String froslass() throws Exception {
        var source=mapper.readTree(Path.of("src/main/resources/pokemon-strategy/sources-v1.json").toFile()).path("references").get(1);var fact=(ObjectNode)source.path("fact").deepCopy();fact.put("formKey","froslass").put("formNameZh","雪妖女");
        var detail=mapper.createObjectNode();detail.set("fact",fact);detail.set("abilities",source.path("availableAbilitySlugs"));detail.set("moves",source.path("availableMoveSlugs"));detail.put("retrievedAt","2026-10-23").put("limitation","本地参考");return detail.toString();
    }
    @Test void actualCandidateAndSvParaphraseAppearWithoutRawJsonOrUnsupportedNumbers() throws Exception {
        var e=evidence();e.observe("lookupPokemonReference",froslass());observe(e,"snow-cloak");String answer=e.verifiedAnswer();
        assertTrue(answer.contains("雪隐（特性）"));assertTrue(answer.contains("资料候选：雪妖女"));assertTrue(answer.contains("下雪时提升闪避"));assertTrue(answer.contains("项目中文释义（待复核）"));
        assertFalse(answer.contains("20%"));assertFalse(answer.contains("冰雹"));assertFalse(answer.contains("projectionSha256"));assertFalse(answer.contains("checksum"));
    }
    @Test void mismatchedUnboundOrTamperedResourcesCannotPublishMechanismFacts() throws Exception {
        var f=resource("snow-cloak");
        for(String input:Arrays.asList(null,"{}",arguments(resource("torrent")),arguments(f).replace("pokeapi-reference-v1","other"))) {
            var e=evidence();e.observe("lookupPokemonResource",f.toString(),input);assertFalse(e.verifiedAnswer().contains("下雪时提升闪避"));assertTrue(e.verifiedAnswer().contains("未通过校验"));}
        var changed=f.deepCopy();changed.put("explanationZh","伪造内容");var e=evidence();e.observe("lookupPokemonResource",changed.toString(),arguments(f));assertFalse(e.verifiedAnswer().contains("伪造内容"));
    }
    @Test void failedSameResourceInvalidatesOnlyItsFactsAndUnknownFailureClearsAll() throws Exception {
        var e=evidence();observe(e,"snow-cloak");observe(e,"cursed-body");e.observe("lookupPokemonResource","[tool.error] private-detail",arguments(resource("snow-cloak")));
        assertFalse(e.verifiedAnswer().contains("下雪时提升闪避"));assertTrue(e.verifiedAnswer().contains("诅咒之躯（特性）"));assertFalse(e.verifiedAnswer().contains("private-detail"));
        e.observe("lookupPokemonResource","[tool.error] unknown");assertFalse(e.verifiedAnswer().contains("机制资料（本次读取）"));
    }
    @Test void unrelatedAbilityIsNotPromotedToTheCurrentSpeciesOrToASavedChoice() throws Exception {
        var e=evidence();e.observe("lookupPokemonReference",froslass());observe(e,"torrent");String answer=e.verifiedAnswer();
        assertTrue(answer.contains("激流（特性）"));assertTrue(answer.contains("尚未核对到当前成员的对应候选"));assertFalse(answer.contains("资料候选：雪妖女"));
    }
    @Test void natureAndStatusFieldsAreReadableButNullAccuracyNeverBecomesGuaranteedHit() throws Exception {
        var e=evidence();observe(e,"timid");observe(e,"modest");observe(e,"protect");String answer=e.verifiedAnswer();
        assertTrue(answer.contains("提升速度，降低攻击"));assertTrue(answer.contains("提升特攻，降低攻击"));assertTrue(answer.contains("威力：未提供 · 命中：未提供"));assertFalse(answer.contains("命中：0%"));
        assertTrue(answer.contains("字段未提供不等于0或必中"));
    }
    @Test void displayEscapesSourceTextAndLegacyReadCannotKeepAnOlderStructuredFact() throws Exception {
        var e=evidence();var f=resource("snow-cloak");f.put("explanationZh","<script>alert(1)</script> [假的](javascript:x)");ResourceReferenceFacts.seal(f);e.observe("lookupPokemonResource",f.toString(),arguments(f));
        assertFalse(e.verifiedAnswer().contains("<script>"));assertTrue(e.verifiedAnswer().contains("&lt;script&gt;"));
        e.observe("lookupPokemonResource","{\"kind\":\"ability\",\"slug\":\"snow-cloak\",\"dataReleaseId\":\"pokeapi-reference-v1\"}",arguments(f));assertFalse(e.verifiedAnswer().contains("机制资料（本次读取）"));
    }
}
