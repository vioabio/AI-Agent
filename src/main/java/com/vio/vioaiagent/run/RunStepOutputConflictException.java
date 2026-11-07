package com.vio.vioaiagent.run;

/** Safe immutable-output conflict; no payload, prompt or storage details are exposed. */
public final class RunStepOutputConflictException extends RuntimeException {
    private final boolean reentrant;

    public RunStepOutputConflictException() {
        this(false);
    }

    public RunStepOutputConflictException(boolean reentrant) {
        super("Run 步骤输出已存在且内容不一致");
        this.reentrant = reentrant;
    }

    public boolean reentrant() { return reentrant; }
}
