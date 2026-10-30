package com.vio.vioaiagent.tools.governance;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PreviewInputEvidenceTest {
    private ToolCapabilityCard card(){return ToolCapabilityCatalog.find("previewTeamChanges").orElseThrow();}
    private Map<?,?> fields(Map<String,Object> proof){return (Map<?,?>)proof.get("fields");}
    @Test void patchShapeNullAndOmissionRemainDistinctWhileTextIsHashed(){
        var patch=new LinkedHashMap<String,Object>();patch.put("slot",1);patch.put("level",51);patch.put("item",null);
        var proof=ToolInputEvidence.capture(card(),Map.of("teamId",1,"changes",List.of(patch),"rationale","private-strategy"),"digest");
        assertEquals("COMPLETE",proof.get("coverage"));assertEquals(ToolInputEvidence.PREVIEW_POLICY_VERSION,proof.get("projectionVersion"));
        assertEquals(144,proof.get("fieldLimit"));assertNull(((Map<?,?>)fields(proof).get("/changes/0/item")).get("value"));
        assertFalse(fields(proof).containsKey("/changes/0/nature"));assertFalse(proof.toString().contains("private-strategy"));
        assertEquals("SHA256_JSON",((Map<?,?>)fields(proof).get("/rationale")).get("kind"));
    }
    @Test void sixFullySpecifiedMembersFitOnlyTheExplicitPreviewBudget(){
        var stats=Map.of("hp",0,"atk",0,"def",0,"spa",252,"spd",4,"spe",252);
        var changes=new ArrayList<Map<String,Object>>();
        for(int slot=1;slot<=6;slot++)changes.add(Map.of("slot",slot,"moves",List.of("surf","protect","ice-beam","water-pulse"),"ability","torrent","item","leftovers","nature","modest","level",50,"evs",stats,"ivs",Map.of("hp",31,"atk",31,"def",31,"spa",31,"spd",31,"spe",31)));
        var proof=ToolInputEvidence.capture(card(),Map.of("teamId",1,"changes",changes,"rationale","private-reason"),"digest");
        assertEquals("COMPLETE",proof.get("coverage"));assertEquals(135,fields(proof).size());
        assertFalse(proof.toString().contains("leftovers"));assertFalse(proof.toString().contains("private-reason"));
    }
    @Test void unknownFieldsAndOversizedValuesCannotBecomeCompleteOrLeakPlaintext(){
        for(var args:List.of(Map.of("teamId",1,"changes",List.of(Map.of("slot",1,"password","private-secret")),"rationale","reason"),
                Map.of("teamId",1,"changes",Collections.nCopies(7,Map.of("slot",1)),"rationale","reason"),
                Map.of("teamId",1,"changes",List.of(Map.of("slot",1,"level",101)),"rationale","private-secret".repeat(1200)))){
            var proof=ToolInputEvidence.capture(card(),args,"digest");assertEquals("PARTIAL",proof.get("coverage"));assertFalse(proof.toString().contains("private-secret"));
        }
    }
    @Test void readProjectionDoesNotAuthorizeWriteOrHigherRiskCards(){
        for(var risk:ToolCapabilityCard.RiskLevel.values()){
            var write=new ToolCapabilityCard("previewTeamChanges","1",risk,ToolCapabilityCard.SideEffect.WRITE,true,1000,List.of("trainer"),"{}","{}");
            assertEquals("OMITTED",ToolInputEvidence.capture(write,Map.of("rationale","secret"),"digest").get("coverage"));
        }
        assertFalse(card().isWriteLike());assertEquals(ToolCapabilityCard.RiskLevel.LOW,card().riskLevel());
    }
}
