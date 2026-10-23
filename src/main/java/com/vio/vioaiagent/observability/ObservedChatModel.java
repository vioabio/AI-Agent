package com.vio.vioaiagent.observability;

import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import reactor.core.publisher.Flux;

/** Observes the ChatModel boundary, including private decisions, without modifying prompts or public output. */
public final class ObservedChatModel implements ChatModel {
    private final ChatModel delegate;
    private final ModelUsageLedger ledger;
    public ObservedChatModel(ChatModel delegate,ModelUsageLedger ledger) {
        this.delegate=java.util.Objects.requireNonNull(delegate);this.ledger=java.util.Objects.requireNonNull(ledger);ledger.attach();
    }
    @Override public ChatOptions getDefaultOptions(){return delegate.getDefaultOptions();}
    private String configuredModel(){var options=delegate.getDefaultOptions();return options==null?null:options.getModel();}
    @Override public ChatResponse call(Prompt prompt) {
        var call=ledger.begin("CALL",configuredModel());
        try {var response=delegate.call(prompt);ledger.response(call,response);ledger.finish(call,"RETURNED");return response;}
        catch(RuntimeException | Error error){ledger.finish(call,error instanceof java.util.concurrent.CancellationException?"CANCELLED":"FAILED");throw error;}
    }
    @Override public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(()->{
            var call=ledger.begin("STREAM",configuredModel());
            try {
                return delegate.stream(prompt).doOnNext(response->ledger.response(call,response))
                        .doOnComplete(()->ledger.finish(call,"RETURNED"))
                        .doOnError(error->ledger.finish(call,"FAILED"))
                        .doOnCancel(()->ledger.finish(call,"CANCELLED"));
            } catch(RuntimeException | Error error){ledger.finish(call,"FAILED");return Flux.error(error);}
        });
    }
}
