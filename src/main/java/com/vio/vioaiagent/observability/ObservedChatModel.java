package com.vio.vioaiagent.observability;

import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import reactor.core.publisher.Flux;

/** Observes the ChatModel boundary, including private decisions, without modifying prompts or public output. */
public final class ObservedChatModel implements ChatModel {
    private final ChatModel delegate;
    private final ModelUsageLedger ledger;
    private final ModelContextBudget budget;
    public ObservedChatModel(ChatModel delegate,ModelUsageLedger ledger) {
        this(delegate,ledger,null);
    }
    public ObservedChatModel(ChatModel delegate,ModelUsageLedger ledger,ModelContextBudget budget) {
        java.util.Objects.requireNonNull(delegate);
        this.delegate=budget!=null && delegate instanceof com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel nativeModel
                ? new DashScopeBudgetChatModel(nativeModel) : delegate;
        this.ledger=java.util.Objects.requireNonNull(ledger);this.budget=budget;ledger.attach();
    }
    @Override public ChatOptions getDefaultOptions(){return delegate.getDefaultOptions();}
    private String configuredModel(){var options=delegate.getDefaultOptions();return options==null?null:options.getModel();}
    private String requestedModel(Prompt prompt){return prompt.getOptions()!=null && prompt.getOptions().getModel()!=null?prompt.getOptions().getModel():configuredModel();}
    @Override public ChatResponse call(Prompt prompt) {
        if(budget!=null)prompt=budget.prepare(prompt,delegate.getDefaultOptions());
        var call=ledger.begin("CALL",configuredModel(),prompt.getOptions()==null?null:prompt.getOptions().getMaxTokens(),requestedModel(prompt));
        try {var response=delegate.call(prompt);ledger.response(call,response);ledger.finish(call,"RETURNED");ledger.checkTaskBudget();if(budget!=null)budget.checkOutput(response);return response;}
        catch(RuntimeException | Error error){ledger.finish(call,error instanceof java.util.concurrent.CancellationException?"CANCELLED":"FAILED");throw error;}
    }
    @Override public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(()->{
            Prompt prepared=budget==null?prompt:budget.prepare(prompt,delegate.getDefaultOptions());
            var call=ledger.begin("STREAM",configuredModel(),prepared.getOptions()==null?null:prepared.getOptions().getMaxTokens(),requestedModel(prepared));
            try {
                return delegate.stream(prepared).doOnNext(response->{
                            ledger.response(call,response);
                            ledger.checkTaskBudget();
                            if(budget!=null)try {budget.checkOutput(response);}catch(ContextBudgetExceededException limit){ledger.finish(call,"RETURNED");throw limit;}
                        })
                        .doOnComplete(()->ledger.finish(call,"RETURNED"))
                        .doOnError(error->ledger.finish(call,"FAILED"))
                        .doOnCancel(()->ledger.finish(call,"CANCELLED"));
            } catch(RuntimeException | Error error){ledger.finish(call,"FAILED");return Flux.error(error);}
        });
    }
}
