package com.vio.vioaiagent.run;

/** M3 Agent Run 状态机；终态不可再次进入运行态。 */
public enum AgentRunStatus {
    CREATED(false),
    RUNNING(false),
    WAITING_APPROVAL(false),
    COMPLETED(true),
    FAILED(true),
    CANCELLED(true),
    TIMED_OUT(true);

    private final boolean terminal;

    AgentRunStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public boolean canTransitionTo(AgentRunStatus target) {
        if (target == null || terminal) {
            return false;
        }
        return switch (this) {
            case CREATED -> target == RUNNING || target == FAILED || target == CANCELLED || target == TIMED_OUT;
            case RUNNING -> target == WAITING_APPROVAL || target == COMPLETED || target == FAILED
                    || target == CANCELLED || target == TIMED_OUT;
            case WAITING_APPROVAL -> target == RUNNING || target == COMPLETED || target == FAILED
                    || target == CANCELLED || target == TIMED_OUT;
            case COMPLETED, FAILED, CANCELLED, TIMED_OUT -> false;
        };
    }
}

