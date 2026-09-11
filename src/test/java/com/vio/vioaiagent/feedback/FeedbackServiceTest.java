package com.vio.vioaiagent.feedback;

import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.observability.AgentMetrics;
import com.vio.vioaiagent.run.AnswerProjection;
import com.vio.vioaiagent.run.InMemoryAnswerProjectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeedbackServiceTest {
    private final InMemoryFeedbackRepository repository = new InMemoryFeedbackRepository();
    private final FeedbackProperties properties = new FeedbackProperties();
    private final FeedbackAuthorization authorization = new FeedbackAuthorization(properties,
            new SecurityProperties(false, Map.of(), List.of(), List.of()));
    private final AgentMetrics metrics = new AgentMetrics();
    private final FeedbackService service = new FeedbackService(repository, metrics, authorization);

    @AfterEach
    void resetContext() {
        com.vio.vioaiagent.common.RequestContext.clear();
    }

    @Test
    void userCanSubmitAndOnlyReadOwnFeedback() {
        FeedbackView created = service.submit(new SubmitFeedbackCommand("answer-1", "trace-1", "WRONG_FACT",
                Map.of("quote", "expected"), "user-1", "data-v1", "knowledge-v1", "index-v1",
                FeedbackType.CORRECTION, FeedbackPriority.P2, "fb-service-1", null));
        assertEquals(FeedbackState.SUBMITTED, created.status());
        assertEquals("fb-service-1", service.findForUser("fb-service-1", "user-1").feedbackId());
        assertThrows(FeedbackNotFoundException.class,
                () -> service.findForUser("fb-service-1", "other-user"));
    }

    @Test
    void localRoleHeaderCanDriveWorkflowButCannotSkipState() {
        FeedbackView created = service.submit(new SubmitFeedbackCommand("answer-2", "trace-2", "GENERAL",
                Map.of(), "user-2", null, null, null, FeedbackType.NOT_HELPFUL, FeedbackPriority.P3, null, null));
        Set<FeedbackRole> roles = service.resolveRoles("operator-1", "operator");
        FeedbackView triaged = service.transition(new FeedbackTransitionCommand(created.feedbackId(),
                FeedbackState.TRIAGED, "operator-1", roles, "自动分级", "trace-3", null, null));
        assertEquals(FeedbackState.TRIAGED, triaged.status());
        assertThrows(FeedbackStateMachine.FeedbackTransitionException.class,
                () -> service.transition(new FeedbackTransitionCommand(created.feedbackId(),
                        FeedbackState.APPROVED, "operator-1", roles, "越级", "trace-4", null, null)));
    }

    @Test
    void explicitRoleBindingOverridesCallerSuppliedRoles() {
        properties.setOperatorRoles(Map.of("operator-2", List.of("operator")));
        FeedbackView created = service.submit(new SubmitFeedbackCommand("answer-3", "trace-5", "GENERAL",
                Map.of(), "user-3", null, null, null, FeedbackType.CORRECTION, FeedbackPriority.P2, null, null));

        // 即使内部调用方伪造 reviewer 角色，服务边界也必须按 actor 的绑定拒绝。
        assertThrows(FeedbackStateMachine.FeedbackTransitionException.class,
                () -> service.transition(new FeedbackTransitionCommand(created.feedbackId(),
                        FeedbackState.APPROVED, "operator-2", Set.of(FeedbackRole.REVIEWER),
                        "伪造角色", "trace-6", null, null)));

        FeedbackView triaged = service.transition(new FeedbackTransitionCommand(created.feedbackId(),
                FeedbackState.TRIAGED, "operator-2", Set.of(FeedbackRole.REVIEWER),
                "按绑定分级", "trace-7", null, null));
        assertEquals(FeedbackState.TRIAGED, triaged.status());
    }

    @Test
    void answerFilterIsAppliedBeforeLimit() {
        service.submit(new SubmitFeedbackCommand("other-answer-1", "trace-a", "GENERAL",
                Map.of(), "user-page", null, null, null, FeedbackType.CORRECTION,
                FeedbackPriority.P3, "fb-page-1", null));
        service.submit(new SubmitFeedbackCommand("other-answer-2", "trace-b", "GENERAL",
                Map.of(), "user-page", null, null, null, FeedbackType.CORRECTION,
                FeedbackPriority.P3, "fb-page-2", null));
        service.submit(new SubmitFeedbackCommand("target-answer", "trace-c", "GENERAL",
                Map.of(), "user-page", null, null, null, FeedbackType.CORRECTION,
                FeedbackPriority.P3, "fb-page-3", null));

        // 即使 limit=1，仓储也应先按 answerId 过滤后再截断结果。
        List<FeedbackView> result = service.findForUserAndAnswer("user-page", "target-answer", 1);
        assertEquals(1, result.size());
        assertEquals("fb-page-3", result.get(0).feedbackId());
    }

    @Test
    void injectedProjectionIsRequiredAndCanonicalizesReleaseLineage() {
        InMemoryAnswerProjectionRepository projections = new InMemoryAnswerProjectionRepository();
        projections.save(AnswerProjection.of("answer-lineage", "run-lineage", "session-lineage",
                "conversation-lineage", "user-lineage", "trace-canonical", "ANSWERED", "答案",
                "sv-1", "singles", "rules-1", "data-canonical", "knowledge-canonical", "index-canonical"));
        FeedbackService lineageService = new FeedbackService(new InMemoryFeedbackRepository(), metrics, authorization);
        lineageService.setAnswerProjectionRepository(projections);

        FeedbackView value = lineageService.submit(new SubmitFeedbackCommand("answer-lineage", "trace-unknown",
                "WRONG_FACT", Map.of(), "user-lineage", null, null, null,
                FeedbackType.CORRECTION, FeedbackPriority.P2, "fb-lineage", Instant.now()));
        assertEquals("trace-canonical", value.traceId());
        assertEquals("data-canonical", value.dataReleaseId());
        assertEquals("knowledge-canonical", value.knowledgeReleaseId());
        assertEquals("index-canonical", value.indexReleaseId());
    }

    @Test
    void injectedProjectionRejectsUnknownOrCrossUserAnswer() {
        InMemoryAnswerProjectionRepository projections = new InMemoryAnswerProjectionRepository();
        projections.save(AnswerProjection.of("answer-partial", "run-partial", "session-partial",
                "conversation-partial", "user-lineage", "trace-1", "ANSWERED", "答案",
                "sv-1", "singles", "rules-1", null, null, null));
        FeedbackService lineageService = new FeedbackService(new InMemoryFeedbackRepository(), metrics, authorization);
        lineageService.setAnswerProjectionRepository(projections);

        assertThrows(FeedbackService.FeedbackOperationException.class,
                () -> lineageService.submit(new SubmitFeedbackCommand("answer-partial", "trace-1", "GENERAL",
                        Map.of(), "user-lineage", null, null, null, FeedbackType.CORRECTION,
                        FeedbackPriority.P2, "fb-partial", null)));
        assertThrows(FeedbackService.FeedbackOperationException.class,
                () -> lineageService.submit(new SubmitFeedbackCommand("answer-partial", "trace-1", "GENERAL",
                        Map.of(), "other-user", null, null, null, FeedbackType.CORRECTION,
                        FeedbackPriority.P2, "fb-cross-user", null)));
    }

    @Test
    void legacySubmissionWithoutAnswerTraceIsRejectedInsteadOfWritingUnknown() {
        FeedbackService.FeedbackOperationException error = assertThrows(
                FeedbackService.FeedbackOperationException.class,
                () -> service.submit(new SubmitFeedbackCommand("answer-no-trace", null, "GENERAL",
                        Map.of(), "user-no-trace", null, null, null, FeedbackType.CORRECTION,
                        FeedbackPriority.P2, "fb-no-trace", null)));
        assertEquals(422, error.status());
    }
}
