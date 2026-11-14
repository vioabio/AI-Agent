package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.vio.vioaiagent.pokemon.engine.DamageResultContract;
import java.util.List;
import java.util.Set;

/** Public provenance selected from the verified calculator callback, never model-authored URLs. */
public record EvidenceAnswerBlock(String schemaVersion, String kind, String targetBlockId,
        String requestDigest, String projectionDigest, String engineVersion, String scope, List<Source> sources) {
    public record Source(String role, String url, String checksum) {
        public Source {
            if (!Set.of("attacker", "defender").contains(role) || url == null
                    || !url.matches("https://pokeapi\\.co/api/v2/pokemon/[1-9][0-9]{0,5}/") || !digest(checksum))
                throw new IllegalArgumentException("Invalid public damage source");
        }
    }
    public EvidenceAnswerBlock {
        if (!"answer-block-v1".equals(schemaVersion) || !"evidence".equals(kind) || targetBlockId == null
                || !targetBlockId.matches("damage-[a-f0-9]{64}") || !digest(requestDigest) || !digest(projectionDigest)
                || !"0.12.0".equals(engineVersion) || !"PUBLIC_REFERENCE_AND_LOCAL_ENGINE_NOT_LEGALITY".equals(scope)
                || sources == null || sources.size() != 2 || sources.stream().anyMatch(s -> s == null)
                || sources.stream().map(Source::role).distinct().count() != 2)
            throw new IllegalArgumentException("Invalid damage evidence block");
        sources = List.copyOf(sources);
        if (!targetBlockId.equals("damage-" + DamageResultContract.digest(List.of(requestDigest.substring(7), projectionDigest.substring(7)))))
            throw new IllegalArgumentException("Unbound damage evidence digests");
    }
    public static EvidenceAnswerBlock from(String target, String request, String projection, JsonNode result) {
        return new EvidenceAnswerBlock("answer-block-v1", "evidence", target, "sha256:" + request,
                "sha256:" + projection, result.path("engineVersion").asText(),
                "PUBLIC_REFERENCE_AND_LOCAL_ENGINE_NOT_LEGALITY", List.of(
                    new Source("attacker", result.path("attackerSource").asText(), result.path("attackerChecksum").asText()),
                    new Source("defender", result.path("defenderSource").asText(), result.path("defenderChecksum").asText())));
    }
    private static boolean digest(String value) { return value != null && value.matches("sha256:[a-f0-9]{64}"); }
}
