package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.reference.PokeApiClient;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Resolve only the same fixed-name/ID semantics as the local reference adapter; no network or model guesses. */
public final class GuideReferenceIdentity {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<String,Long> NAMES=names();
    private GuideReferenceIdentity(){ }
    record Target(Long id,String slug) {
        boolean matches(PokemonFact fact){return id!=null ? (slug!=null ? fact.speciesId()==id && fact.slug().endsWith(slug) : fact.formId()==id) : slug.equals(fact.slug().toLowerCase(Locale.ROOT));}
    }
    static Optional<Target> target(String arguments) {
        if(arguments==null||arguments.length()>2000)return Optional.empty();
        try {
            var root=JSON.readTree(arguments);
            if(!root.isObject()||root.size()!=2||!"pokeapi-reference-v1".equals(root.path("dataReleaseId").asText())||!root.path("query").isTextual())return Optional.empty();
            String query=com.vio.vioaiagent.pokemon.reference.RegionalFormName.normalizeQuery(root.path("query").asText());
            if(query.isBlank()||query.length()>80)return Optional.empty();
            String regional = com.vio.vioaiagent.pokemon.reference.RegionalFormName.alolanSpecies(query);
            if (regional != null) { Long species = NAMES.get(regional); return species == null ? Optional.empty() : Optional.of(new Target(species,"-alola")); }
            if(query.matches("[0-9]+")) {long id=Long.parseLong(query);return id>0&&id<=Integer.MAX_VALUE?Optional.of(new Target(id,null)):Optional.empty();}
            if(query.matches("[a-z0-9][a-z0-9-]{0,79}"))return Optional.of(new Target(null,query));
            Long id=NAMES.get(query);return id==null?Optional.empty():Optional.of(new Target(id,null));
        }catch(Exception invalid){return Optional.empty();}
    }
    private static Map<String,Long> names() {
        try(var data=GuideReferenceIdentity.class.getResourceAsStream("/pokemon-reference/species-names.csv");
            var metadata=GuideReferenceIdentity.class.getResourceAsStream("/pokemon-reference/species-names.meta.json")) {
            if(data==null||metadata==null)return Map.of();
            String csv=new String(data.readNBytes(2_000_001),StandardCharsets.UTF_8);
            var meta=JSON.readTree(metadata.readNBytes(65536));
            if(csv.getBytes(StandardCharsets.UTF_8).length>2_000_000||!PokeApiClient.digest(csv).equals(meta.path("sha256").asText())
                    ||!"https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/pokemon_species_names.csv".equals(meta.path("sourceUrl").asText()))return Map.of();
            Map<String,Long> result=new HashMap<>();Set<String> ambiguous=new HashSet<>();
            for(String line:csv.split("\\R")) {
                String[] columns=line.split(",",4);
                if(columns.length<3||!Set.of("4","12").contains(columns[1]))continue;
                long id=Long.parseLong(columns[0]);String name=columns[2].replace("\"","").toLowerCase(Locale.ROOT);
                Long previous=result.putIfAbsent(name,id);if(previous!=null&&previous!=id)ambiguous.add(name);
            }
            ambiguous.forEach(result::remove);return Map.copyOf(result);
        }catch(Exception invalid){return Map.of();} // Unknown identity conservatively invalidates, never preserves by guess.
    }
}
