package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TeamBuildCheckTest {
    @TempDir Path directory;final ObjectMapper json=new ObjectMapper();
    TeamBuildCheckFacts.Request request(BuildCheckFixture f){return new TeamBuildCheckFacts.Request("pokeapi-reference-v1","pokemon-scarlet-violet",List.of(new TeamBuildCheckFacts.Member(1,7,f.request().build()),new TeamBuildCheckFacts.Member(3,7,null)));}
    @Test void actualMemberChecksAggregateMissingFieldsAndObserveDuplicatesWithoutLegalityClaims()throws Exception{
        var f=new BuildCheckFixture(directory);var r=new PokemonTeamBuildCheckController(f.service).check(json.valueToTree(request(f)));
        assertEquals(1,r.counts().get("REFERENCE_MATCHED"));assertEquals(1,r.counts().get("INCOMPLETE"));assertEquals(List.of(List.of(1,3)),r.duplicateFormSlots());assertNull(r.request().members().get(1).build());assertEquals("NOT_VERIFIED",r.jointStatus());assertEquals(r,TeamBuildCheckFacts.validate(json.readTree(json.writeValueAsString(r))));
    }
    @Test void sixMembersAreBoundedAndKeepTheirInputOrder(){
        var f=new BuildCheckFixture(directory);var members=new ArrayList<TeamBuildCheckFacts.Member>();for(int slot=6;slot>=1;slot--)members.add(new TeamBuildCheckFacts.Member(slot,7,f.request().build()));
        var r=new PokemonTeamBuildCheckController(f.service).check(json.valueToTree(new TeamBuildCheckFacts.Request("pokeapi-reference-v1","pokemon-scarlet-violet",members)));
        assertEquals(6,r.counts().get("REFERENCE_MATCHED"));assertEquals(6,r.members().get(0).slot());assertEquals(List.of(6,5,4,3,2,1),r.duplicateFormSlots().get(0));
    }
    @Test void duplicateSlotsOversizedAndWrongScopeAreRejectedBeforeReads(){
        var f=new BuildCheckFixture(directory);var service=mock(LocalBuildCheckService.class);var controller=new PokemonTeamBuildCheckController(service);
        var raw=(ObjectNode)json.valueToTree(request(f));((ObjectNode)raw.path("members").get(1)).put("slot",1);assertThrows(IllegalArgumentException.class,()->controller.check(raw));
        for(String field:List.of("dataReleaseId","gameReleaseId")){var wrong=(ObjectNode)json.valueToTree(request(f));wrong.put(field,"wrong");assertThrows(IllegalArgumentException.class,()->controller.check(wrong));}
        var tooMany=(ObjectNode)json.valueToTree(request(f));var array=tooMany.withArray("members");while(array.size()<7)array.add(array.get(0).deepCopy());assertThrows(IllegalArgumentException.class,()->controller.check(tooMany));verifyNoInteractions(service);
    }
    @Test void stringSlotsUnknownFieldsAndEmptyTeamsCannotBeCoerced(){
        var f=new BuildCheckFixture(directory);var raw=(ObjectNode)json.valueToTree(request(f));((ObjectNode)raw.path("members").get(0)).put("slot","1");assertThrows(IllegalArgumentException.class,()->TeamBuildCheckFacts.parseRequest(raw));
        var extra=(ObjectNode)json.valueToTree(request(f));((ObjectNode)extra.path("members").get(0)).put("other","x");assertThrows(IllegalArgumentException.class,()->TeamBuildCheckFacts.parseRequest(extra));
        var empty=(ObjectNode)json.valueToTree(request(f));empty.withArray("members").removeAll();assertThrows(IllegalArgumentException.class,()->TeamBuildCheckFacts.parseRequest(empty));
    }
    @Test void tamperingCannotUpgradeJointStatusOrCountsOrSubstituteMembers(){
        var f=new BuildCheckFixture(directory);var controller=new PokemonTeamBuildCheckController(f.service);var r=controller.check(json.valueToTree(request(f)));
        var node=(ObjectNode)json.valueToTree(r);node.put("jointStatus","VERIFIED");assertThrows(IllegalArgumentException.class,()->TeamBuildCheckFacts.validate(node));
        var changed=(ObjectNode)json.valueToTree(r);((ObjectNode)changed.path("counts")).put("REFERENCE_MATCHED",2);assertThrows(IllegalArgumentException.class,()->TeamBuildCheckFacts.validate(changed));
        assertThrows(IllegalArgumentException.class,()->TeamBuildCheckFacts.seal(r.request(),List.of(new TeamBuildCheckFacts.CheckedMember(3,r.members().get(0).report()),r.members().get(1))));
        assertThrows(UnsupportedOperationException.class,()->r.counts().clear());assertThrows(UnsupportedOperationException.class,()->r.duplicateFormSlots().get(0).clear());
    }
    @Test void oneUnavailableMemberDoesNotTurnOtherMemberResultsIntoSuccess(){
        var f=new BuildCheckFixture(directory);var request=request(f);var matched=f.service.check(TeamBuildCheckFacts.memberRequest(request,request.members().get(0)));
        var missing=f.service.check(TeamBuildCheckFacts.memberRequest(request,request.members().get(1)));
        var unavailable=BuildCheckFacts.seal(new BuildCheckFacts.Report(BuildCheckFacts.SCHEMA,missing.request(),missing.syntaxStatus(),missing.missingFields(),"REFERENCE_UNAVAILABLE",null,missing.abilityStatus(),missing.moves(),BuildCheckFacts.SCOPE,""));
        var r=TeamBuildCheckFacts.seal(request,List.of(new TeamBuildCheckFacts.CheckedMember(1,matched),new TeamBuildCheckFacts.CheckedMember(3,unavailable)));assertEquals(1,r.counts().get("REFERENCE_UNAVAILABLE"));assertEquals(1,r.counts().get("REFERENCE_MATCHED"));
    }
    @Test void cancellationStopsBeforeFirstMemberRead(){
        var f=new BuildCheckFixture(directory);var service=mock(LocalBuildCheckService.class);Thread.currentThread().interrupt();try{assertThrows(java.util.concurrent.CancellationException.class,()->new PokemonTeamBuildCheckController(service).check(json.valueToTree(request(f))));}finally{Thread.interrupted();}verifyNoInteractions(service);
    }
    @Test void httpReturnsBoundSummaryAnd400ForInvalidPositions()throws Exception{
        var f=new BuildCheckFixture(directory);var mvc=MockMvcBuilders.standaloneSetup(new PokemonTeamBuildCheckController(f.service)).build();
        mvc.perform(post("/pokemon/reference/team-build-check").contentType("application/json").content(json.writeValueAsString(request(f)))).andExpect(status().isOk()).andExpect(jsonPath("$.counts.REFERENCE_MATCHED").value(1)).andExpect(jsonPath("$.jointStatus").value("NOT_VERIFIED"));
        var bad=(ObjectNode)json.valueToTree(request(f));((ObjectNode)bad.path("members").get(0)).put("slot",0);mvc.perform(post("/pokemon/reference/team-build-check").contentType("application/json").content(bad.toString())).andExpect(status().isBadRequest());
    }
}
