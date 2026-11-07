package com.vio.vioaiagent.feedback;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeedbackStateMachineTest {

    @Test
    void followsContractAndKeepsAuditHistory() {
        CorrectionCase value = submitted();
        value = move(value, FeedbackState.TRIAGED, FeedbackRole.OPERATOR);
        value = move(value, FeedbackState.IN_REVIEW, FeedbackRole.QUALITY);
        value = move(value, FeedbackState.APPROVED, FeedbackRole.REVIEWER);
        value = FeedbackStateMachine.transition(value, FeedbackState.PUBLISHED, "publisher-1",
                Set.of(FeedbackRole.PUBLISHER), "发布修复", "trace-4", "knowledge-v2", "golden-pass");
        value = move(value, FeedbackState.VERIFIED, FeedbackRole.QUALITY);
        value = move(value, FeedbackState.CLOSED, FeedbackRole.OPERATOR);

        assertEquals(FeedbackState.CLOSED, value.status());
        assertEquals(7, value.history().size());
        assertEquals("golden-pass", value.evaluationResult());
    }

    @Test
    void rejectsIllegalEdgeAndRole() {
        CorrectionCase initial = submitted();
        assertThrows(FeedbackStateMachine.FeedbackTransitionException.class,
                () -> FeedbackStateMachine.transition(initial, FeedbackState.APPROVED, "operator-1",
                        Set.of(FeedbackRole.OPERATOR), "跳过审核", "trace-2"));
        CorrectionCase triaged = move(initial, FeedbackState.TRIAGED, FeedbackRole.OPERATOR);
        assertThrows(FeedbackStateMachine.FeedbackTransitionException.class,
                () -> FeedbackStateMachine.transition(triaged, FeedbackState.IN_REVIEW, "trainer-1",
                        Set.of(FeedbackRole.REVIEWER), "越权", "trace-3"));
    }

    @Test
    void requiresFixAndEvaluationBeforePublishAndClosesForever() {
        CorrectionCase value = submitted();
        value = move(value, FeedbackState.TRIAGED, FeedbackRole.OPERATOR);
        value = move(value, FeedbackState.IN_REVIEW, FeedbackRole.OPERATOR);
        value = move(value, FeedbackState.APPROVED, FeedbackRole.QUALITY);
        CorrectionCase approved = value;
        assertThrows(IllegalArgumentException.class,
                () -> FeedbackStateMachine.transition(approved, FeedbackState.PUBLISHED, "pub",
                        Set.of(FeedbackRole.PUBLISHER), "缺少评测", "trace", "fix-v1", null));
        CorrectionCase inReview = move(move(submitted(), FeedbackState.TRIAGED, FeedbackRole.OPERATOR),
                FeedbackState.IN_REVIEW, FeedbackRole.OPERATOR);
        value = FeedbackStateMachine.transition(inReview, FeedbackState.REJECTED, "reviewer",
                Set.of(FeedbackRole.REVIEWER), "重新审核后拒绝", "trace-r");
        CorrectionCase rejected = value;
        assertEquals(FeedbackState.REJECTED, rejected.status());
        rejected = move(rejected, FeedbackState.CLOSED, FeedbackRole.OPERATOR);
        CorrectionCase closed = rejected;
        assertThrows(FeedbackStateMachine.FeedbackTransitionException.class,
                () -> FeedbackStateMachine.transition(closed, FeedbackState.SUBMITTED, "operator",
                        Set.of(FeedbackRole.OPERATOR), "重开", "trace-c"));
    }

    private CorrectionCase submitted() {
        return CorrectionCase.submit("fb-1", "answer-1", "trace-1", "WRONG_FACT",
                Map.of("expected", "fire"), "user-1", "data-v1", "knowledge-v1", "index-v1",
                FeedbackType.CORRECTION, FeedbackPriority.P1, null);
    }

    private CorrectionCase move(CorrectionCase value, FeedbackState target, FeedbackRole role) {
        return FeedbackStateMachine.transition(value, target, role.name().toLowerCase(), Set.of(role),
                "处理 " + target, "trace-" + target.name().toLowerCase());
    }
}
