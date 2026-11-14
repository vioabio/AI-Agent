package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.pokemon.domain.*;
import com.vio.vioaiagent.pokemon.repository.SavedTeamRepository;
import com.vio.vioaiagent.pokemon.team.TeamWorkflowService;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class TrainingPlanAnswerBlockTest {
    public static TeamWorkflowService.ReadTeam read(String owner){
        var facts=TeamSuggestionAnswerBlockTest.evidence().members().stream().map(m->m.detail().fact()).toList();
        var build=new TeamBuild(List.of("water-gun","protect"),"torrent","","modest",51,new TeamBuild.Stats(0,0,4,252,0,252),TeamBuild.Stats.filled(31),"water");
        var team=new SavedTeam(91,owner,"培养测试队伍","casual-v1","pokeapi-reference-v1",List.of(new TeamMember(1,7,1,build),new TeamMember(2,25,2,TeamBuild.defaults())));
        return new TeamWorkflowService.ReadTeam(new SavedTeamRepository.Snapshot(team,2),facts,List.of("缺项"),Instant.now().plusSeconds(60));
    }
    @Test void savedFieldsAndTeraAreExactImmutableAndDoNotExposeOwner() throws Exception {
        var read=read("u");var card=TrainingPlanAnswerBlock.from(91,"u",read,Instant.now());
        assertEquals(51,card.members().getFirst().build().level());assertEquals("water",card.members().getFirst().build().teraType());
        assertEquals(252,card.members().getFirst().build().evs().spa());assertEquals("modest",card.members().getFirst().build().nature());
        assertTrue(card.members().getLast().build().moves().isEmpty());assertEquals("",card.members().getLast().build().item());
        assertThrows(UnsupportedOperationException.class,()->card.members().clear());
        assertFalse(new ObjectMapper().writeValueAsString(card).contains("ownerId"));
    }
    @Test void identityOwnerExpiryAndMemberMismatchAreRejected(){
        var read=read("u");
        assertThrows(IllegalArgumentException.class,()->TrainingPlanAnswerBlock.from(92,"u",read,Instant.now()));
        assertThrows(IllegalArgumentException.class,()->TrainingPlanAnswerBlock.from(91,"other",read,Instant.now()));
        assertThrows(IllegalArgumentException.class,()->TrainingPlanAnswerBlock.from(91,"u",read,read.expiresAt()));
        var swapped=new TeamWorkflowService.ReadTeam(read.snapshot(),List.of(read.facts().getLast(),read.facts().getFirst()),List.of(),read.expiresAt());
        assertThrows(IllegalArgumentException.class,()->TrainingPlanAnswerBlock.from(91,"u",swapped,Instant.now()));
    }
    @Test void truncatedToolJsonCannotAcquireConstructorDefaults() throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules();var root=(ObjectNode)mapper.valueToTree(read("u"));
        var valid=new SavedTeamAnswerEvidence();valid.observe("readCurrentTeam",mapper.writeValueAsString(root));assertTrue(valid.verifiedRead().isPresent());
        for(String field:List.of("level","nature","moves","ability","item","evs","ivs")){
            ObjectNode broken=root.deepCopy();((ObjectNode)broken.path("snapshot").path("team").path("members").get(0).path("build")).remove(field);
            valid.observe("readCurrentTeam",mapper.writeValueAsString(broken));assertTrue(valid.verifiedRead().isEmpty(),field);
        }
    }
}
