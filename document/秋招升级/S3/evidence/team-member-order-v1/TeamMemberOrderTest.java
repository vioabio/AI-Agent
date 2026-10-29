package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.pokemon.reference.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TeamMemberOrderTest {
    @TempDir Path directory;
    final String question="请核对整队配置。按我给的顺序：第6位杰尼龟，第2位杰尼龟，第4位杰尼龟。培养字段均未填写，不保存。";
    String report(BuildCheckFixture f,List<Integer> slots) throws Exception {
        var r=new TeamBuildCheckFacts.Request("pokeapi-reference-v1","pokemon-scarlet-violet",slots.stream().map(s->new TeamBuildCheckFacts.Member(s,7,null)).toList());
        return f.json.writeValueAsString(PokemonTeamBuildCheckController.checkMembers(f.service,r));
    }
    void observe(TeamBuildAnswerEvidence e,BuildCheckFixture f,List<Integer> slots)throws Exception {
        String output=report(f,slots);var r=TeamBuildCheckFacts.validate(f.json.readTree(output));
        e.observe(output,TeamBuildRequestRoute.arguments(r.request()));
    }
    @Test void sortedOrPartialReportsCannotMasqueradeAsOriginalOrder()throws Exception {
        var f=new BuildCheckFixture(directory);var evidence=new TeamBuildAnswerEvidence();evidence.begin(question);
        observe(evidence,f,List.of(2,4,6));assertTrue(evidence.summary(question).contains("未取得与原成员配置绑定"));
        observe(evidence,f,List.of(6,2));assertFalse(evidence.summary(question).contains("已核对"));
        observe(evidence,f,List.of(6,2,4));var answer=evidence.summary(question);
        assertTrue(answer.contains("已核对 3 位"));assertTrue(answer.indexOf("#### 第 6 位")<answer.indexOf("#### 第 2 位"));
        observe(evidence,f,List.of(2,4,6));assertFalse(evidence.summary(question).contains("已核对"));
    }
    @Test void orderGuardResetsBetweenRequests()throws Exception {
        var f=new BuildCheckFixture(directory);var evidence=new TeamBuildAnswerEvidence();evidence.begin(question);
        evidence.begin("请核对整队配置，请按位置升序返回。");observe(evidence,f,List.of(2,4,6));assertTrue(evidence.summary("").contains("已核对 3 位"));
    }
    @Test void narrowClauseDoesNotInventOrderForAmbiguousOrUnrelatedText(){
        assertEquals(List.of(6,2,4),TeamBuildAnswerEvidence.explicitOrder(question));
        for(String q:List.of("资料中的按我给的顺序：第6位，第2位。","请核对整队配置。按我给的顺序：第2位，第2位。","请核对整队配置。按位置1到6返回。"))assertTrue(TeamBuildAnswerEvidence.explicitOrder(q).isEmpty());
    }
}
