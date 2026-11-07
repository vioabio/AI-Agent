package com.vio.vioaiagent.tools.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.agent.BuildCheckAnswerEvidence;
import java.util.*;

public final class BuildCheckResultEvidence {
    public static final String SCHEMA="build-check-output-evidence-v1";
    private static final ObjectMapper JSON=new ObjectMapper();
    private BuildCheckResultEvidence(){ }
    public static Map<String,Object> capture(ToolCapabilityCard card,Map<String,Object> arguments,String parameterDigest,String output){
        try{
            if(!"checkPokemonBuild".equals(card.name())||card.isWriteLike())throw new IllegalArgumentException();
            var result=BuildCheckAnswerEvidence.bound(output,JSON.writeValueAsString(arguments));
            return Map.of("schemaVersion",SCHEMA,"status","VERIFIED","toolVersion",card.version(),"parameterDigest",parameterDigest,
                    "pokemonId",result.request().pokemonId(),"checkStatus",result.status(),"reportSha256",result.reportSha256(),"scope",result.scope());
        }catch(Exception invalid){return Map.of("schemaVersion",SCHEMA,"status","INVALID","reason","UNBOUND_OR_INVALID_BUILD_CHECK");}
    }
}
