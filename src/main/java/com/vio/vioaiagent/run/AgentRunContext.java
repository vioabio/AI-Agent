package com.vio.vioaiagent.run;

/** 传给执行器的受控上下文；执行器不能直接改变用户级 Run 生命周期。 */
public record AgentRunContext(AgentRun run) {
    public AgentRunContext {
        if (run == null) throw new IllegalArgumentException("run 不能为空");
    }
}

