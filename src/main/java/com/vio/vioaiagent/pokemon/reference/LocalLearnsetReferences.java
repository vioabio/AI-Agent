package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.util.*;

/** Reads the existing fixed-origin public cache, retaining only exact Scarlet/Violet learn rows. */
@Service @Profile("local & !prod & !demo")
@ConditionalOnProperty(name = "vio.pokemon.reference.enabled", havingValue = "true")
public class LocalLearnsetReferences {
    private final ObjectMapper mapper;
    private final PokeApiClient client;
    @org.springframework.beans.factory.annotation.Autowired
    public LocalLearnsetReferences(ObjectMapper mapper,
            @Value("${vio.pokemon.reference.path:${user.dir}/.local-data/pokeapi}") String directory,
            @Value("${vio.pokemon.reference.proxy:}") String proxy) {
        this(mapper, PokeApiClient.configured(mapper, Path.of(directory), proxy));
    }
    public LocalLearnsetReferences(ObjectMapper mapper, PokeApiClient client) { this.mapper = mapper; this.client = client; }

    public PokemonLearnsetFacts read(long pokemonId, String moveSlug, String dataReleaseId, String gameReleaseId) {
        PokemonLearnsetFacts.requireRequest(pokemonId, moveSlug, dataReleaseId, gameReleaseId);
        return project(pokemonId, moveSlug, client.read("pokemon", Long.toString(pokemonId)));
    }
    PokemonLearnsetFacts project(long id, String move, PokeApiClient.Snapshot raw) {
        try {
            var root = mapper.readTree(raw.payload());
            if (!raw.sha256().equals(PokeApiClient.digest(raw.payload())) || !root.path("id").isIntegralNumber()
                    || !root.path("id").canConvertToLong() || root.path("id").asLong() != id
                    || !root.path("name").isTextual() || !PokemonLearnsetFacts.slug(root.path("name").asText())
                    || !root.path("moves").isArray() || root.path("moves").size() > 2000) throw new IllegalArgumentException();
            List<PokemonLearnsetFacts.Entry> entries = new ArrayList<>();
            boolean matched = false;
            for (var candidate : root.path("moves")) {
                if (!candidate.path("move").path("name").isTextual() || !PokemonLearnsetFacts.slug(candidate.path("move").path("name").asText())) throw new IllegalArgumentException();
                if (!move.equals(candidate.path("move").path("name").asText())) continue;
                if (matched || !candidate.path("version_group_details").isArray() || candidate.path("version_group_details").size() > 200) throw new IllegalArgumentException();
                matched = true;
                for (var row : candidate.path("version_group_details")) {
                    if (!row.path("version_group").path("name").isTextual()) throw new IllegalArgumentException();
                    if (!PokemonLearnsetFacts.VERSION_GROUP.equals(row.path("version_group").path("name").asText())) continue;
                    String method = row.path("move_learn_method").path("name").asText();
                    if (!row.path("move_learn_method").path("name").isTextual() || !PokemonLearnsetFacts.slug(method)
                            || !PokemonLearnsetFacts.integer(row.path("level_learned_at"), 100)
                            || row.has("order") && !row.path("order").isNull() && !PokemonLearnsetFacts.integer(row.path("order"), 1000)) throw new IllegalArgumentException();
                    var entry = new PokemonLearnsetFacts.Entry(method, row.path("level_learned_at").asInt(), row.hasNonNull("order") ? row.path("order").asInt() : null);
                    if (!entries.contains(entry)) entries.add(entry);
                    if (entries.size() > 32) throw new IllegalArgumentException();
                }
            }
            entries.sort(Comparator.comparing(PokemonLearnsetFacts.Entry::methodKey).thenComparingInt(PokemonLearnsetFacts.Entry::levelLearnedAt)
                    .thenComparing(PokemonLearnsetFacts.Entry::order, Comparator.nullsFirst(Comparator.naturalOrder())));
            return PokemonLearnsetFacts.create(id, root.path("name").asText(), move, entries, raw);
        } catch (Exception invalid) { throw new IllegalStateException("学习途径来源不完整或校验失败，不能确认学习方式", invalid); }
    }
}
