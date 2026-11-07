package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.util.*;

/** Read-only supplied-build diagnostics. Drafts and confirmed-write workflow are not mutated. */
@Service @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.pokemon.reference.enabled",havingValue="true")
public class LocalBuildCheckService {
    private final ObjectMapper json;
    private final LocalPokeApiRepository reference;
    private final LocalLearnsetReferences learning;
    private final PokeApiClient client;
    @org.springframework.beans.factory.annotation.Autowired
    public LocalBuildCheckService(ObjectMapper json,LocalPokeApiRepository reference,LocalLearnsetReferences learning,
            @Value("${vio.pokemon.reference.path:${user.dir}/.local-data/pokeapi}") String path,@Value("${vio.pokemon.reference.proxy:}") String proxy) {
        this(json,reference,learning,PokeApiClient.configured(json,Path.of(path),proxy));
    }
    public LocalBuildCheckService(ObjectMapper json,LocalPokeApiRepository reference,LocalLearnsetReferences learning,PokeApiClient client) { this.json=json;this.reference=reference;this.learning=learning;this.client=client; }
    public BuildCheckFacts.Report check(BuildCheckFacts.Request request) {
        BuildCheckFacts.requireRequest(request); String syntax=BuildCheckFacts.syntax(request.build()); var missing=BuildCheckFacts.missing(request.build());
        BuildCheckFacts.Source source=null; List<BuildCheckFacts.Move> moves=new ArrayList<>();
        if (!syntax.equals("INVALID_INPUT")) {
            interrupted();
            try {
                var detail=reference.detail(Long.toString(request.pokemonId()),request.dataReleaseId());
                var raw=client.read("pokemon",Long.toString(request.pokemonId()));var pokemon=json.readTree(raw.payload());
                var species=client.read("pokemon-species",pokemon.path("species").path("name").asText());
                if (detail.fact().formId()!=request.pokemonId() || !detail.fact().slug().equals(pokemon.path("name").asText()) || !detail.fact().gameReleaseId().equals(request.gameReleaseId())
                        || detail.abilities()==null||detail.abilities().size()>8||detail.abilities().stream().anyMatch(s->s==null||!s.matches("[a-z0-9][a-z0-9-]{0,79}"))
                        || !PokeApiClient.digest(raw.sha256()+species.sha256()).equals(detail.fact().checksum())) throw new IllegalStateException("参考快照发生变化");
                source=new BuildCheckFacts.Source(request.pokemonId(),detail.fact().slug(),detail.fact().speciesNameZh(),raw.url(),detail.fact().checksum(),raw.sha256(),species.sha256(),List.copyOf(detail.abilities()));
            } catch (Exception unavailable) { interrupted(); }
            var selected=request.build()==null||request.build().moves()==null?List.<String>of():request.build().moves();
            for (int i=0;i<selected.size();i++) {
                interrupted();String supplied=selected.get(i),key=BuildCheckFacts.normalize(supplied);PokemonLearnsetFacts read=null;
                if (source!=null && !key.isEmpty()) try {
                    var candidate=learning.read(request.pokemonId(),key,request.dataReleaseId(),request.gameReleaseId());
                    if (candidate.sourceChecksum().equals(source.pokemonChecksum()) && candidate.pokemonSlug().equals(source.slug())) read=candidate;
                } catch (RuntimeException unavailable) { interrupted(); }
                moves.add(new BuildCheckFacts.Move(i+1,supplied,key,BuildCheckFacts.moveStatus(key,source,read),read));
            }
        }
        String ability=BuildCheckFacts.abilityStatus(request.build(),source);
        return BuildCheckFacts.seal(new BuildCheckFacts.Report(BuildCheckFacts.SCHEMA,request,syntax,missing,
                BuildCheckFacts.outcome(syntax,missing,source,ability,moves),source,ability,List.copyOf(moves),BuildCheckFacts.SCOPE,""));
    }
    private static void interrupted() { if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("配招核对已取消"); }
}
