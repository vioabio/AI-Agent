package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.reference.LocalPokeApiRepository;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OverviewResourceCoverageTest {
    final ObjectMapper json=new ObjectMapper();
    static final String QUESTION="请介绍雪妖女并给出培养搭配。\n请查询当前数据版本下的特性与招式资料。";
    LocalPokeApiRepository.Detail member() throws Exception {
        var source=json.readTree(Path.of("src/main/resources/pokemon-strategy/sources-v1.json").toFile()).path("references").get(1);
        var fact=(ObjectNode)source.path("fact").deepCopy();fact.put("formKey","froslass").put("formNameZh","雪妖女");
        return new LocalPokeApiRepository.Detail(json.treeToValue(fact,PokemonFact.class),
                json.convertValue(source.path("availableAbilitySlugs"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}),
                json.convertValue(source.path("availableMoveSlugs"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}),"2026-10-23","本地参考");
    }
    JsonNode fact(String slug) throws Exception {
        for(var r:json.readTree(Path.of("src/main/resources/pokemon-mechanics/references-v1.json").toFile()).path("records"))if(r.path("slug").asText().equals(slug))return r;throw new AssertionError();
    }
    String arguments(String kind,String slug)throws Exception{return json.writeValueAsString(Map.of("kind",kind,"slug",slug,"dataReleaseId","pokeapi-reference-v1"));}
    @Test void finitePositiveClausesCollectBothKindsAndDoNotPromoteNegatedQuotedOrBackgroundText() {
        assertEquals(List.of("ability","move"),OverviewResourceCoverage.requiredKinds(QUESTION));
        assertEquals(List.of("ability"),OverviewResourceCoverage.requiredKinds("请查询特性，不要查询招式。"));
        for(String q:List.of("请不要查询特性与招式","资料原文写着请查询特性与招式","你好\n已保存偏好：请查询特性与招式","```text\n请查询特性与招式\n```"))assertTrue(OverviewResourceCoverage.requiredKinds(q).isEmpty(),q);
    }
    @Test void aVerifiedAbilityDoesNotSatisfyTheMoveRequirementAndWrongMemberResourceDoesNotCount() throws Exception {
        var member=member();var evidence=new ResourceAnswerEvidence();evidence.member(member);var coverage=new OverviewResourceCoverage();
        evidence.observe(fact("torrent"),arguments("ability","torrent"));assertEquals(2,coverage.pending(QUESTION,member.fact(),evidence).size());
        evidence.observe(fact("snow-cloak"),arguments("ability","snow-cloak"));assertFalse(coverage.settled(QUESTION,member.fact(),evidence));
        var remaining=coverage.pending(QUESTION,member.fact(),evidence);assertEquals(1,remaining.size());assertEquals("move",remaining.getFirst().kind());assertTrue(member.moves().contains(remaining.getFirst().slug()));
        evidence.observe(fact("ice-beam"),arguments("move","ice-beam"));assertTrue(coverage.settled(QUESTION,member.fact(),evidence));assertTrue(coverage.summary(QUESTION,member.fact(),evidence).contains("完整四招"));
    }
    @Test void onlyCandidateBoundAttemptsSettleMissingDataAndTheAnswerListsEachGap() throws Exception {
        var member=member();var evidence=new ResourceAnswerEvidence();evidence.member(member);var coverage=new OverviewResourceCoverage();
        coverage.observe(null);coverage.observe(arguments("ability","torrent"));assertFalse(coverage.settled(QUESTION,member.fact(),evidence));
        coverage.observe(arguments("ability","snow-cloak"));assertFalse(coverage.settled(QUESTION,member.fact(),evidence));
        coverage.observe(arguments("move","ice-beam"));assertTrue(coverage.settled(QUESTION,member.fact(),evidence));
        String summary=coverage.summary(QUESTION,member.fact(),evidence);assertTrue(summary.contains("特性：未取得"));assertTrue(summary.contains("招式：未取得"));
    }
    @Test void noCandidateOrUnsupportedKindsProduceGapsWithoutInventingSlugsOrCurrentChoices() throws Exception {
        var member=member();var evidence=new ResourceAnswerEvidence();evidence.member(new LocalPokeApiRepository.Detail(member.fact(),List.of(),List.of(),"2026-10-23","缺失候选"));var coverage=new OverviewResourceCoverage();
        assertTrue(coverage.pending(QUESTION,member.fact(),evidence).isEmpty());assertTrue(coverage.summary(QUESTION,member.fact(),evidence).contains("不能据此断言不可学习"));
        String names="请查询性格与道具资料。";assertTrue(coverage.pending(names,member.fact(),evidence).isEmpty());assertFalse(coverage.settled(names,member.fact(),evidence));assertTrue(coverage.summary(names,member.fact(),evidence).contains("不自动代选"));
    }
    @Test void candidatesOutsideTheBundledSetRetainTheNormalReadOpportunityInsteadOfPrematureCompletion() throws Exception {
        var member=member();var evidence=new ResourceAnswerEvidence();evidence.member(new LocalPokeApiRepository.Detail(member.fact(),List.of("outside-example"),List.of(),"2026-10-23","未内置候选"));var coverage=new OverviewResourceCoverage();
        String request="请查询特性资料。";assertTrue(coverage.pending(request,member.fact(),evidence).isEmpty());assertFalse(coverage.settled(request,member.fact(),evidence));
        coverage.observe(arguments("ability","outside-example"));assertTrue(coverage.settled(request,member.fact(),evidence));assertTrue(coverage.summary(request,member.fact(),evidence).contains("未取得可校验内容"));
    }
}
