package com.vio.vioaiagent.pokemon.team;

import com.vio.vioaiagent.run.AgentRunContext;
import java.util.List;

/** Slot labels at presentation boundaries; the cited workflow implementation and write semantics stay intact. */
public final class TeamChangePresentation {
    private TeamChangePresentation() { }

    public static String finishIfPending(TeamWorkflowService workflow,AgentRunContext context) {
        // The workflow publishes a complete program-generated receipt. Render it once after it returns,
        // retaining the same mutation guard, usage ledger, constraints and history.
        var quiet=new AgentRunContext(context.run(),null,context.history(),context.mutations(),context.modelUsage(),context.constraints());
        String receipt=workflow.finishIfPending(quiet);
        if(receipt==null)return null;
        String visible=render(workflow.outcome(context.run().id(),context.run().userId()),receipt);
        if(context.answerDeltaSink()!=null)context.answerDeltaSink().accept(visible);
        return visible;
    }

    public static TeamWorkflowService.Outcome outcome(TeamWorkflowService.Outcome value) {
        return new TeamWorkflowService.Outcome(value.preview(),value.status(),value.savedTeam(),render(value,value.message()));
    }

    private static String render(TeamWorkflowService.Outcome outcome,String message) {
        if(message==null||!"APPLIED".equals(outcome.status())||outcome.savedTeam()==null||outcome.preview()==null)return message;
        String original=table(outcome.preview().changes(),false);
        return message.replace(original,table(outcome.preview().changes(),true));
    }

    private static String table(List<TeamWorkflowService.ChangeLine> changes,boolean slots) {
        var text=new StringBuilder("| 成员 | 配置 | 修改前 | 修改后 |\n| --- | --- | --- | --- |\n");
        for(var line:changes) {
            text.append("| ");if(slots)text.append("第").append(line.slot()).append("位 · ");
            text.append(safe(line.member())).append(" | ").append(line.field()).append(" | ")
                .append(safe(line.before())).append(" | ").append(safe(line.after())).append(" |\n");
        }
        return text.toString();
    }
    private static String safe(String text) { return text.replaceAll("[|\\r\\n*`<>]"," "); }
}
