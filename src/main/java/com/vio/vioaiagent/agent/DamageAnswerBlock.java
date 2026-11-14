package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.vio.vioaiagent.pokemon.engine.*;
import java.util.*;

/** Numbers and resolved conditions from the trusted calculation callback, never model-authored prose. */
public record DamageAnswerBlock(String schemaVersion,String blockId,String kind,Member attacker,Member defender,String move,
        Field field,int minimum,int maximum,int defenderHp,double minimumPercent,double maximumPercent,
        String gameReleaseId,String dataReleaseId,String evidenceDigest,EvidenceAnswerBlock evidence) {
    public record Member(String species,int level,String nature,String ability,String item,List<Integer> evs,List<Integer> ivs){
        public Member {
            if(!text(species)||!text(nature)||!text(ability)||item==null||item.length()>80||level<1||level>100||!stats(evs,252)||!stats(ivs,31)||evs.stream().mapToInt(Integer::intValue).sum()>510)throw new IllegalArgumentException("Invalid damage member");
            evs=List.copyOf(evs);ivs=List.copyOf(ivs);
        }
    }
    public record Field(String gameType,String weather,String terrain){
        public Field {if(!Set.of("Singles","Doubles").contains(gameType))throw new IllegalArgumentException("Invalid damage field");var validated=new LocalDamageEngine.Field(gameType,weather,terrain);weather=validated.weather();terrain=validated.terrain();}
    }
    public DamageAnswerBlock {
        if(evidence!=null&&(!evidence.targetBlockId().equals(blockId)||!evidence.projectionDigest().equals(evidenceDigest)))throw new IllegalArgumentException("Unbound damage evidence");
        if(!"answer-block-v1".equals(schemaVersion)||!"damageResult".equals(kind)||blockId==null||!blockId.matches("damage-[a-f0-9]{64}")||attacker==null||defender==null||field==null||!text(move)
                ||minimum<0||maximum<minimum||defenderHp<1||!Double.isFinite(minimumPercent+maximumPercent)||Math.abs(minimumPercent-100.0*minimum/defenderHp)>.01||Math.abs(maximumPercent-100.0*maximum/defenderHp)>.01
                ||!"pokemon-scarlet-violet".equals(gameReleaseId)||!"pokeapi-reference-v1".equals(dataReleaseId)||evidenceDigest==null||!evidenceDigest.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid damage block");
    }
    public static DamageAnswerBlock from(LocalDamageEngine.Request request,JsonNode output){
        DamageResultContract.requireShape(output);var proof=output.path("conditionValidation");
        String projection=DamageResultContract.projectionDigest(output);
        if(request==null||request.generation()!=9||!"pokeapi-reference-v1".equals(request.dataReleaseId())||!DamageResultContract.SCHEMA.equals(proof.path("schemaVersion").asText())||!"VERIFIED".equals(proof.path("status").asText())
                ||!DamageResultContract.digest(request).equals(proof.path("typedRequestDigest").asText())||!projection.equals(proof.path("validatedProjectionDigest").asText()))throw new IllegalArgumentException("Unbound damage block");
        var assumptions=output.path("assumptions");var field=assumptions.path("field");
        String blockId="damage-"+DamageResultContract.digest(List.of(proof.path("typedRequestDigest").asText(),projection));
        EvidenceAnswerBlock evidence;
        try { evidence=EvidenceAnswerBlock.from(blockId,proof.path("typedRequestDigest").asText(),projection,output); }
        catch(IllegalArgumentException missingOrInvalidSource) { evidence=null; }
        return new DamageAnswerBlock("answer-block-v1",blockId,"damageResult",
                member(assumptions.path("attacker")),member(assumptions.path("defender")),assumptions.path("move").asText(),new Field(field.path("gameType").asText(),nullable(field.get("weather")),nullable(field.get("terrain"))),
                output.path("range").path("minimum").asInt(),output.path("range").path("maximum").asInt(),output.path("defenderHp").asInt(),output.path("percent").path("minimum").asDouble(),output.path("percent").path("maximum").asDouble(),
                "pokemon-scarlet-violet",request.dataReleaseId(),"sha256:"+projection,evidence);
    }
    private static Member member(JsonNode n){return new Member(n.path("species").asText(),n.path("level").asInt(),n.path("nature").asText(),n.path("ability").asText(),n.path("item").asText(""),values(n.path("evs")),values(n.path("ivs")));}
    private static List<Integer> values(JsonNode stats){var result=new ArrayList<Integer>();for(String key:List.of("hp","atk","def","spa","spd","spe")){var value=stats.path(key);if(!value.isIntegralNumber()||!value.canConvertToInt())throw new IllegalArgumentException("Invalid damage stats");result.add(value.asInt());}return result;}
    private static String nullable(JsonNode n){if(n==null||n.isNull())return null;if(!n.isTextual())throw new IllegalArgumentException("Invalid damage condition");return n.asText();}
    private static boolean text(String n){return n!=null&&!n.isBlank()&&n.length()<=80;}
    private static boolean stats(List<Integer> n,int maximum){return n!=null&&n.size()==6&&n.stream().allMatch(v->v!=null&&v>=0&&v<=maximum);}
}
