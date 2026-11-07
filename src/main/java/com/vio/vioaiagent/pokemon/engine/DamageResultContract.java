package com.vio.vioaiagent.pokemon.engine;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.pokemon.domain.TeamBuild;
import java.security.MessageDigest;
import java.util.*;

/** Checks supported conditions against this invocation; does not reimplement the damage algorithm. */
public final class DamageResultContract {
    public static final String SCHEMA="damage-condition-validation-v1";
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final List<String> STATS=List.of("hp","atk","def","spa","spd","spe");
    private DamageResultContract() { }
    public static ObjectNode verify(LocalDamageEngine.Request supplied,LocalDamageEngine.Request normalized,JsonNode result,
                                    List<String> attackAbilities,List<String> defendAbilities) {
        requireShape(result);
        if(!Objects.equals(normalized.dataReleaseId(),result.path("dataReleaseId").asText()))throw invalid();
        var defaults=new ArrayList<String>();
        member(normalized.attacker(),result.path("assumptions").path("attacker"),attackAbilities,"attacker",defaults);
        member(normalized.defender(),result.path("assumptions").path("defender"),defendAbilities,"defender",defaults);
        if(!sameName(normalized.move(),result.path("assumptions").path("move")))throw invalid();
        var field=normalized.field()==null?new LocalDamageEngine.Field(null,null,null):normalized.field();
        var actual=result.path("assumptions").path("field");
        if(!field.gameType().equals(actual.path("gameType").asText())||!nullable(field.weather(),actual.get("weather"))||!nullable(field.terrain(),actual.get("terrain")))throw invalid();
        var defender=normalized.defender().build();
        int base=result.path("defenderBaseStats").path("hp").asInt(-1);
        if(base<1)throw invalid();
        int hp=base==1?1:(2*base+defender.ivs().hp()+defender.evs().hp()/4)*defender.level()/100+defender.level()+10;
        if(result.path("defenderHp").asInt()!=hp)throw invalid();
        var validation=JSON.createObjectNode().put("schemaVersion",SCHEMA).put("status","VERIFIED")
                .put("scope","SUPPORTED_CONDITIONS_NOT_DAMAGE_FORMULA").put("typedRequestDigest",digest(supplied)).put("validatedProjectionDigest",projectionDigest(result));
        validation.set("engineDefaultAbilitySides",JSON.valueToTree(defaults));return validation;
    }
    public static void requireShape(JsonNode result) {
        if(result==null||!result.isObject()||!"@smogon/calc".equals(result.path("engine").asText())||!"0.12.0".equals(result.path("engineVersion").asText())
                ||!result.path("generation").isIntegralNumber()||result.path("generation").asInt()!=9||!"pokeapi-reference-v1".equals(result.path("dataReleaseId").asText()))throw invalid();
        int min=integer(result.path("range").path("minimum"),0,Integer.MAX_VALUE),max=integer(result.path("range").path("maximum"),0,Integer.MAX_VALUE),hp=integer(result.path("defenderHp"),1,Integer.MAX_VALUE);
        var pmin=result.path("percent").path("minimum");var pmax=result.path("percent").path("maximum");
        if(max<min||!pmin.isNumber()||!pmax.isNumber()||!Double.isFinite(pmin.asDouble()+pmax.asDouble())
                ||Math.abs(pmin.asDouble()-100.0*min/hp)>.01||Math.abs(pmax.asDouble()-100.0*max/hp)>.01)throw invalid();
    }
    private static void member(LocalDamageEngine.Combatant expected,JsonNode actual,List<String> abilities,String side,List<String> defaults) {
        var build=expected.build();
        // The pinned SDK omits undefined item. Only an expected empty item may be made explicit.
        if(build.item().isBlank()&&actual.isObject()&&!actual.has("item"))((ObjectNode)actual).put("item","");
        if(!sameName(expected.species(),actual.path("species"))||integer(actual.path("level"),1,100)!=build.level()
                ||!sameName(build.nature(),actual.path("nature"))||!optionalName(build.item(),actual.path("item")))throw invalid();
        if(!build.ability().isBlank()) {if(!sameName(build.ability(),actual.path("ability")))throw invalid();}
        else {
            if(abilities==null||abilities.stream().noneMatch(name->sameName(name,actual.path("ability"))))throw invalid();
            defaults.add(side);
        }
        stats(build.evs(),actual.path("evs"),252);stats(build.ivs(),actual.path("ivs"),31);
    }
    private static void stats(TeamBuild.Stats expected,JsonNode actual,int max) {
        if(!actual.isObject()||actual.size()!=6)throw invalid();
        var values=JSON.valueToTree(expected);
        for(String stat:STATS)if(integer(actual.path(stat),0,max)!=values.path(stat).asInt())throw invalid();
    }
    private static int integer(JsonNode value,int minimum,int maximum) {
        if(!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()<minimum||value.asInt()>maximum)throw invalid();return value.asInt();
    }
    private static String id(String value){return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");}
    private static boolean sameName(String expected,JsonNode actual) {
        return expected!=null&&!id(expected).isEmpty()&&actual.isTextual()&&actual.asText().length()<=80&&id(expected).equals(id(actual.asText()));
    }
    private static boolean optionalName(String expected,JsonNode actual) {return expected.isBlank()?actual.isTextual()&&actual.asText().isEmpty():sameName(expected,actual);}
    private static boolean nullable(String expected,JsonNode actual){return actual!=null&&(expected==null?actual.isNull():actual.isTextual()&&expected.equals(actual.asText()));}
    public static String digest(Object value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(JSON.convertValue(value,Object.class))));}
        catch(Exception invalid){throw new IllegalStateException("无法核对伤害计算摘要",invalid);}
    }
    public static String projectionDigest(JsonNode result) {
        return digest(Map.of("assumptions",result.path("assumptions"),"range",result.path("range"),"percent",result.path("percent"),"defenderHp",result.path("defenderHp")));
    }
    private static IllegalStateException invalid(){return new IllegalStateException("伤害结果与本次计算条件不一致，已拒绝发布");}
}
