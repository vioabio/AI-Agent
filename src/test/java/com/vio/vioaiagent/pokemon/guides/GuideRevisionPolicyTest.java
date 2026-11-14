package com.vio.vioaiagent.pokemon.guides;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.agent.GuideAnswerEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GuideRevisionPolicyTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper();
    LocalGuideSearch.Document doc(String id) throws Exception {
        try(var in=getClass().getResourceAsStream("/pokemon-guides/expanded-v1.json")){
            for(var row:json.readTree(in).path("documents"))if(id.equals(row.path("id").asText()))return json.treeToValue(row,LocalGuideSearch.Document.class);
        }throw new IllegalArgumentException(id);
    }
    LocalGuideSearch.Hit hit(LocalGuideSearch.Document doc){
        var part=GuideChunks.split(doc,GuideChunks.documentVersion(doc)).getFirst();
        return new LocalGuideSearch.Hit(doc,20,LocalGuideSearch.checksum(doc),new LocalGuideSearch.Citation(part.id(),part.version(),part.number(),part.start(),part.end(),part.content(),part.hash()));
    }
    @Test void knownUnscopedRevisionsAreExcludedFromCapabilityExplanationWithoutRewritingFrozenContent() throws Exception {
        for(var row:GuideRevisionPolicy.LEGACY_SCOPE.entrySet()){
            var d=doc(row.getKey());assertEquals(row.getValue(),LocalGuideSearch.checksum(d));
            assertFalse(GuideRevisionPolicy.usable(d,"工具报告的伤害条件"));assertTrue(GuideChunks.validCitation(d,hit(d).citation()));
        }
    }
    @Test void genericBridgeAndGeneralReferenceQueriesKeepTheirApplicableLegacySources() throws Exception {
        var d=doc("damage-assumptions-v1");
        assertTrue(GuideRevisionPolicy.usable(d,"calculateBattleDamage 支持哪些条件"));
        assertTrue(GuideRevisionPolicy.usable(d,"伤害区间的一般解释"));
        assertFalse(GuideRevisionPolicy.usable(d,"calculateGymDamage 支持哪些条件"));
        assertFalse(GuideRevisionPolicy.usable(d,"两种工具当前伤害接口的区别"));
    }
    @Test void correctedRevisionOfSameLogicalDocumentIsNotPermanentlyBlacklisted() throws Exception {
        var d=doc("damage-assumptions-v1");
        var revision=new LocalGuideSearch.Document(d.id(),d.title(),d.gameReleaseId(),d.keywords(),d.author(),"2026-11-14",d.sourceUrl(),"用于测试的已修订能力说明。");
        assertTrue(GuideRevisionPolicy.usable(revision,"工具报告的伤害条件"));assertTrue(GuideRevisionPolicy.usable(doc("damage-request-checklist-v1"),"工具报告的伤害条件"));
    }
    @Test void normalRetrievalExcludesRetiredRevisionsAndKeepsCanonicalSources() {
        var search=new LocalGuideSearch(json,new DefaultResourceLoader(),"classpath:pokemon-guides/expanded-v1.json",directory.resolve("guides.sqlite").toString());
        var hits=search.search("伤害计算 假设 双方配置 条件","pokemon-scarlet-violet");
        assertFalse(hits.isEmpty());for(var hit:hits){assertTrue(GuideRevisionPolicy.usable(hit.document(),"伤害计算 假设 双方配置 条件"));assertTrue(GuideChunks.validCitation(hit.document(),hit.citation()));}
    }
    @Test void otherwiseValidToolOutputCannotReintroduceRetiredRevisionsIntoAnswers() throws Exception {
        var old=hit(doc("damage-assumptions-v1"));var current=hit(doc("damage-request-checklist-v1"));
        var evidence=new GuideAnswerEvidence();evidence.begin("检索工具伤害计算条件攻略");
        evidence.observe("searchPokemonGuides",json.writeValueAsString(List.of(old,current)));
        String answer=evidence.verifiedAnswer(false);assertFalse(answer.contains("当前本地桥接只表达"));assertTrue(answer.contains(current.document().title()));
    }
}
