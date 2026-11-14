package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.pokemon.domain.TeamBuild;
import com.vio.vioaiagent.pokemon.team.TeamWorkflowService;
import com.vio.vioaiagent.pokemon.engine.DamageResultContract;
import java.time.Instant;
import java.util.*;

/** Saved cultivation settings, not a generated strategy or proof of in-game state/legality. */
public record TrainingPlanAnswerBlock(String schemaVersion,String blockId,String kind,long teamId,long revision,
        String teamName,String gameReleaseId,String dataReleaseId,String rulesetVersionId,String scope,List<Member> members) {
    public static final String SCOPE="SAVED_BUILD_SNAPSHOT_NOT_TRAINING_ADVICE";
    public record Member(int slot,PokemonAnswerBlock pokemon,TeamBuild build) {
        public Member {if(slot<1||slot>6||pokemon==null||build==null)throw new IllegalArgumentException("Invalid saved build member");}
    }
    public TrainingPlanAnswerBlock {
        if(!"answer-block-v1".equals(schemaVersion)||!"trainingPlan".equals(kind)||!SCOPE.equals(scope)||teamId<1||revision<0
                ||teamName==null||teamName.isBlank()||teamName.length()>128||!"pokemon-scarlet-violet".equals(gameReleaseId)||!"pokeapi-reference-v1".equals(dataReleaseId)
                ||rulesetVersionId==null||rulesetVersionId.isBlank()||rulesetVersionId.length()>64||members==null||members.isEmpty()||members.size()>6
                ||members.stream().anyMatch(m->m==null||!gameReleaseId.equals(m.pokemon().gameReleaseId())||!dataReleaseId.equals(m.pokemon().dataReleaseId()))
                ||members.stream().map(Member::slot).distinct().count()!=members.size()
                ||members.stream().map(m->m.pokemon().formId()).distinct().count()!=members.size())throw new IllegalArgumentException("Invalid saved cultivation block");
        members=List.copyOf(members);
        if(!Objects.equals(blockId,identity(teamId,revision,teamName,rulesetVersionId,members)))throw new IllegalArgumentException("Unbound saved cultivation block");
    }
    public static TrainingPlanAnswerBlock from(long requestedId,String owner,TeamWorkflowService.ReadTeam read,Instant now) {
        if(read==null||read.snapshot()==null||read.snapshot().team()==null||read.expiresAt()==null||now==null||!now.isBefore(read.expiresAt()))throw new IllegalArgumentException("Expired or missing snapshot");
        var team=read.snapshot().team();
        if(owner==null||!owner.equals(team.ownerId())||requestedId!=team.id()||read.facts().size()!=team.members().size())throw new IllegalArgumentException("Unbound saved team snapshot");
        var members=new ArrayList<Member>();
        for(int i=0;i<team.members().size();i++) {
            var member=team.members().get(i);var fact=read.facts().get(i);
            if(fact==null||member.formId()!=fact.formId())throw new IllegalArgumentException("Mismatched saved member fact");
            members.add(new Member(member.slot(),PokemonAnswerBlock.from(fact),member.build()));
        }
        return new TrainingPlanAnswerBlock("answer-block-v1",identity(team.id(),read.snapshot().revision(),team.name(),team.rulesetId(),members),"trainingPlan",team.id(),read.snapshot().revision(),team.name(),"pokemon-scarlet-violet",team.dataReleaseId(),team.rulesetId(),SCOPE,members);
    }
    private static String identity(long team,long revision,String name,String rules,List<Member> members){return "training-"+DamageResultContract.digest(List.of(team,revision,name,rules,members));}
}
