package com.vio.vioaiagent.run;

/**
 * 启动恢复的 fail-closed 策略。
 *
 * <p>Checkpoint 只记录执行位置，不等于已经保存了模型响应或外部副作用
 * 的结果。当前 Run 执行器没有统一的 checkpoint-aware replay 接口，因此
 * 只有最初的 {@code dispatch} 检查点可以安全地重新提交。任何已经进入
 * {@code execute-answer}、{@code commit-answer} 或未知动作的检查点都必须
 * 停止并留下可审计的失败事实，避免重启后重复计费、工具写入或 MCP 调用。</p>
 */
public final class RunRecoveryPolicy {

    public Decision decide(AgentCheckpoint checkpoint) {
        if (checkpoint == null) {
            return Decision.fail("CHECKPOINT_MISSING");
        }
        if (checkpoint.stepNo() == 0 && "dispatch".equals(checkpoint.nextAction())) {
            return Decision.resume("DISPATCH_NOT_STARTED");
        }
        return Decision.fail("CHECKPOINT_AFTER_NON_REPLAYABLE_STEP");
    }

    public enum Action {
        RESUME_DISPATCH,
        FAIL_CLOSED
    }

    public record Decision(Action action, String reason) {
        public Decision {
            if (action == null) throw new IllegalArgumentException("恢复动作不能为空");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("恢复原因不能为空");
        }

        public static Decision resume(String reason) {
            return new Decision(Action.RESUME_DISPATCH, reason);
        }

        public static Decision fail(String reason) {
            return new Decision(Action.FAIL_CLOSED, reason);
        }

        public boolean canResume() {
            return action == Action.RESUME_DISPATCH;
        }
    }
}
