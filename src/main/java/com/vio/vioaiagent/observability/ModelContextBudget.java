package com.vio.vioaiagent.observability;

import com.vio.vioaiagent.config.ContextBudgetProperties;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.BeanWrapperImpl;
import java.util.*;

/** One execution attempt. Only complete prior turns are removable; current work is indivisible. */
public final class ModelContextBudget {
    private static final String HISTORY_NOTICE="部分历史未纳入本次请求。只依据可见历史和当前问题回答；不要猜测遗漏的用户限制，条件不足时先追问。当前Run的游戏版本、赛制和本轮工具事实优先。";
    private final ContextBudgetProperties limits;
    private final ContextTokenEstimator estimator;
    private final List<Message> history;
    private final ModelUsageLedger ledger;
    private final int availableTurns,initiallyOmitted;
    private int checks,maximumOmitted;
    private Map<String,Object> latest=Map.of(),baseEstimate=Map.of();
    private ContextBudgetExceededException failure;

    public ModelContextBudget(ContextBudgetProperties limits,List<Message> history,ModelUsageLedger ledger) {
        this.limits=limits;this.estimator=new ContextTokenEstimator(limits.safetyPercent());
        this.history=List.copyOf(history);this.ledger=ledger;
        var source=ledger.historySource();
        availableTurns=((Number)source.getOrDefault("availableTurns",history.size()/2)).intValue();
        initiallyOmitted=((Number)source.getOrDefault("omittedTurns",0)).intValue();
        maximumOmitted=initiallyOmitted;
    }
    public Prompt prepare(Prompt original,ChatOptions defaults) {
        if(failure!=null)throw failure;
        checks++;
        try {
            List<Message> messages=new ArrayList<>(original.getInstructions());
            int start=0;while(start<messages.size() && messages.get(start) instanceof SystemMessage)start++;
            if(history.size()%2!=0 || start+history.size()>messages.size())throw blocked("HISTORY_SHAPE");
            for(int i=0;i<history.size();i++) {
                Message expected=history.get(i),actual=messages.get(start+i);
                if((i%2==0 && !(expected instanceof UserMessage)) || (i%2==1 && !(expected instanceof AssistantMessage))
                        || expected.getMessageType()!=actual.getMessageType() || !Objects.equals(expected.getText(),actual.getText()))throw blocked("HISTORY_SHAPE");
            }
            var definitions=toolDefinitions(original.getOptions(),defaults);
            int removed=0;
            while(true) {
                List<Message> request=new ArrayList<>(messages);
                request.subList(start,start+removed*2).clear();
                int historyMessages=history.size()-removed*2;
                boolean omitted=initiallyOmitted+removed>0;
                boolean insertedSystem=omitted && (request.isEmpty() || !(request.getFirst() instanceof SystemMessage));
                if(insertedSystem)request.add(0,new SystemMessage(HISTORY_NOTICE));
                else if(omitted)request.set(0,new SystemMessage(request.getFirst().getText()+"\n\n"+HISTORY_NOTICE));
                int historyStart=start+(insertedSystem?1:0),system=0,past=0,tools=0,current=0;
                int baseSystem=0,basePast=0,baseTools=0,baseCurrent=0;
                for(int i=0;i<request.size();i++) {
                    Message message=request.get(i);var measured=estimator.estimateMessage(message);int tokens=measured.budgetTokens(),base=measured.baseTokens();
                    if(i>=historyStart && i<historyStart+historyMessages){past=Math.addExact(past,tokens);basePast=Math.addExact(basePast,base);}
                    else if(message instanceof SystemMessage){system=Math.addExact(system,tokens);baseSystem=Math.addExact(baseSystem,base);}
                    else if(message instanceof ToolResponseMessage){tools=Math.addExact(tools,tokens);baseTools=Math.addExact(baseTools,base);}
                    else {current=Math.addExact(current,tokens);baseCurrent=Math.addExact(baseCurrent,base);}
                }
                int total=Math.addExact(Math.addExact(system,past),Math.addExact(Math.addExact(tools,current),definitions.budgetTokens()));
                latest=Map.of("system",system,"history",past,"toolDefinitions",definitions.budgetTokens(),"toolResults",tools,
                        "currentMessages",current,"inputTokens",total);
                int baseTotal=Math.addExact(Math.addExact(baseSystem,basePast),Math.addExact(Math.addExact(baseTools,baseCurrent),definitions.baseTokens()));
                baseEstimate=Map.of("system",baseSystem,"history",basePast,"toolDefinitions",definitions.baseTokens(),"toolResults",baseTools,
                        "currentMessages",baseCurrent,"inputTokens",baseTotal);
                maximumOmitted=Math.max(maximumOmitted,initiallyOmitted+removed);
                if(total<=limits.inputTokens() && past<=limits.historyTokens() && historyMessages/2<=limits.historyTurns()) {
                    ChatOptions options=capOutput(original.getOptions(),defaults);
                    publish("CHECKED",null);
                    return new Prompt(request,options);
                }
                if(historyMessages==0)throw blocked("INPUT_LIMIT");
                removed++;
            }
        } catch(ContextBudgetExceededException overBudget) {
            if(failure==null)throw blocked("UNSUPPORTED_OR_OVERSIZED_INPUT");
            throw overBudget;
        } catch(RuntimeException unsupported) {throw blocked("UNSUPPORTED_OPTIONS_OR_TOOL_SCHEMA");}
    }
    private ChatOptions capOutput(ChatOptions request,ChatOptions defaults) {
        Integer requested=request==null?null:request.getMaxTokens();
        if(requested==null && defaults!=null)requested=defaults.getMaxTokens();
        int cap=requested==null?limits.outputTokens():Math.min(requested,limits.outputTokens());
        if(cap<1)throw blocked("INVALID_OUTPUT_LIMIT");
        if(request==null)return ToolCallingChatOptions.builder().maxTokens(cap).internalToolExecutionEnabled(false).build();
        if(request instanceof com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions nativeOptions)
            return DashScopeBudgetChatModel.withMaxTokens(nativeOptions,cap);
        ChatOptions copy=request.copy();
        var bean=new BeanWrapperImpl(copy);
        if(!bean.isWritableProperty("maxTokens"))throw blocked("UNSUPPORTED_OPTIONS");
        bean.setPropertyValue("maxTokens",cap);
        if(copy instanceof ToolCallingChatOptions tools)tools.setInternalToolExecutionEnabled(false);
        return copy;
    }
    private ContextTokenEstimator.Estimate toolDefinitions(ChatOptions request,ChatOptions defaults) {
        Map<String,String> definitions=new LinkedHashMap<>();Set<String> names=new HashSet<>();
        for(ChatOptions options:Arrays.asList(defaults,request))if(options instanceof ToolCallingChatOptions tools) {
            if(options instanceof com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions nativeOptions
                    && nativeOptions.getTools()!=null && !nativeOptions.getTools().isEmpty())throw blocked("UNRESOLVED_TOOL_SCHEMA");
            if(options instanceof org.springframework.ai.openai.OpenAiChatOptions nativeOptions
                    && nativeOptions.getTools()!=null && !nativeOptions.getTools().isEmpty())throw blocked("UNRESOLVED_TOOL_SCHEMA");
            if(tools.getToolNames()!=null)names.addAll(tools.getToolNames());
            if(tools.getToolCallbacks()!=null)for(var callback:tools.getToolCallbacks()) {
                var definition=callback.getToolDefinition();
                String encoded=definition.name()+"\n"+definition.description()+"\n"+definition.inputSchema();
                String previous=definitions.put(definition.name(),encoded);
                if(previous!=null && !previous.equals(encoded))throw blocked("AMBIGUOUS_TOOL_SCHEMA");
            }
        }
        if(!definitions.keySet().containsAll(names))throw blocked("UNRESOLVED_TOOL_SCHEMA");
        var total=new ContextTokenEstimator.Estimate(0,0);
        for(String text:definitions.values())total=total.plus(new ContextTokenEstimator.Estimate(16,16)).plus(estimator.estimateText(text));
        return total;
    }
    /** A provider stopped at the requested output cap; do not certify the partial answer as complete. */
    public void checkOutput(ChatResponse response) {
        if(response==null)return;
        for(var generation:response.getResults()) {
            String reason=generation.getMetadata().getFinishReason();
            if(reason!=null && Set.of("length","max_tokens","max_output_tokens").contains(reason.toLowerCase(Locale.ROOT)))throw blocked("OUTPUT_LIMIT");
        }
    }
    private ContextBudgetExceededException blocked(String reason) {
        failure=new ContextBudgetExceededException();publish("BLOCKED",reason);return failure;
    }
    private void publish(String status,String reason) {
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("schemaVersion","context-budget-v1");summary.put("estimator",ContextTokenEstimator.ID);
        summary.put("calibration","PROXY_UNCALIBRATED");summary.put("safetyPercent",limits.safetyPercent());
        summary.put("contextTokens",limits.contextTokens());summary.put("inputLimit",limits.inputTokens());
        summary.put("outputReserve",limits.outputTokens());summary.put("historyLimit",limits.historyTokens());
        summary.put("availableHistoryTurns",availableTurns);summary.put("omittedHistoryTurns",maximumOmitted);
        summary.put("requestsChecked",checks);summary.put("status",status);summary.put("blockReason",reason);
        summary.put("lastEstimate",latest);summary.put("estimateSchemaVersion","context-estimate-pair-v1");
        summary.put("baseEstimate",baseEstimate);ledger.contextBudget(summary);
    }
    public void throwIfBlocked(){if(failure!=null)throw failure;}
}
