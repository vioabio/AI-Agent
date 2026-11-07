package com.vio.vioaiagent.tools.governance;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.MachineReferenceFacts;
import java.util.*;
public final class MachineResultEvidence {
    public static final String SCHEMA="machine-output-evidence-v1";
    private MachineResultEvidence(){ }
    public static Map<String,Object> capture(ToolCapabilityCard card,Map<String,Object> args,String digest,String output){try{
        if(!"lookupPokemonMachine".equals(card.name())||card.isWriteLike())throw new IllegalArgumentException();
        var r=MachineReferenceFacts.bound(output,new ObjectMapper().writeValueAsString(args));
        return Map.of("schemaVersion",SCHEMA,"status","VERIFIED","toolVersion",card.version(),"parameterDigest",digest,"pokemonId",r.pokemonId(),"moveSlug",r.moveSlug(),"recordStatus",r.status(),"reportSha256",r.reportSha256());
    }catch(Exception invalid){return Map.of("schemaVersion",SCHEMA,"status","INVALID","reason","UNBOUND_MACHINE_REFERENCE");}}
}
