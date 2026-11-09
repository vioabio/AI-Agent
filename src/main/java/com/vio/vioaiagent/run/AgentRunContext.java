package com.vio.vioaiagent.run;

/** 传给执行器的受控上下文；执行器不能直接改变用户级 Run 生命周期。 */
public record AgentRunContext(AgentRun run, java.util.function.Consumer<String> answerDeltaSink,
                              java.util.List<org.springframework.ai.chat.messages.Message> history,
                              RunMutationBoundary mutations,com.vio.vioaiagent.observability.ModelUsageLedger modelUsage,
                              ConversationConstraints constraints,
                              java.util.List<org.springframework.ai.chat.messages.Message> dialogueHistory) {
    /** Program routing history is separate from the bounded model prompt. */
    public AgentRunContext(AgentRun run,java.util.function.Consumer<String> sink,java.util.List<org.springframework.ai.chat.messages.Message> history,
                           RunMutationBoundary mutations,com.vio.vioaiagent.observability.ModelUsageLedger modelUsage,
                           ConversationConstraints constraints) {
        this(run,sink,history,mutations,modelUsage,constraints,history);
    }
    public AgentRunContext(AgentRun run,java.util.function.Consumer<String> sink,java.util.List<org.springframework.ai.chat.messages.Message> history,
                           RunMutationBoundary mutations,com.vio.vioaiagent.observability.ModelUsageLedger modelUsage) {
        this(run,sink,history,mutations,modelUsage,ConversationConstraints.defaults());
    }
    public AgentRunContext(AgentRun run,java.util.function.Consumer<String> sink,java.util.List<org.springframework.ai.chat.messages.Message> history,RunMutationBoundary mutations) {
        this(run,sink,history,mutations,new com.vio.vioaiagent.observability.ModelUsageLedger());
    }
    public AgentRunContext(AgentRun run, java.util.function.Consumer<String> sink, java.util.List<org.springframework.ai.chat.messages.Message> history) {
        this(run,sink,history,RunMutationBoundary.denied());
    }
    public AgentRunContext(AgentRun run) { this(run, null, java.util.List.of()); }
    public AgentRunContext(AgentRun run, java.util.function.Consumer<String> sink) {
        this(run, sink, java.util.List.of());
    }
    public AgentRunContext {
        if (run == null) throw new IllegalArgumentException("run 不能为空");
        history = history == null ? java.util.List.of() : java.util.List.copyOf(history);
        dialogueHistory = dialogueHistory == null ? java.util.List.of() : java.util.List.copyOf(dialogueHistory);
        mutations = mutations == null ? RunMutationBoundary.denied() : mutations;
        modelUsage = modelUsage == null ? new com.vio.vioaiagent.observability.ModelUsageLedger() : modelUsage;
        constraints = constraints == null ? ConversationConstraints.defaults() : constraints;
    }
}

