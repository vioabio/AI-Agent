package com.vio.vioaiagent.observability;

import com.alibaba.cloud.ai.dashscope.chat.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/** SDK 1.0.0.2 has no writable maxTokens property. Put the cap in a per-call model's native defaults. */
public final class DashScopeBudgetChatModel implements ChatModel {
    private final DashScopeChatModel delegate;
    public DashScopeBudgetChatModel(DashScopeChatModel delegate){this.delegate=delegate;}
    @Override public ChatOptions getDefaultOptions(){return delegate.getDefaultOptions();}
    @Override public ChatResponse call(Prompt prompt){var request=adapt(prompt);return request.model.call(request.prompt);}
    @Override public Flux<ChatResponse> stream(Prompt prompt){var request=adapt(prompt);return request.model.stream(request.prompt);}
    private Request adapt(Prompt prompt) {
        Integer cap=prompt.getOptions().getMaxTokens();
        if(cap==null || cap<1)throw new ContextBudgetExceededException();
        var original=delegate.getDashScopeChatOptions();
        ChatOptions runtime;
        if(prompt.getOptions() instanceof DashScopeChatOptions nativeOptions) {
            var defaults=original;
            original=ModelOptionsUtils.merge(nativeOptions,defaults,DashScopeChatOptions.class);
            original.setToolCallbacks(ToolCallingChatOptions.mergeToolCallbacks(nativeOptions.getToolCallbacks(),defaults.getToolCallbacks()));
            original.setToolNames(ToolCallingChatOptions.mergeToolNames(nativeOptions.getToolNames(),defaults.getToolNames()));
            original.setToolContext(ToolCallingChatOptions.mergeToolContext(nativeOptions.getToolContext(),defaults.getToolContext()));
            runtime=ToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build();
        } else {
            runtime=prompt.getOptions().copy();new BeanWrapperImpl(runtime).setPropertyValue("maxTokens",null);
        }
        return new Request(delegate.mutate().defaultOptions(withMaxTokens(original,cap)).build(),new Prompt(prompt.getInstructions(),runtime));
    }
    static DashScopeChatOptions withMaxTokens(DashScopeChatOptions original,int cap) {
        return DashScopeChatOptions.builder().withModel(original.getModel()).withMaxToken(cap)
                .withTemperature(original.getTemperature()).withTopP(original.getTopP()).withTopK(original.getTopK())
                .withSeed(original.getSeed()).withStop(original.getStop()).withResponseFormat(original.getResponseFormat())
                .withStream(original.getStream()).withEnableSearch(original.getEnableSearch())
                .withIncrementalOutput(original.getIncrementalOutput()).withRepetitionPenalty(original.getRepetitionPenalty())
                .withToolCallbacks(original.getToolCallbacks()).withToolNames(original.getToolNames())
                .withInternalToolExecutionEnabled(false).withToolContext(original.getToolContext())
                .withTools(original.getTools()).withToolChoice(original.getToolChoice())
                .withMultiModel(original.getMultiModel()).withVlHighResolutionImages(original.getVlHighResolutionImages())
                .withEnableThinking(original.getEnableThinking()).build();
    }
    private record Request(DashScopeChatModel model,Prompt prompt) { }
}
