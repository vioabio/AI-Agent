package com.vio.vioaiagent.pokemon;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.agent.GuideAnswerEvidence;
import com.vio.vioaiagent.pokemon.guides.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GuideProvenanceTest {
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper();
    LocalGuideSearch expanded(){return new LocalGuideSearch(mapper,new DefaultResourceLoader(),"classpath:pokemon-guides/expanded-v1.json",directory.resolve("guides.sqlite").toString());}
    @Test void expandedNotesHaveProvenanceRealMultipleChunksAndUnchangedBaselineContent() throws Exception {
        var search=expanded();assertEquals(12,search.status().documentCount());assertTrue(search.status().chunkCount()>12);
        assertEquals("local-guides-expanded-v1",search.catalog().catalogId());
        var original=mapper.readTree(Files.readString(Path.of("src/main/resources/pokemon-guides/index.json"))).path("documents");
        var expanded=mapper.readTree(Files.readString(Path.of("src/main/resources/pokemon-guides/expanded-v1.json"))).path("documents");
        for(int i=0;i<4;i++)for(String key:List.of("id","title","author","updatedAt","content"))assertEquals(original.get(i).get(key),expanded.get(i).get(key));
        var hits=search.search("物种形态编号","pokemon-scarlet-violet");assertFalse(hits.isEmpty());
        for(var hit:hits){assertNotNull(hit.document().provenance());assertTrue(GuideChunks.validCitation(hit.document(),hit.citation()));}
    }
    @Test void missingOrUnverifiedExternalCopyMetadataCannotBeImportedOrCited() throws Exception {
        var root=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(Files.readString(Path.of("src/main/resources/pokemon-guides/expanded-v1.json")));
        for(String mutation:List.of("missing","kind","usage","url","date","path","sha","review")) {
            var json=root.deepCopy();var doc=(com.fasterxml.jackson.databind.node.ObjectNode)json.path("documents").get(2);
            var p=(com.fasterxml.jackson.databind.node.ObjectNode)doc.path("provenance");
            switch(mutation) {
                case "missing"->doc.remove("provenance");case "kind"->p.put("kind","EXTERNAL_ARTICLE");
                case "usage"->p.put("contentUsage","COPIED");case "review"->p.put("reviewStatus","APPROVED");
                case "url"->((com.fasterxml.jackson.databind.node.ObjectNode)p.path("references").get(0)).put("url","javascript:alert(1)");
                case "date"->p.put("acquiredAt","2999-01-01");
                case "path"->((com.fasterxml.jackson.databind.node.ObjectNode)p.path("codeReferences").get(0)).put("path","../../private.yml");
                case "sha"->((com.fasterxml.jackson.databind.node.ObjectNode)p.path("codeReferences").get(0)).put("sha256","wrong");
            }
            var file=directory.resolve(mutation+".json");Files.writeString(file,json.toString());
            assertThrows(IllegalStateException.class,()->new LocalGuideSearch(mapper,new DefaultResourceLoader(),file.toUri().toString(),directory.resolve("bad.sqlite").toString()),mutation);
        }
    }
    @Test void attributionChangesInvalidateTheCitationEvenWhenOriginalBodyChecksumMatches() throws Exception {
        var hit=expanded().search("伤害计算条件","pokemon-scarlet-violet").get(0);
        var json=mapper.valueToTree(List.of(hit));
        ((com.fasterxml.jackson.databind.node.ObjectNode)json.get(0).path("document").path("provenance")).put("acquiredAt","2026-10-22");
        var evidence=new GuideAnswerEvidence();evidence.observe("searchPokemonGuides",json.toString());
        assertTrue(evidence.verifiedAnswer().contains("没有取得"));
    }
    @Test void legacyDocumentVersionDoesNotChangeMerelyBecauseOptionalProvenanceWasAdded() throws Exception {
        var search=new LocalGuideSearch(mapper,new DefaultResourceLoader(),"classpath:pokemon-guides/index.json",directory.resolve("old.sqlite").toString());
        var doc=search.search("努力值性格","pokemon-scarlet-violet").get(0).document();
        String before=mapper.writeValueAsString(doc);
        assertFalse(before.contains("provenance"));
        var tree=mapper.readTree(before);assertEquals(12,tree.size());
        assertEquals(com.vio.vioaiagent.pokemon.reference.PokeApiClient.digest(before),GuideChunks.documentVersion(doc));
    }
    @Test void genericQuestionFramingDoesNotMakeUnrelatedNotesRelevant() {
        var search=expanded();
        assertTrue(GuideChunks.tokens("如何 什么 哪些 是否 能否 问题 应该 可以").isEmpty());
        assertTrue(search.search("火星种菜完全无关问题","pokemon-scarlet-violet").isEmpty());
        assertFalse(search.search("问题：努力值与性格","pokemon-scarlet-violet").isEmpty());
    }
}
