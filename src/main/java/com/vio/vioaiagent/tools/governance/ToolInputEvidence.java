package com.vio.vioaiagent.tools.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Bounded evidence of supplied arguments, not resolved defaults, output correctness or authorization. */
public final class ToolInputEvidence {
    public static final String SCHEMA="tool-input-evidence-v1";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> TYPES=Set.of("一般","火","水","电","草","冰","格斗","毒","地面","飞行","超能力","虫","岩石","幽灵","龙","恶","钢","妖精");
    private record Rule(Map<String,Rule> properties,Rule element,int bound,Set<String> values,boolean hash) { }
    private static Rule object(Object... pairs) {
        Map<String,Rule> fields=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2)fields.put((String)pairs[i],(Rule)pairs[i+1]);
        return new Rule(Map.copyOf(fields),null,0,null,false);
    }
    private static Rule text(int length){return new Rule(null,null,length,null,true);}
    private static Rule number(int maximum){return new Rule(null,null,maximum,null,false);}
    private static Rule choices(String... values){return new Rule(null,null,0,Set.of(values),false);}
    private static Rule array(Rule element,int maximum){return new Rule(null,element,maximum,null,false);}
    private static final Rule TYPE=new Rule(null,null,0,TYPES,false);
    private static final Rule RELEASE=choices("pokeapi-reference-v1");
    private static final Rule EV=stats(252),IV=stats(31);
    private static Rule stats(int max){var n=number(max);return object("hp",n,"atk",n,"def",n,"spa",n,"spd",n,"spe",n);}
    private static final Rule BUILD=object("moves",array(text(80),4),"ability",text(80),"item",text(80),"nature",text(80),"level",number(100),"evs",EV,"ivs",IV);
    private static final Rule COMBATANT=object("species",text(80),"build",BUILD);
    private static final Map<String,Rule> RULES=Map.ofEntries(
            Map.entry("checkTypeEffectiveness",object("attackType",TYPE,"defendType",TYPE)),
            Map.entry("getTypeChart",object("attackType",TYPE)),
            Map.entry("lookupPokemonReference",object("query",text(80),"dataReleaseId",RELEASE)),
            Map.entry("lookupPokemonResource",object("kind",choices("move","ability","item","nature"),"slug",text(80),"dataReleaseId",RELEASE)),
            Map.entry("lookupPokemonLearnset",object("pokemonId",number(100_000),"moveSlug",text(80),"dataReleaseId",RELEASE,"gameReleaseId",choices("pokemon-scarlet-violet"))),
            Map.entry("lookupPokemonMachine",object("pokemonId",number(100_000),"moveSlug",text(80),"dataReleaseId",RELEASE,"gameReleaseId",choices("pokemon-scarlet-violet"))),
            Map.entry("lookupPokemonMechanics",object("kind",choices("move","ability"),"slug",text(80),"dataReleaseId",RELEASE,"gameReleaseId",choices("pokemon-scarlet-violet"))),
            Map.entry("checkPokemonBuild",object("request",object("pokemonId",number(100_000),"build",BUILD,"dataReleaseId",RELEASE,"gameReleaseId",choices("pokemon-scarlet-violet")))),
            Map.entry("checkPokemonTeamBuild",object("request",object("members",array(object("slot",number(6),"pokemonId",number(100_000),"build",BUILD),6),"dataReleaseId",RELEASE,"gameReleaseId",choices("pokemon-scarlet-violet")))),
            Map.entry("lookupPokemonTeamReference",object("queries",array(text(80),6),"dataReleaseId",RELEASE)),
            Map.entry("searchPokemonGuides",object("query",text(800),"gameReleaseId",choices("pokemon-scarlet-violet"))),
            Map.entry("readCurrentTeam",object("teamId",number(Integer.MAX_VALUE))),
            Map.entry("listCurrentTeams",object()),
            Map.entry("calculateBattleDamage",object("request",object("generation",number(9),"dataReleaseId",RELEASE,"attacker",COMBATANT,"defender",COMBATANT,"move",text(80),
                    "field",object("gameType",choices("Singles","Doubles"),"weather",choices("Sun","Rain","Sand","Hail","Snow","Harsh Sunshine","Heavy Rain","Strong Winds"),
                            "terrain",choices("Electric","Grassy","Misty","Psychic"))))));
    private ToolInputEvidence() { }
    public static Set<String> supportedTools(){return RULES.keySet();}

    public static Map<String,Object> capture(ToolCapabilityCard card,Map<String,Object> arguments,String parameterDigest) {
        var rule=RULES.get(card.name());
        if(rule==null || card.riskLevel()!=ToolCapabilityCard.RiskLevel.LOW || card.isWriteLike())
            return Map.of("schemaVersion",SCHEMA,"coverage","OMITTED","fields",Map.of());
        Map<String,Object> fields=new LinkedHashMap<>();
        boolean complete=project(arguments,rule,"",fields);
        var result=new LinkedHashMap<String,Object>(Map.of("schemaVersion",SCHEMA,"coverage",complete?"COMPLETE":"PARTIAL","toolVersion",card.version(),
                "parameterDigest",parameterDigest,"fields",Collections.unmodifiableMap(fields),"scope","SUPPLIED_ARGUMENTS_ONLY"));
        if("calculateBattleDamage".equals(card.name()))try {
            var typed=JSON.convertValue(arguments.get("request"),com.vio.vioaiagent.pokemon.engine.LocalDamageEngine.Request.class);
            if(typed!=null)result.put("typedRequestDigest",com.vio.vioaiagent.pokemon.engine.DamageResultContract.digest(typed));
        }catch(IllegalArgumentException invalid){ /* Invalid DTO input is never promoted to resolved output evidence. */ }
        return Collections.unmodifiableMap(result);
    }
    private static boolean project(Object value,Rule rule,String path,Map<String,Object> fields) {
        if(fields.size()>=96)return false;
        if(value==null){fields.put(path,literal(null));return true;}
        if(rule.properties()!=null) {
            if(!(value instanceof Map<?,?> map) || map.size()>24)return false;
            boolean complete=true;
            for(var entry:map.entrySet()) {
                Rule child=rule.properties().get(entry.getKey());
                if(child==null){complete=false;continue;}
                complete=project(entry.getValue(),child,path+"/"+entry.getKey(),fields)&&complete;
            }
            return complete;
        }
        if(rule.element()!=null) {
            if(!(value instanceof List<?> list) || list.size()>rule.bound())return false;
            fields.put(path+"/length",literal(list.size()));boolean complete=true;
            for(int i=0;i<list.size();i++)complete=project(list.get(i),rule.element(),path+"/"+i,fields)&&complete;
            return complete;
        }
        if(rule.hash()) {
            if(!(value instanceof String text) || text.length()>rule.bound())return false;
            try {
                String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(text).getBytes(StandardCharsets.UTF_8)));
                fields.put(path,Map.of("kind","SHA256_JSON","sha256",sha));return true;
            }catch(Exception impossible){throw new IllegalStateException("无法生成工具输入证据",impossible);}
        }
        if(rule.values()!=null) {
            if(!(value instanceof String text) || !knownChoice(rule,text,path))return false;
        } else if(!(value instanceof Number n) || n.doubleValue()!=n.longValue() || n.longValue()<0 || n.longValue()>rule.bound())return false;
        fields.put(path,literal(value));return true;
    }
    private static boolean knownChoice(Rule rule,String text,String path) {
        if(rule.values().contains(text))return true;
        // Preserve the raw spelling of aliases explicitly supported by the Field DTO.
        if(text.length()>40)return false;
        if(path.equals("/request/field/gameType"))return rule.values().contains(text.trim());
        if(path.equals("/request/field/weather")||path.equals("/request/field/terrain"))
            return rule.values().contains(text.trim())||text.trim().isEmpty()||text.trim().equalsIgnoreCase("none")||text.trim().equals("无");
        return false;
    }
    private static Map<String,Object> literal(Object value) {
        var entry=new LinkedHashMap<String,Object>();entry.put("kind","LITERAL");entry.put("value",value);return Collections.unmodifiableMap(entry);
    }
}
