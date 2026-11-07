package com.vio.vioaiagent.tools.governance;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.agent.TeamBuildAnswerEvidence;
import java.util.Map;
public final class TeamBuildResultEvidence {
    public static final String SCHEMA="team-build-output-evidence-v1";
    private TeamBuildResultEvidence() { }
    public static Map<String,Object> capture(ToolCapabilityCard card,Map<String,Object> arguments,String parameterDigest,String output){try{
        if(!"checkPokemonTeamBuild".equals(card.name())||card.isWriteLike())throw new IllegalArgumentException();
        var r=TeamBuildAnswerEvidence.bound(output,new ObjectMapper().writeValueAsString(arguments));
        return Map.of("schemaVersion",SCHEMA,"status","VERIFIED","toolVersion",card.version(),"parameterDigest",parameterDigest,"memberCount",r.members().size(),"reportSha256",r.reportSha256(),"jointStatus",r.jointStatus(),"scope",r.scope());
    }catch(Exception invalid){return Map.of("schemaVersion",SCHEMA,"status","INVALID","reason","UNBOUND_OR_INVALID_TEAM_BUILD");}}
}
