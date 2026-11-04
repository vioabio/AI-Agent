package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bundled species identities only: no network, battle facts or legality inferred. */
public final class PokemonNameIndex {
    private PokemonNameIndex() { }
    private static final class Holder { static final List<Entry> ENTRIES = load(); }
    public record Entry(int nationalNo, String nameZh, String slug, List<String> aliases) { }
    public static List<Entry> all() { return Holder.ENTRIES; }
    public static List<Entry> search(String query, int limit) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (q.length() > 80) throw new IllegalArgumentException("名称最多80个字符");
        return all().stream().filter(e -> q.isEmpty() || e.slug().contains(q)
                || Integer.toString(e.nationalNo()).equals(q.replaceFirst("^#?0+(?!$)", ""))
                || e.aliases().stream().anyMatch(n -> n.toLowerCase(Locale.ROOT).contains(q)))
                .sorted(Comparator.comparingInt((Entry e) -> e.slug().equals(q) || e.aliases().contains(q) ? 0 : 1)
                        .thenComparingInt(Entry::nationalNo))
                .limit(Math.clamp(limit, 1, 2048)).toList();
    }
    private static List<Entry> load() {
        try {
            String species = readVerified("species", "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/pokemon_species.csv");
            String names = readVerified("species-names", "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/pokemon_species_names.csv");
            Map<Integer, List<String>> aliases = new HashMap<>();
            Map<Integer, String> simplified = new HashMap<>();
            for (String row : names.split("\\R")) {
                String[] c = row.split(",", 4);
                if (c.length < 3 || !c[0].matches("[0-9]+")) continue;
                int id = Integer.parseInt(c[0]);
                String name = c[2].replace("\"", "");
                aliases.computeIfAbsent(id, ignored -> new ArrayList<>()).add(name);
                if ("12".equals(c[1])) simplified.put(id, name);
            }
            List<Entry> result = new ArrayList<>();
            for (String row : species.split("\\R")) {
                String[] c = row.split(",", 4);
                if (c.length < 3 || !c[0].matches("[0-9]+")) continue;
                int id = Integer.parseInt(c[0]);
                if (id > 1025 || !simplified.containsKey(id)) continue;
                result.add(new Entry(id, simplified.get(id), c[1], List.copyOf(aliases.get(id))));
            }
            return List.copyOf(result);
        } catch (Exception error) { throw new IllegalStateException("图鉴名称索引校验失败", error); }
    }
    private static String readVerified(String file, String source) throws Exception {
        try (var data = PokemonNameIndex.class.getResourceAsStream("/pokemon-reference/" + file + ".csv");
             var metadata = PokemonNameIndex.class.getResourceAsStream("/pokemon-reference/" + file + ".meta.json")) {
            if (data == null || metadata == null) throw new IllegalStateException("缺少名称索引");
            String text = new String(data.readNBytes(2_000_001), StandardCharsets.UTF_8);
            var meta = new ObjectMapper().readTree(metadata);
            if (!source.equals(meta.path("sourceUrl").asText()) || !PokeApiClient.digest(text).equals(meta.path("sha256").asText()))
                throw new IllegalStateException("索引来源或摘要不匹配");
            return text;
        }
    }
}
