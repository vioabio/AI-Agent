package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

public final class MachineReferenceFacts {
    public static final String SCHEMA="pokemon-machine-reference-v1",SCOPE="MEMBER_MACHINE_RECORD_AND_PINNED_SV_TM_NOT_ACQUISITION_OR_LEGALITY";
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private MachineReferenceFacts(){ }
    public record Report(String schemaVersion,long pokemonId,String moveSlug,String dataReleaseId,String gameReleaseId,
                         String status,PokemonLearnsetFacts learning,MachineReferenceCatalog.Machine machine,String scope,String reportSha256){ }
    public static Report create(long id,String move,String data,String game,PokemonLearnsetFacts learning){
        PokemonLearnsetFacts.requireRequest(id,move,data,game);var machine=learning!=null&&learning.entries().stream().anyMatch(e->e.methodKey().equals("machine"))?MachineReferenceCatalog.current().find(move):null;
        var r=new Report(SCHEMA,id,move,data,game,status(learning,machine),learning,machine,SCOPE,"");var node=(ObjectNode)JSON.valueToTree(r);node.put("reportSha256",hash(node));return validate(node);
    }
    public static Report validate(JsonNode node){try{
        if(!node.isObject()||node.size()!=10||!SCHEMA.equals(node.path("schemaVersion").asText())||!SCOPE.equals(node.path("scope").asText())||!hash((ObjectNode)node).equals(node.path("reportSha256").asText()))throw new IllegalArgumentException();
        if(!node.path("pokemonId").isIntegralNumber()||!node.path("pokemonId").canConvertToLong())throw new IllegalArgumentException();
        for(String k:List.of("moveSlug","dataReleaseId","gameReleaseId","status","scope","reportSha256"))if(!node.path(k).isTextual())throw new IllegalArgumentException();
        if(!node.path("machine").isNull()&&(!node.path("machine").isObject()||!node.path("machine").path("machineId").isIntegralNumber()))throw new IllegalArgumentException();
        var r=JSON.treeToValue(node,Report.class);PokemonLearnsetFacts.requireRequest(r.pokemonId(),r.moveSlug(),r.dataReleaseId(),r.gameReleaseId());
        if(r.learning()!=null){var l=PokemonLearnsetFacts.validate(node.path("learning"));if(l.pokemonId()!=r.pokemonId()||!l.moveSlug().equals(r.moveSlug())||!l.dataReleaseId().equals(r.dataReleaseId())||!l.gameReleaseId().equals(r.gameReleaseId()))throw new IllegalArgumentException();}
        var expected=r.learning()!=null&&r.learning().entries().stream().anyMatch(e->e.methodKey().equals("machine"))?MachineReferenceCatalog.current().find(r.moveSlug()):null;
        if(!Objects.equals(expected,r.machine())||!status(r.learning(),r.machine()).equals(r.status()))throw new IllegalArgumentException();return r;
    }catch(Exception invalid){throw new IllegalArgumentException("学习器结果未通过成员与来源核验",invalid);}}
    public static Report bound(String output,String arguments){try{
        var a=JSON.readTree(arguments);if(a==null||!a.isObject()||a.size()!=4||!a.path("pokemonId").isIntegralNumber()||!a.path("pokemonId").canConvertToLong())throw new IllegalArgumentException();
        for(String k:List.of("moveSlug","dataReleaseId","gameReleaseId"))if(!a.path(k).isTextual())throw new IllegalArgumentException();
        if(output==null||output.length()>20000)throw new IllegalArgumentException();var r=validate(JSON.readTree(output));if(r.pokemonId()!=a.path("pokemonId").asLong()||!r.moveSlug().equals(a.path("moveSlug").asText())||!r.dataReleaseId().equals(a.path("dataReleaseId").asText())||!r.gameReleaseId().equals(a.path("gameReleaseId").asText()))throw new IllegalArgumentException();return r;
    }catch(Exception invalid){throw new IllegalArgumentException("学习器结果与实际入参不符",invalid);}}
    private static String status(PokemonLearnsetFacts l,MachineReferenceCatalog.Machine m){return l==null?"REFERENCE_UNAVAILABLE":l.entries().stream().noneMatch(e->e.methodKey().equals("machine"))?"MEMBER_MACHINE_NOT_RECORDED":m==null?"NOT_CATALOGUED":"MATCHED";}
    private static String hash(ObjectNode node){var n=node.deepCopy();n.remove("reportSha256");return PokeApiClient.digest(canonical(n).toString());}
    private static JsonNode canonical(JsonNode n){if(n.isObject()){var out=JSON.createObjectNode();var rows=new TreeMap<String,JsonNode>();n.fields().forEachRemaining(e->rows.put(e.getKey(),e.getValue()));rows.forEach((k,v)->out.set(k,canonical(v)));return out;}if(n.isArray()){var out=JSON.createArrayNode();n.forEach(v->out.add(canonical(v)));return out;}return n;}
}
