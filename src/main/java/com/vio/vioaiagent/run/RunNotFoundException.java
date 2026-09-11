package com.vio.vioaiagent.run;

/** 对不存在或不属于当前用户的 Run 使用同一语义，避免泄漏资源存在性。 */
public class RunNotFoundException extends RuntimeException {
    public RunNotFoundException(String runId) {
        super("Run 不存在或无权访问: " + runId);
    }
}

