package com.vio.vioaiagent.run;

/**
 * Run 乐观锁冲突。调用方应重新读取最新快照后决定是否重试，不能用旧快照覆盖
 * 其他实例已经提交的状态。
 */
public class RunConflictException extends IllegalStateException {
    public RunConflictException(String runId, long expectedVersion) {
        super("Run 版本冲突，拒绝覆盖旧快照: runId=" + runId + ", expectedVersion=" + expectedVersion);
    }
}
