package com.vio.vioaiagent.tools.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LearnsetResultEvidenceTest {
    final ObjectMapper json = new ObjectMapper();
    Map<String, Object> args() { return Map.of("pokemonId", 7, "moveSlug", "water-pulse", "dataReleaseId", "pokeapi-reference-v1", "gameReleaseId", "pokemon-scarlet-violet"); }
    PokemonLearnsetFacts fact() { return PokemonLearnsetFacts.create(7, "squirtle", "water-pulse", List.of(new PokemonLearnsetFacts.Entry("level-up", 15, null)),
            new PokeApiClient.Snapshot("fixture", PokeApiClient.digest("fixture"), Instant.parse("2026-10-26T00:00:00Z"), "https://pokeapi.co/api/v2/pokemon/7/")); }
    @Test void actualOutputProjectionAndInputDigestAreBoundWithoutRecordingUnboundedText() throws Exception {
        var card = ToolCapabilityCatalog.find("lookupPokemonLearnset").orElseThrow(); var f = fact();
        var evidence = LearnsetResultEvidence.capture(card, args(), "input-digest", json.writeValueAsString(f));
        assertEquals("VERIFIED", evidence.get("status")); assertEquals(f.projectionChecksum(), evidence.get("projectionChecksum")); assertEquals("input-digest", evidence.get("parameterDigest"));
        assertEquals(1, evidence.get("entryCount")); assertFalse(evidence.containsKey("payload")); assertThrows(UnsupportedOperationException.class, () -> evidence.put("new", "text"));
    }
    @Test void changedFieldsWrongMemberOrInvalidOutputNeverGetVerifiedEvidence() throws Exception {
        var card = ToolCapabilityCatalog.find("lookupPokemonLearnset").orElseThrow();
        var changed = new LinkedHashMap<>(args()); changed.put("pokemonId", 478);
        assertEquals("INVALID", LearnsetResultEvidence.capture(card, changed, "digest", json.writeValueAsString(fact())).get("status"));
        String invalid = json.writeValueAsString(fact()).replace("\"levelLearnedAt\":15", "\"levelLearnedAt\":99");
        assertEquals("INVALID", LearnsetResultEvidence.capture(card, args(), "digest", invalid).get("status"));
        var denied = LearnsetResultEvidence.capture(card, args(), "digest", "[tool.error] private-raw-error"); assertEquals("INVALID", denied.get("status")); assertFalse(denied.toString().contains("private-raw-error"));
    }
}
