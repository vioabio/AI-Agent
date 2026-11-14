package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class BuildCheckFixture {
    public final ObjectMapper json=new ObjectMapper();
    public final AtomicInteger networkCalls=new AtomicInteger();
    public final PokeApiClient client;
    public final LocalPokeApiRepository repository;
    public final LocalLearnsetReferences learning;
    public final LocalBuildCheckService service;
    public BuildCheckFixture(Path path) { this(path,List.of(new PokemonLearnsetFacts.Entry("machine",0,null))); }
    public BuildCheckFixture(Path path,List<PokemonLearnsetFacts.Entry> entries) {
        var p=json.createObjectNode().put("id",7).put("name","squirtle");p.putObject("species").put("name","squirtle");p.putArray("types").addObject().putObject("type").put("name","water");
        var stats=p.putArray("stats");for(String s:List.of("hp","attack","defense","special-attack","special-defense","speed"))stats.addObject().put("base_stat",40).putObject("stat").put("name",s);
        p.putArray("abilities").addObject().putObject("ability").put("name","torrent");
        var moves=p.putArray("moves");for(String move:List.of("water-pulse","ice-beam","protect","surf")){var m=moves.addObject();m.putObject("move").put("name",move);var details=m.putArray("version_group_details");for(var entry:entries){var row=details.addObject().put("level_learned_at",entry.levelLearnedAt());if(entry.order()==null)row.putNull("order");else row.put("order",entry.order());row.putObject("version_group").put("name","scarlet-violet");row.putObject("move_learn_method").put("name",entry.methodKey());}}
        var s=json.createObjectNode().put("id",7).put("name","squirtle");s.putObject("generation").put("name","generation-i");s.putArray("names").addObject().put("name","杰尼龟").putObject("language").put("name","zh-Hans");
        client=new PokeApiClient(json,path,url->{networkCalls.incrementAndGet();return url.contains("pokemon-species")?s.toString():p.toString();});
        repository=new LocalPokeApiRepository(json,client);learning=new LocalLearnsetReferences(json,client);service=new LocalBuildCheckService(json,repository,learning,client);
    }
    public BuildCheckFacts.Request request(){return new BuildCheckFacts.Request(7,new BuildCheckFacts.Build(List.of("water-pulse","ice-beam","protect","surf"),"torrent","","modest",50,
            new BuildCheckFacts.Stats(0,0,0,252,4,252),new BuildCheckFacts.Stats(31,31,31,31,31,31)),"pokeapi-reference-v1","pokemon-scarlet-violet");}
}
