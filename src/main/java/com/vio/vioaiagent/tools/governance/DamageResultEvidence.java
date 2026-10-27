package com.vio.vioaiagent.tools.governance;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.engine.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Minimal result projection bound to the parsed request and the actual callback output. */
public final class DamageResultEvidence {
    public static final String SCHEMA="damage-result-evidence-v1";
    private static final ObjectMapper JSON=new ObjectMapper();
    private DamageResultEvidence() { }
    public static Map<String,Object> capture(ToolCapabilityCard card,Map<String,Object> supplied,String parameterDigest,String output) {
        try {
            if(!"calculateBattleDamage".equals(card.name())||card.riskLevel()!=ToolCapabilityCard.RiskLevel.LOW||card.isWriteLike()||output==null||output.length()>131072)throw new IllegalArgumentException();
            var result=JSON.readTree(output);DamageResultContract.requireShape(result);
            var typed=JSON.convertValue(supplied.get("request"),LocalDamageEngine.Request.class);
            var validation=result.path("conditionValidation");
            if(typed==null||!DamageResultContract.SCHEMA.equals(validation.path("schemaVersion").asText())||!"VERIFIED".equals(validation.path("status").asText())
                    ||!DamageResultContract.digest(typed).equals(validation.path("typedRequestDigest").asText())
                    ||!DamageResultContract.projectionDigest(result).equals(validation.path("validatedProjectionDigest").asText()))throw new IllegalArgumentException();
            var request=JSON.createObjectNode().put("generation",9).put("dataReleaseId","pokeapi-reference-v1");
            for(String side:List.of("attacker","defender")) {
                var original=result.path("assumptions").path(side);var combatant=request.putObject(side).put("species",original.path("species").asText());var build=combatant.putObject("build");
                for(String field:List.of("level","nature","ability","item","evs","ivs"))build.set(field,original.path(field));
            }
            request.set("move",result.path("assumptions").path("move"));request.set("field",result.path("assumptions").path("field"));
            Map<String,Object> effective=JSON.convertValue(Map.of("request",request),Map.class);
            var projection=ToolInputEvidence.capture(card,effective,parameterDigest);
            if(!"COMPLETE".equals(projection.get("coverage")))throw new IllegalArgumentException();
            var raw=JSON.valueToTree(supplied);var fields=(Map<?,?>)projection.get("fields");var omitted=new ArrayList<String>();var nulls=new ArrayList<String>();var abilities=new ArrayList<String>();
            for(Object key:fields.keySet()) {
                String path=key.toString();var value=raw.at(path);
                if(value.isMissingNode())omitted.add(path);else if(value.isNull())nulls.add(path);
                if(path.endsWith("/ability")&&(value.isMissingNode()||value.isNull()||value.isTextual()&&value.asText().isBlank()))abilities.add(path);
            }
            Map<String,Object> proof=new LinkedHashMap<>();proof.put("schemaVersion",SCHEMA);proof.put("status","VERIFIED");proof.put("scope","SUPPORTED_CONDITIONS_AND_RANGE_ONLY");
            proof.put("parameterDigest",parameterDigest);proof.put("toolVersion",card.version());proof.put("typedRequestDigest",validation.path("typedRequestDigest").asText());
            proof.put("outputDigest",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output.getBytes(StandardCharsets.UTF_8))));
            proof.put("resolvedFields",fields);proof.put("omittedFields",List.copyOf(omitted));proof.put("explicitNullFields",List.copyOf(nulls));proof.put("engineDefaultAbilityFields",List.copyOf(abilities));
            proof.put("range",Map.of("minimum",result.path("range").path("minimum").asInt(),"maximum",result.path("range").path("maximum").asInt()));
            proof.put("percent",Map.of("minimum",result.path("percent").path("minimum").asDouble(),"maximum",result.path("percent").path("maximum").asDouble()));proof.put("defenderHp",result.path("defenderHp").asInt());
            return Collections.unmodifiableMap(proof);
        }catch(Exception invalid){return Map.of("schemaVersion",SCHEMA,"status","UNAVAILABLE");}
    }
}
