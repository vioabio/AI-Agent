package com.vio.vioaiagent.tools.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.agent.MechanicAnswerEvidence;
import com.vio.vioaiagent.pokemon.reference.IndependentMechanicCatalog;
import java.util.*;

/** Content identity of actual, argument-bound pinned community notes, not a human fact verdict. */
public final class MechanicResultEvidence {
    public static final String SCHEMA = "independent-mechanic-output-evidence-v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private MechanicResultEvidence() { }
    public static Map<String, Object> capture(ToolCapabilityCard card, Map<String, Object> arguments, String parameterDigest, String output) {
        try {
            if (!"lookupPokemonMechanics".equals(card.name()) || card.isWriteLike() || output == null || output.length() > 20_000) throw new IllegalArgumentException();
            var input = JSON.valueToTree(arguments); MechanicAnswerEvidence.validateInput(input);
            var note = IndependentMechanicCatalog.verifyCurrent(JSON.readTree(output));
            for (String field : List.of("kind", "slug", "dataReleaseId", "gameReleaseId")) if (!input.path(field).equals(note.path(field))) throw new IllegalArgumentException();
            var result = new LinkedHashMap<String, Object>(); result.put("schemaVersion", SCHEMA); result.put("status", "VERIFIED");
            result.put("toolVersion", card.version()); result.put("parameterDigest", parameterDigest);
            for (String field : List.of("kind", "slug", "sourceCommit", "sourceFileSha256", "descriptionSha256", "projectionSha256")) result.put(field, note.path(field).asText());
            return Collections.unmodifiableMap(result);
        } catch (Exception invalid) { return Map.of("schemaVersion", SCHEMA, "status", "INVALID", "reason", "UNVERIFIED_OR_UNBOUND_MECHANIC_NOTE"); }
    }
}
