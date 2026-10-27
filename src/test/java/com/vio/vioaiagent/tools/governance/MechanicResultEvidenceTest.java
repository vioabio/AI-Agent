package com.vio.vioaiagent.tools.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MechanicResultEvidenceTest {
    Map<String, Object> input() { return Map.of("kind", "ability", "slug", "torrent", "dataReleaseId", "pokeapi-reference-v1", "gameReleaseId", "pokemon-scarlet-violet"); }
    @Test void sourceAndDescriptionIdentityBindActualOutputToThisToolInput() {
        var note = new IndependentMechanicCatalog(new ObjectMapper(), new DefaultResourceLoader()).read("ability", "torrent", "pokeapi-reference-v1", "pokemon-scarlet-violet");
        var evidence = MechanicResultEvidence.capture(ToolCapabilityCatalog.find("lookupPokemonMechanics").orElseThrow(), input(), "digest", note.toString());
        assertEquals("VERIFIED", evidence.get("status")); assertEquals(note.path("projectionSha256").asText(), evidence.get("projectionSha256")); assertEquals("digest", evidence.get("parameterDigest"));
        assertThrows(UnsupportedOperationException.class, () -> evidence.put("new", "text")); assertFalse(evidence.containsKey("descriptionEn"));
    }
    @Test void wrongResourceModifiedProjectionAndRawFailuresNeverBecomeVerifiedEvidence() {
        var c = new IndependentMechanicCatalog(new ObjectMapper(), new DefaultResourceLoader()); var card = ToolCapabilityCatalog.find("lookupPokemonMechanics").orElseThrow();
        assertEquals("INVALID", MechanicResultEvidence.capture(card, input(), "digest", c.read("ability", "snow-cloak", "pokeapi-reference-v1", "pokemon-scarlet-violet").toString()).get("status"));
        String modified = c.read("ability", "torrent", "pokeapi-reference-v1", "pokemon-scarlet-violet").toString().replace("进攻能力乘以1.5", "所有伤害翻倍");
        assertEquals("INVALID", MechanicResultEvidence.capture(card, input(), "digest", modified).get("status"));
        var failed = MechanicResultEvidence.capture(card, input(), "digest", "[tool.error] private-detail"); assertEquals("INVALID", failed.get("status")); assertFalse(failed.toString().contains("private-detail"));
    }
}
