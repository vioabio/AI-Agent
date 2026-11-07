package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Fixed community text projection; integrity checks do not constitute an official or human review. */
public final class IndependentMechanicFacts {
    public static final String SCHEMA = "independent-mechanic-facts-v1";
    public static final String COMMIT = "8f9ab4949d0bf64d5a5ac985a298d1313f2b9a29";
    public static final String SCOPE = "PINNED_COMMUNITY_GEN9_TEXT_NOT_OFFICIAL_GAME_OR_LEGALITY";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of("schemaVersion", "kind", "slug", "nameZh", "dataReleaseId", "gameReleaseId", "generation",
            "sourceRepository", "sourceCommit", "sourcePath", "sourceUrl", "sourceBlobSha", "sourceFileSha256", "sourceLine", "descriptionEn",
            "descriptionSha256", "explanationZh", "reviewStatus", "scope", "projectionSha256");
    private IndependentMechanicFacts() { }
    public static void requireRequest(String kind, String slug, String release, String game) {
        if (!Set.of("ability", "move").contains(kind == null ? "" : kind) || !PokemonLearnsetFacts.slug(slug)
                || !LocalPokeApiRepository.RELEASE.equals(release) || !"pokemon-scarlet-violet".equals(game))
            throw new IllegalArgumentException("详细机制仅支持招式/特性英文标识及当前朱紫参考范围");
    }
    public static ObjectNode validate(JsonNode value) {
        if (!value.isObject() || value.size() != FIELDS.size()) throw new IllegalArgumentException("独立机制字段不完整");
        for (String field : FIELDS) {
            if (Set.of("generation", "sourceLine").contains(field)) {
                if (!value.path(field).isIntegralNumber() || !value.path(field).canConvertToInt()) throw new IllegalArgumentException("独立机制数值无效");
            } else if (!value.path(field).isTextual() || value.path(field).asText().isBlank() || value.path(field).asText().length() > 2000
                    || value.path(field).asText().contains("\u0000")) throw new IllegalArgumentException("独立机制文字无效");
        }
        requireRequest(value.path("kind").asText(), value.path("slug").asText(), value.path("dataReleaseId").asText(), value.path("gameReleaseId").asText());
        String file = "ability".equals(value.path("kind").asText()) ? "data/text/abilities.ts" : "data/text/moves.ts";
        if (!SCHEMA.equals(value.path("schemaVersion").asText()) || value.path("generation").asInt() != 9 || value.path("sourceLine").asInt() < 1 || value.path("sourceLine").asInt() > 100_000
                || !"smogon/pokemon-showdown".equals(value.path("sourceRepository").asText()) || !COMMIT.equals(value.path("sourceCommit").asText()) || !file.equals(value.path("sourcePath").asText())
                || !("https://github.com/smogon/pokemon-showdown/blob/" + COMMIT + "/" + file + "#L" + value.path("sourceLine").asInt()).equals(value.path("sourceUrl").asText())
                || !value.path("sourceBlobSha").asText().matches("[a-f0-9]{40}") || !value.path("sourceFileSha256").asText().matches("sha256:[a-f0-9]{64}")
                || !PokeApiClient.digest(value.path("descriptionEn").asText()).equals(value.path("descriptionSha256").asText())
                || !"PROJECT_PARAPHRASE_PENDING_HUMAN_REVIEW".equals(value.path("reviewStatus").asText()) || !SCOPE.equals(value.path("scope").asText()))
            throw new IllegalArgumentException("独立机制来源或范围不符");
        var sorted = new TreeMap<String, JsonNode>(); value.fields().forEachRemaining(e -> { if (!e.getKey().equals("projectionSha256")) sorted.put(e.getKey(), e.getValue()); });
        try { if (!PokeApiClient.digest(JSON.writeValueAsString(sorted)).equals(value.path("projectionSha256").asText())) throw new IllegalArgumentException("独立机制摘要不符"); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException("独立机制无法编码", invalid); }
        return ((ObjectNode) value).deepCopy();
    }
}
