package com.vio.vioaiagent.tools.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.PokemonLearnsetFacts;
import java.util.*;

/** Binds the actual validated callback projection to supplied arguments and one terminal event. */
public final class LearnsetResultEvidence {
    public static final String SCHEMA = "pokemon-learnset-output-evidence-v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private LearnsetResultEvidence() { }
    public static Map<String, Object> capture(ToolCapabilityCard card, Map<String, Object> arguments, String parameterDigest, String output) {
        try {
            if (!"lookupPokemonLearnset".equals(card.name()) || card.isWriteLike() || output == null || output.length() > 20_000) throw new IllegalArgumentException();
            var input = JSON.valueToTree(arguments);
            if (!input.isObject() || input.size() != 4 || !input.path("pokemonId").isIntegralNumber() || !input.path("pokemonId").canConvertToLong()) throw new IllegalArgumentException();
            for (String key : List.of("moveSlug", "dataReleaseId", "gameReleaseId")) if (!input.path(key).isTextual()) throw new IllegalArgumentException();
            var fact = PokemonLearnsetFacts.validate(JSON.readTree(output));
            if (input.path("pokemonId").asLong() != fact.pokemonId() || !input.path("moveSlug").asText().equals(fact.moveSlug())
                    || !input.path("dataReleaseId").asText().equals(fact.dataReleaseId()) || !input.path("gameReleaseId").asText().equals(fact.gameReleaseId())) throw new IllegalArgumentException();
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("schemaVersion", SCHEMA); evidence.put("status", "VERIFIED"); evidence.put("toolVersion", card.version()); evidence.put("parameterDigest", parameterDigest);
            evidence.put("pokemonId", fact.pokemonId()); evidence.put("moveSlug", fact.moveSlug()); evidence.put("versionGroup", fact.versionGroup());
            evidence.put("recordStatus", fact.status()); evidence.put("entryCount", fact.entries().size()); evidence.put("sourceChecksum", fact.sourceChecksum()); evidence.put("projectionChecksum", fact.projectionChecksum());
            return Collections.unmodifiableMap(evidence);
        } catch (Exception invalid) { return Map.of("schemaVersion", SCHEMA, "status", "INVALID", "reason", "UNVERIFIED_OR_UNBOUND_LEARNSET"); }
    }
}
