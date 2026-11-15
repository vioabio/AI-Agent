package com.vio.vioaiagent.pokemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.agent.GuideAnswerEvidence;
import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.guides.*;
import com.vio.vioaiagent.pokemon.reference.LocalPokeApiRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpeciesGuideRetrievalTest {
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper();
    LocalGuideSearch index(){return new LocalGuideSearch(mapper,new DefaultResourceLoader(),"classpath:pokemon-strategy/overview-v1.json",directory.resolve("guides.sqlite").toString());}
    List<LocalGuideSearch.Hit> search(LocalGuideSearch index,String query){return index.search(query,"pokemon-scarlet-violet");}
    @Test void domainNameAloneCannotRetrieveUnrelatedFactsButTopicAndCitationArePreserved() {
        var index = index();
        for (String query : List.of("宝可梦2049无人区时间钥匙在哪里", "Pokémon XYZ999", "Pokemon XYZ999"))
            assertTrue(search(index, query).isEmpty(), query);
        var hits = search(index, "宝可梦努力值培养");
        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().allMatch(hit -> GuideChunks.validCitation(hit.document(), hit.citation())));
        assertEquals(GuideChunks.tokens("努力值培养"), GuideChunks.tokens("宝可梦努力值培养"));
        assertTrue(GuideChunks.tokens("pokemon2 培养").contains("pokemon2"));
    }
    @Test void matchingSpeciesIsPrioritizedAndUnrequestedSaveOrDamageTopicsAreExcluded() {
        var index=index();assertEquals(14,index.status().documentCount());
        for(String species:List.of("雪妖女","杰尼龟")) {
            var hits=search(index,species+"的培养搭配与对战策略");
            assertTrue(hits.getFirst().document().title().startsWith(species));
            for(var hit:hits) {
                assertTrue(GuideChunks.validCitation(hit.document(),hit.citation()));
                assertFalse(hit.document().applicability().topics().contains("damage"));
                assertNotEquals("team-change-confirmation-v1",hit.document().id());
                assertEquals(hit.citation(),index.locate(hit.citation().chunkId()).orElseThrow().citation());
            }
        }
    }
    @Test void otherOrUnspecifiedSpeciesCannotAcquireSpecificNotesAndEnglishFormsAreNotSubstringAliases() {
        var index=index();
        for(String query:List.of("培养配置与努力值","皮卡丘培养搭配","froslass-other 培养","froslass2 培养"))
            assertTrue(search(index,query).stream().allMatch(h->h.document().applicability().subjects().isEmpty()),query);
        assertEquals("froslass-conditional-overview-v1",search(index,"FROSLASS 培养搭配").getFirst().document().id());
        assertTrue(search(index,"杰尼龟培养").stream().noneMatch(h->h.document().id().startsWith("froslass-")));
    }
    @Test void explicitProjectOrDamageQuestionsStillReachTheirOwnSourcesAndOldCorpusStillLoads() {
        var index=index();
        assertTrue(search(index,"本项目队伍保存预览确认流程").stream().anyMatch(h->h.document().id().equals("team-change-confirmation-v1")));
        assertTrue(search(index,"伤害计算天气场地条件").stream().anyMatch(h->h.document().applicability().topics().contains("damage")));
        var legacy=new LocalGuideSearch(mapper,new DefaultResourceLoader(),"classpath:pokemon-guides/expanded-v1.json",directory.resolve("old.sqlite").toString());
        assertEquals(12,legacy.catalog().documentCount());assertFalse(search(legacy,"努力值培养").isEmpty());
    }
    @Test void undeclaredOrInvalidScopeCannotReplaceAValidIndex() throws Exception {
        var index=index();var original=mapper.readTree(Path.of("src/main/resources/pokemon-strategy/overview-v1.json").toFile());
        for(String mutation:List.of("missing","topic","subject")) {
            var root=original.deepCopy();var doc=(ObjectNode)root.path("documents").get(0);
            if(mutation.equals("missing"))doc.remove("applicability");
            else if(mutation.equals("topic"))((ObjectNode)doc.path("applicability")).putArray("topics").add("unknown");
            else ((ObjectNode)doc.path("applicability")).putArray("subjects").add("../private");
            Path file=directory.resolve(mutation+".json");Files.writeString(file,root.toString());
            assertThrows(IllegalStateException.class,()->new LocalGuideSearch(mapper,new DefaultResourceLoader(),file.toUri().toString(),directory.resolve("guides.sqlite").toString()));
            assertFalse(search(index,"雪妖女培养搭配").isEmpty());
        }
    }
    String reference() throws Exception {
        var tree=(ObjectNode)mapper.readTree(Path.of("evaluation/campus/user-reports-v1/fixtures.json").toFile()).path("references").path("froslass").path("fact");
        tree.put("formKey","froslass").put("formNameZh","雪妖女");
        var source=mapper.readTree(Path.of("src/main/resources/pokemon-strategy/sources-v1.json").toFile()).path("references").get(1);
        return mapper.writeValueAsString(new LocalPokeApiRepository.Detail(mapper.treeToValue(tree,PokemonFact.class),
                mapper.convertValue(source.path("availableAbilitySlugs"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}),
                mapper.convertValue(source.path("availableMoveSlugs"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}),"2026-10-23","本地参考"));
    }
    @Test void boundSpeciesNoteIncludesItsFullConditionalConfigurationAndLimitsInsteadOfOnlyOneChunk() throws Exception {
        var hits=search(index(),"雪妖女培养搭配");var evidence=new GuideAnswerEvidence();evidence.begin("请介绍雪妖女并给出培养搭配");
        evidence.observe("lookupPokemonReference",reference());evidence.observe("searchPokemonGuides",mapper.writeValueAsString(hits));
        String answer=evidence.overviewAnswer();
        assertTrue(evidence.overviewReady("请介绍雪妖女并给出培养搭配"));
        assertTrue(answer.contains("特攻努力值252、速度252、HP 4"));assertTrue(answer.contains("策略推断"));
        assertTrue(answer.contains("没有实时使用率、赛事成绩"));assertTrue(answer.contains("待人工复核"));
        assertTrue(answer.contains("原文范围：完整文档"));assertFalse(answer.contains("从AI建议到队伍保存"));
        assertTrue(hits.getFirst().document().content().contains(hits.getFirst().citation().text()));
        assertTrue(hits.getFirst().document().content().length()>hits.getFirst().citation().text().length());
    }
    @Test void alteredApplicabilityCannotReuseAnOldCitationAndNoFactDoesNotUnlockFullSpeciesText() throws Exception {
        var hits=search(index(),"雪妖女培养搭配");var evidence=new GuideAnswerEvidence();evidence.begin("请介绍雪妖女并给出培养搭配");
        evidence.observe("searchPokemonGuides",mapper.writeValueAsString(hits));assertFalse(evidence.verifiedAnswer().contains("原文范围：完整文档"));
        var changed=mapper.valueToTree(hits);((ObjectNode)changed.get(0).path("document").path("applicability")).putArray("subjects").add("squirtle");
        evidence.observe("searchPokemonGuides",changed.toString());assertTrue(evidence.verifiedAnswer().contains("没有取得可核对"));
    }
    @Test void sourceFocusCanBeReadAfterFactsButBothRequestedKindsNeedBoundResultsBeforeEarlyFinish() throws Exception {
        String question="请介绍雪妖女并给出培养搭配。\n请查询当前数据版本下的特性与招式资料。";
        var evidence=new GuideAnswerEvidence();evidence.begin(question);evidence.observe("lookupPokemonReference",reference());
        assertEquals("请介绍雪妖女并给出培养搭配。",evidence.missingSpeciesSourceQuery(question).orElseThrow());
        assertFalse(evidence.overviewReady(question));
        evidence.observe("searchPokemonGuides",mapper.writeValueAsString(search(index(),"雪妖女培养搭配")));
        assertTrue(evidence.missingSpeciesSourceQuery(question).isEmpty());assertFalse(evidence.overviewReady(question));
        evidence.observe("lookupPokemonResource","[tool.error] unavailable");
        assertFalse(evidence.overviewReady(question));
        evidence.observe("lookupPokemonResource","[tool.error] unavailable","{\"kind\":\"ability\",\"slug\":\"snow-cloak\",\"dataReleaseId\":\"pokeapi-reference-v1\"}");
        assertFalse(evidence.overviewReady(question));
        evidence.observe("lookupPokemonResource","[tool.error] unavailable","{\"kind\":\"move\",\"slug\":\"ice-beam\",\"dataReleaseId\":\"pokeapi-reference-v1\"}");
        assertTrue(evidence.overviewReady(question));assertTrue(evidence.overviewAnswer().contains("未取得的资料不作确认"));
        assertTrue(evidence.overviewAnswer().contains("特性：未取得"));assertTrue(evidence.overviewAnswer().contains("招式：未取得"));
    }
}
