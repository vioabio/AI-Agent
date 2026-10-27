package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Six offline notes from pinned MIT-licensed community text; upstream TypeScript is never executed. */
@Service @Profile("local & !prod & !demo")
@ConditionalOnProperty(name = "vio.pokemon.reference.enabled", havingValue = "true")
public class IndependentMechanicCatalog {
    private final Map<String, ObjectNode> records;
    public IndependentMechanicCatalog(ObjectMapper json, ResourceLoader loader) {
        try {
            String catalog = read(loader, "catalog-v1.json", 60_000), excerpts = read(loader, "excerpts-v1.json", 30_000), license = read(loader, "LICENSE.showdown", 8_000), generation = read(loader, "generation-v1.txt", 1_000);
            var metadata = json.readTree(read(loader, "metadata-v1.json", 20_000)); var root = json.readTree(catalog); var proof = json.readTree(excerpts);
            if (!"independent-mechanic-metadata-v1".equals(metadata.path("schemaVersion").asText()) || !IndependentMechanicFacts.COMMIT.equals(metadata.path("commit").asText())
                    || !"smogon/pokemon-showdown".equals(metadata.path("repository").asText()) || metadata.path("baseGeneration").asInt() != 9
                    || !"PENDING_HUMAN_REVIEW".equals(metadata.path("reviewStatus").asText())
                    || !PokeApiClient.digest(catalog).equals(metadata.path("catalogSha256").asText()) || !PokeApiClient.digest(excerpts).equals(metadata.path("excerptsSha256").asText())
                    || !PokeApiClient.digest(license).equals(metadata.path("licenseSha256").asText())
                    || !PokeApiClient.digest(generation).equals(metadata.path("generationMarkerSha256").asText()) || !generation.equals("export const Scripts: BattleScriptsData = {\n\tgen: 9,\n};\n")
                    || !"independent-mechanic-catalog-v1".equals(root.path("schemaVersion").asText()) || !root.path("records").isArray() || root.path("records").size() != 6 || metadata.path("recordCount").asInt() != 6
                    || !"independent-mechanic-excerpts-v1".equals(proof.path("schemaVersion").asText()) || !proof.path("excerpts").isArray() || proof.path("excerpts").size() != 6) throw new IllegalArgumentException();
            Map<String, ObjectNode> rows = new HashMap<>(); Set<String> usedProofs = new HashSet<>();
            for (var item : root.path("records")) {
                ObjectNode row = IndependentMechanicFacts.validate(item); String key = row.path("kind").asText() + ":" + row.path("slug").asText();
                var source = metadata.path("sourceFiles").path(row.path("sourcePath").asText());
                if (!source.path("blobSha").equals(row.path("sourceBlobSha")) || !source.path("sha256").equals(row.path("sourceFileSha256")) || rows.putIfAbsent(key, row) != null) throw new IllegalArgumentException();
                List<JsonNode> matching = new ArrayList<>();
                for (var candidate : proof.path("excerpts")) if (candidate.path("kind").equals(row.path("kind")) && candidate.path("slug").equals(row.path("slug"))) matching.add(candidate);
                if (matching.size() != 1 || !usedProofs.add(key)) throw new IllegalArgumentException();
                var p = matching.getFirst();
                if (!p.path("sourcePath").equals(row.path("sourcePath")) || !p.path("fileSha256").equals(row.path("sourceFileSha256")) || !p.path("blobSha").equals(row.path("sourceBlobSha"))
                        || !p.path("line").equals(row.path("sourceLine")) || !("\t\tdesc: " + json.writeValueAsString(row.path("descriptionEn").asText()) + ",").equals(p.path("rawLine").asText())) throw new IllegalArgumentException();
            }
            records = Map.copyOf(rows);
        } catch (Exception invalid) { throw new IllegalStateException("独立机制参考加载失败，不能发布未核对的说明", invalid); }
    }
    private static String read(ResourceLoader loader, String name, int maximum) throws java.io.IOException {
        try (var input = loader.getResource("classpath:pokemon-mechanics-independent/" + name).getInputStream()) {
            byte[] bytes = input.readNBytes(maximum + 1); if (bytes.length > maximum) throw new IllegalArgumentException("独立机制资料过大");
            return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
    public ObjectNode read(String kind, String slug, String release, String game) {
        IndependentMechanicFacts.requireRequest(kind, slug, release, game);
        var value = records.get(kind + ":" + slug);
        if (value == null) throw new com.vio.vioaiagent.pokemon.service.PokemonResourceNotFoundException("尚未收录该资源的独立机制参考，不能从通用短文补造细节");
        return value.deepCopy();
    }
    public static ObjectNode verifyCurrent(JsonNode value) {
        var valid = IndependentMechanicFacts.validate(value);
        if (!valid.equals(Current.CATALOG.read(valid.path("kind").asText(), valid.path("slug").asText(), valid.path("dataReleaseId").asText(), valid.path("gameReleaseId").asText())))
            throw new IllegalArgumentException("机制输出与当前固定来源不符");
        return valid;
    }
    private static final class Current { private static final IndependentMechanicCatalog CATALOG = new IndependentMechanicCatalog(new ObjectMapper(), new DefaultResourceLoader()); }
}
