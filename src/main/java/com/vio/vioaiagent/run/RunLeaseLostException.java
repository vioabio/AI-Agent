package com.vio.vioaiagent.run;

/** Safe ownership failure; lease owners, tokens and database details are never exposed. */
public final class RunLeaseLostException extends RuntimeException {
    public RunLeaseLostException() {
        super("Run 执行租约已失效，已停止当前 worker");
    }
}
