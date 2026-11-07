package com.vio.vioaiagent.run;

/** Stable overload response without principal, prompt, credentials or internal queue details. */
public final class RunAdmissionException extends RuntimeException {
    private final String code;
    private final int retryAfterSeconds;
    private final boolean unavailable;

    public RunAdmissionException(String code, int retryAfterSeconds, boolean unavailable) {
        super(switch (code) {
            case "RUN_CAPACITY_EXCEEDED" -> "当前任务容量已满，请稍后重试";
            case "RUN_PRINCIPAL_LIMIT" -> "当前账号的任务数量已达上限，请稍后重试";
            case "RUN_TENANT_LIMIT" -> "当前租户的任务数量已达上限，请稍后重试";
            case "RUN_EXECUTION_UNAVAILABLE" -> "任务执行服务暂时不可用，请稍后重试";
            default -> throw new IllegalArgumentException("Unknown admission code");
        });
        if (retryAfterSeconds < 1 || retryAfterSeconds > 3600) throw new IllegalArgumentException("Invalid Retry-After");
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
        this.unavailable = unavailable;
    }
    public String code() { return code; }
    public int retryAfterSeconds() { return retryAfterSeconds; }
    public boolean unavailable() { return unavailable; }
}
