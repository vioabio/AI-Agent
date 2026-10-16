package com.vio.vioaiagent.run;

/** Fail-closed error for unavailable or invalid encrypted step output storage. */
public final class RunStepOutputException extends RuntimeException {
    public RunStepOutputException(String message) {
        super(message);
    }

    public RunStepOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
