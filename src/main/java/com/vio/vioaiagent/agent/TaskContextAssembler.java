package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.config.ContextBudgetProperties;
import com.vio.vioaiagent.observability.*;
import org.springframework.ai.chat.messages.*;
import java.util.*;

/** Remove only complete old conversation turns; current work and evidence are indivisible. */
public final class TaskContextAssembler {
    private final ContextBudgetProperties limits;
    private final ContextTokenEstimator estimator;
    public record Result(List<Message> messages,int omittedTurns,int estimatedTokens) {
        public Result {messages=List.copyOf(messages);}
    }
    public TaskContextAssembler(ContextBudgetProperties limits){this.limits=Objects.requireNonNull(limits);estimator=new ContextTokenEstimator(limits.safetyPercent());}
    public Result assemble(SystemMessage policy,List<Message> history,UserMessage current,List<Message> evidence,List<Message> work) {
        Objects.requireNonNull(policy);Objects.requireNonNull(current);
        if(history.size()%2!=0)throw new IllegalArgumentException("Incomplete historical turn");
        for(int i=0;i<history.size();i+=2)if(!(history.get(i) instanceof UserMessage)||!(history.get(i+1) instanceof AssistantMessage a)||a.hasToolCalls())throw new IllegalArgumentException("Invalid historical turn");
        var protectedPairs=new ArrayList<Message>(evidence);protectedPairs.addAll(work);validatePairs(protectedPairs);
        int removed=0;
        while(true){
            var kept=history.subList(removed*2,history.size());
            var messages=new ArrayList<Message>();
            messages.add(removed==0?policy:new SystemMessage(policy.getText()+"\n部分旧历史未纳入当前请求；当前问题及本次实际工具证据优先，遗漏条件不得猜测。"));
            messages.addAll(kept);messages.add(current);messages.addAll(protectedPairs);
            int historyTokens=count(kept),total=count(messages);
            if(kept.size()/2<=limits.historyTurns()&&historyTokens<=limits.historyTokens()&&total<=limits.inputTokens())return new Result(messages,removed,total);
            if(kept.isEmpty())throw new ContextBudgetExceededException();
            removed++;
        }
    }
    private int count(List<Message> messages){int total=0;for(var m:messages)total=Math.addExact(total,estimator.message(m));return total;}
    private static void validatePairs(List<Message> messages){
        if(messages.size()%2!=0)throw new IllegalArgumentException("Incomplete tool pair");
        var allIds=new HashSet<String>();
        for(int i=0;i<messages.size();i+=2){
            if(!(messages.get(i) instanceof AssistantMessage a)||!a.hasToolCalls()||!(messages.get(i+1) instanceof ToolResponseMessage t))throw new IllegalArgumentException("Invalid tool pair");
            var expected=new HashMap<String,String>();
            for(var call:a.getToolCalls())if(call.id()==null||call.id().isBlank()||call.name()==null||call.name().isBlank()||!allIds.add(call.id())||expected.put(call.id(),call.name())!=null)throw new IllegalArgumentException("Duplicate tool id");
            if(t.getResponses().size()!=expected.size())throw new IllegalArgumentException("Missing tool result");
            for(var response:t.getResponses())if(!Objects.equals(expected.remove(response.id()),response.name()))throw new IllegalArgumentException("Mismatched tool result");
            if(!expected.isEmpty())throw new IllegalArgumentException("Missing tool result");
        }
    }
}
