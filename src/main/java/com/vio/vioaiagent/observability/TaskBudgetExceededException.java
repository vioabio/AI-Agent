package com.vio.vioaiagent.observability;

public final class TaskBudgetExceededException extends RuntimeException {
    public enum Reason { CALL_LIMIT, TOKEN_LIMIT, STATE_UNAVAILABLE, ESTIMATE_UNAVAILABLE }
    private final Reason reason;

    public TaskBudgetExceededException(Reason reason) {
        super("任务模型额度不足或无法核实，请缩小任务范围后重新发起。");
        this.reason = reason;
    }

    public Reason reason() { return reason; }
    public String errorType() {
        return reason == Reason.STATE_UNAVAILABLE || reason == Reason.ESTIMATE_UNAVAILABLE
                ? "RUN_TASK_BUDGET_UNAVAILABLE" : "RUN_TASK_BUDGET_EXCEEDED";
    }
}
