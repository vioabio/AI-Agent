package com.vio.vioaiagent.pokemon;

import com.vio.vioaiagent.pokemon.team.*;
import com.vio.vioaiagent.pokemon.domain.*;
import com.vio.vioaiagent.run.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeamChangePresentationTest {
    static final String RECEIPT="已保存\n| 成员 | 配置 | 修改前 | 修改后 |\n| --- | --- | --- | --- |\n| 同名宝可梦 | 等级 | 50 | 51 |\n| 同名宝可梦 | 道具 | leftovers |  |\n边界说明";
    TeamWorkflowService.Outcome outcome(String status) {
        var preview=new TeamWorkflowService.Preview("approval-test",1,"练习队",List.of(
            new TeamWorkflowService.ChangeLine(1,"同名宝可梦","等级","50","51"),
            new TeamWorkflowService.ChangeLine(2,"同名宝可梦","道具","leftovers","")),"按用户要求",Instant.now().plusSeconds(60),"未计算",List.of());
        var team=new SavedTeam(1,"trainer","练习队","casual-v1","pokeapi-reference-v1",List.of(new TeamMember(0,7,1,TeamBuild.defaults())));
        return new TeamWorkflowService.Outcome(preview,status,team,RECEIPT);
    }
    @Test void appliedReceiptLabelsSlotsAndPreservesAllOutcomeFields() {
        var raw=outcome("APPLIED");var rendered=TeamChangePresentation.outcome(raw);
        assertTrue(rendered.message().contains("第1位 · 同名宝可梦"));assertTrue(rendered.message().contains("第2位 · 同名宝可梦"));
        assertTrue(rendered.message().endsWith("边界说明"));assertSame(raw.savedTeam(),rendered.savedTeam());assertSame(raw.preview(),rendered.preview());assertEquals(raw.status(),rendered.status());
        assertEquals(rendered,TeamChangePresentation.outcome(rendered));
    }
    @Test void nonAppliedOrUnmatchedMessagesAreNeverRewrittenAsSaved() {
        var raw=outcome("CONFLICT");assertEquals(raw,TeamChangePresentation.outcome(raw));
        var unrecognized=new TeamWorkflowService.Outcome(raw.preview(),"APPLIED",raw.savedTeam(),"另外的回执格式");
        assertEquals(unrecognized,TeamChangePresentation.outcome(unrecognized));
    }
    @Test void streamAndReturnShareOneRenderedReceiptAndTheOriginalExecutionGuards() {
        var workflow=mock(TeamWorkflowService.class);var run=mock(AgentRun.class);when(run.id()).thenReturn("run-test");when(run.userId()).thenReturn("trainer");
        var text=new ArrayList<String>();var context=new AgentRunContext(run,text::add);
        when(workflow.finishIfPending(any())).thenAnswer(call->{
            AgentRunContext actual=call.getArgument(0);assertNull(actual.answerDeltaSink());assertSame(context.mutations(),actual.mutations());
            assertSame(context.modelUsage(),actual.modelUsage());assertSame(context.constraints(),actual.constraints());assertEquals(context.history(),actual.history());return RECEIPT;
        });
        when(workflow.outcome("run-test","trainer")).thenReturn(outcome("APPLIED"));
        String answer=TeamChangePresentation.finishIfPending(workflow,context);assertEquals(List.of(answer),text);assertTrue(answer.contains("第2位 · 同名宝可梦"));
        verify(workflow,times(1)).finishIfPending(any());
    }
}
