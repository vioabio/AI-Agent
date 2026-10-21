package com.vio.vioaiagent.run;

/** 传给执行器的受控上下文；执行器不能直接改变用户级 Run 生命周期。 */
public record AgentRunContext(AgentRun run, java.util.function.Consumer<String> answerDeltaSink,
                              java.util.List<org.springframework.ai.chat.messages.Message> history) {
    public AgentRunContext(AgentRun run) { this(run, null, java.util.List.of()); }
    public AgentRunContext(AgentRun run, java.util.function.Consumer<String> sink) {
        this(run, sink, java.util.List.of());
    }
    public AgentRunContext {
        if (run == null) throw new IllegalArgumentException("run 不能为空");
        history = history == null ? java.util.List.of() : java.util.List.copyOf(history);
    }
}

