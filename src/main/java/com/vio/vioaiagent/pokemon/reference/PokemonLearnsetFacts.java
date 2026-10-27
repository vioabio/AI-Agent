package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;

/** A bounded upstream version-group observation; absence is never a legality verdict. */
public record PokemonLearnsetFacts(String schemaVersion, long pokemonId, String pokemonSlug, String moveSlug,
        String dataReleaseId, String gameReleaseId, String versionGroup, String status, List<Entry> entries,
        String sourceUrl, String sourceChecksum, String retrievedAt, String projectionChecksum) {
    public static final String SCHEMA = "pokemon-learnset-facts-v1";
    public static final String VERSION_GROUP = "scarlet-violet";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of("schemaVersion", "pokemonId", "pokemonSlug", "moveSlug",
            "dataReleaseId", "gameReleaseId", "versionGroup", "status", "entries", "sourceUrl", "sourceChecksum",
            "retrievedAt", "projectionChecksum");
    public record Entry(String methodKey, int levelLearnedAt, Integer order) { }

    public static void requireRequest(long pokemonId, String moveSlug, String dataReleaseId, String gameReleaseId) {
        if (pokemonId < 1 || pokemonId > 100_000 || !slug(moveSlug)
                || !LocalPokeApiRepository.RELEASE.equals(dataReleaseId) || !"pokemon-scarlet-violet".equals(gameReleaseId))
            throw new IllegalArgumentException("学习途径仅支持有效成员编号、招式英文标识及当前朱紫参考版本");
    }

    public static PokemonLearnsetFacts create(long id, String slug, String move, List<Entry> entries, PokeApiClient.Snapshot raw) {
        var fact = new PokemonLearnsetFacts(SCHEMA, id, slug, move, LocalPokeApiRepository.RELEASE,
                "pokemon-scarlet-violet", VERSION_GROUP, entries.isEmpty() ? "NOT_RECORDED_IN_SNAPSHOT" : "RECORDED",
                List.copyOf(entries), raw.url(), raw.sha256(), raw.retrievedAt().toString(), "");
        ObjectNode node = JSON.valueToTree(fact);
        node.put("projectionChecksum", checksum(node));
        return validate(node);
    }

    public static PokemonLearnsetFacts validate(JsonNode node) {
        try {
            if (!node.isObject() || node.size() != FIELDS.size()) throw new IllegalArgumentException();
            for (String key : FIELDS) if (!node.has(key)) throw new IllegalArgumentException();
            for (String key : FIELDS) if (!Set.of("pokemonId", "entries").contains(key) && !node.path(key).isTextual()) throw new IllegalArgumentException();
            if (!node.path("pokemonId").isIntegralNumber() || !node.path("pokemonId").canConvertToLong()) throw new IllegalArgumentException();
            requireRequest(node.path("pokemonId").asLong(), node.path("moveSlug").asText(), node.path("dataReleaseId").asText(), node.path("gameReleaseId").asText());
            if (!SCHEMA.equals(node.path("schemaVersion").asText()) || !VERSION_GROUP.equals(node.path("versionGroup").asText())
                    || !slug(node.path("pokemonSlug").asText()) || !node.path("sourceChecksum").asText().matches("sha256:[a-f0-9]{64}")) throw new IllegalArgumentException();
            String source = node.path("sourceUrl").asText();
            if (!source.equals("https://pokeapi.co/api/v2/pokemon/" + node.path("pokemonId").asLong() + "/")
                    && !source.equals("https://pokeapi.co/api/v2/pokemon/" + node.path("pokemonSlug").asText() + "/")) throw new IllegalArgumentException();
            Instant.parse(node.path("retrievedAt").asText());
            JsonNode rows = node.path("entries");
            if (!rows.isArray() || rows.size() > 32) throw new IllegalArgumentException();
            Set<Entry> unique = new HashSet<>();
            for (var row : rows) {
                if (!row.isObject() || row.size() != 3 || !row.path("methodKey").isTextual() || !slug(row.path("methodKey").asText())
                        || !integer(row.path("levelLearnedAt"), 100) || !row.has("order")
                        || !(row.path("order").isNull() || integer(row.path("order"), 1000))) throw new IllegalArgumentException();
                if (!unique.add(JSON.treeToValue(row, Entry.class))) throw new IllegalArgumentException();
            }
            if (!(rows.isEmpty() ? "NOT_RECORDED_IN_SNAPSHOT" : "RECORDED").equals(node.path("status").asText())
                    || !checksum((ObjectNode) node).equals(node.path("projectionChecksum").asText())) throw new IllegalArgumentException();
            var fact = JSON.treeToValue(node, PokemonLearnsetFacts.class);
            return new PokemonLearnsetFacts(fact.schemaVersion(), fact.pokemonId(), fact.pokemonSlug(), fact.moveSlug(), fact.dataReleaseId(),
                    fact.gameReleaseId(), fact.versionGroup(), fact.status(), List.copyOf(fact.entries()), fact.sourceUrl(), fact.sourceChecksum(), fact.retrievedAt(), fact.projectionChecksum());
        } catch (Exception invalid) { throw new IllegalArgumentException("学习途径资料校验失败", invalid); }
    }

    private static String checksum(ObjectNode node) {
        ObjectNode canonical = JSON.createObjectNode();
        FIELDS.stream().filter(k -> !k.equals("projectionChecksum")).sorted().forEach(k -> canonical.set(k, node.get(k)));
        // Entry field order is fixed too, so a JSON object's key order does not affect identity.
        var rows = canonical.putArray("entries");
        for (var row : node.path("entries")) rows.addObject().put("methodKey", row.path("methodKey").asText())
                .set("levelLearnedAt", row.get("levelLearnedAt"));
        for (int i = 0; i < rows.size(); i++) ((ObjectNode) rows.get(i)).set("order", node.path("entries").get(i).get("order"));
        return PokeApiClient.digest(canonical.toString());
    }
    static boolean integer(JsonNode value, int maximum) { return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0 && value.asInt() <= maximum; }
    static boolean slug(String value) { return value != null && value.matches("[a-z0-9][a-z0-9-]{0,79}"); }
}
