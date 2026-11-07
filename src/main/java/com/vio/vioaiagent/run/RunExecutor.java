package com.vio.vioaiagent.run;

/** Agent 执行器边界；模型、工具和 VioManus 通过此接口被 Run Service 调度。 */
@FunctionalInterface
public interface RunExecutor {
    String execute(AgentRunContext context) throws Exception;
}

