package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.feedback.CorrectionCase;
import com.vio.vioaiagent.feedback.FeedbackPriority;
import com.vio.vioaiagent.feedback.FeedbackRole;
import com.vio.vioaiagent.feedback.FeedbackService;
import com.vio.vioaiagent.feedback.FeedbackState;
import com.vio.vioaiagent.feedback.FeedbackTransitionCommand;
import com.vio.vioaiagent.feedback.FeedbackType;
import com.vio.vioaiagent.feedback.FeedbackView;
import com.vio.vioaiagent.feedback.FeedbackNotFoundException;
import com.vio.vioaiagent.feedback.FeedbackConflictException;
import com.vio.vioaiagent.feedback.SubmitFeedbackCommand;
import com.vio.vioaiagent.feedback.FeedbackStateMachine.FeedbackTransitionException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** M6 反馈 API：用户提交反馈，运营角色推动纠错工单状态。 */
@Tag(name = "反馈与纠错", description = "用户反馈、运营审核和发布前纠错状态机")
@RestController
@RequestMapping
public class FeedbackController {
    private final FeedbackService service;
    private final SecurityProperties securityProperties;

    public FeedbackController(FeedbackService service, SecurityProperties securityProperties) {
        this.service = service;
        this.securityProperties = securityProperties;
    }

    @Operation(summary = "提交答案反馈")
    @PostMapping("/answers/{answerId}/feedback")
    public ResponseEntity<FeedbackView> submit(
            @PathVariable String answerId,
            @RequestBody(required = false) SubmitFeedbackRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader,
            @RequestHeader(value = "X-Answer-Trace-Id", required = false) String answerTraceHeader) {
        SubmitFeedbackRequest body = request == null ? new SubmitFeedbackRequest() : request;
        String userId = authenticatedUser(userHeader, body.userId);
        // 当前 HTTP 请求的 X-Trace-Id 是“提交反馈”这次调用的 trace，不能
        // 冒充原答案 trace。答案 trace 仅接受显式字段，随后仍由投影校验/覆盖。
        String traceId = firstNonBlank(answerTraceHeader, body.traceId, null);
        SubmitFeedbackCommand command = new SubmitFeedbackCommand(answerId, traceId, body.errorType,
                body.evidence, userId, body.dataReleaseId, body.knowledgeReleaseId, body.indexReleaseId,
                FeedbackType.parse(body.feedbackType), FeedbackPriority.parse(body.priority),
                body.feedbackId, body.createdAt);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.submit(command));
    }

    @Operation(summary = "查询自己的反馈")
    @GetMapping("/answers/{answerId}/feedback")
    public List<FeedbackView> ownFeedback(
            @PathVariable String answerId,
        @RequestHeader(value = "X-User-Id", required = false) String userHeader,
            @RequestParam(defaultValue = "50") int limit) {
        String userId = authenticatedUser(userHeader, null);
        return service.findForUserAndAnswer(userId, answerId, limit);
    }

    @Operation(summary = "查询纠错工单（运营）")
    @GetMapping("/admin/corrections")
    public List<FeedbackView> corrections(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader,
            @RequestHeader(value = "X-Role", required = false) String roleHeader) {
        String actorId = authenticatedUser(userHeader, null);
        requireOperator(actorId, roleHeader);
        return service.findAll(status == null || status.isBlank() ? null : FeedbackState.parse(status), limit);
    }

    @Operation(summary = "推进纠错工单状态")
    @PatchMapping("/admin/corrections/{feedbackId}")
    public FeedbackView transition(
            @PathVariable String feedbackId,
            @RequestBody TransitionRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader,
            @RequestHeader(value = "X-Role", required = false) String roleHeader,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceHeader) {
        if (request == null) throw new IllegalArgumentException("迁移请求不能为空");
        String actorId = authenticatedUser(userHeader, request.actorId);
        Set<FeedbackRole> roles = service.resolveRoles(actorId, roleHeader);
        if (request.role != null && !request.role.isBlank()) {
            FeedbackRole requested = FeedbackRole.parse(request.role);
            roles = roles.contains(requested) ? Set.of(requested) : Set.of();
        }
        String traceId = firstNonBlank(traceHeader, request.traceId, RequestContext.getTraceId(), null);
        FeedbackTransitionCommand command = new FeedbackTransitionCommand(feedbackId,
                FeedbackState.parse(request.target), actorId, roles, request.reason, traceId,
                request.fixReleaseId, request.evaluationResult);
        return service.transition(command);
    }

    private void requireOperator(String actorId, String roleHeader) {
        if (service.resolveRoles(actorId, roleHeader).isEmpty()) {
            throw new FeedbackTransitionException("当前主体不是反馈运营角色", 403);
        }
    }

    private String authenticatedUser(String header, String bodyUser) {
        String principal = RequestContext.getPrincipalId();
        if (principal != null && !principal.isBlank()) return principal;
        Long authenticated = RequestContext.getUserId();
        if (authenticated != null) return String.valueOf(authenticated);
        if (securityProperties.isEnabled()) {
            throw new FeedbackService.FeedbackOperationException("缺少认证身份", 401);
        }
        return firstNonBlank(header, bodyUser, "anonymous");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    @ExceptionHandler(FeedbackNotFoundException.class)
    public ResponseEntity<ErrorBody> notFound(FeedbackNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody(404, ex.getMessage()));
    }

    @ExceptionHandler(FeedbackTransitionException.class)
    public ResponseEntity<ErrorBody> transitionError(FeedbackTransitionException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorBody(ex.status(), ex.getMessage()));
    }

    @ExceptionHandler(FeedbackService.FeedbackOperationException.class)
    public ResponseEntity<ErrorBody> operationError(FeedbackService.FeedbackOperationException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorBody(ex.status(), ex.getMessage()));
    }

    @ExceptionHandler(FeedbackConflictException.class)
    public ResponseEntity<ErrorBody> conflict(FeedbackConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorBody(HttpStatus.CONFLICT.value(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ErrorBody(400, ex.getMessage()));
    }

    public record ErrorBody(int code, String message) {
    }

    /** HTTP 输入 DTO；身份字段只在本地安全关闭时作为测试兜底。 */
    public static class SubmitFeedbackRequest {
        public String feedbackId;
        public String userId;
        public String traceId;
        public String errorType = "GENERAL";
        public Map<String, Object> evidence = Map.of();
        public String dataReleaseId;
        public String knowledgeReleaseId;
        public String indexReleaseId;
        public String feedbackType = "CORRECTION";
        public String priority = "P2";
        public Instant createdAt;
    }

    public static class TransitionRequest {
        public String target;
        public String actorId;
        public String role;
        public String reason;
        public String traceId;
        public String fixReleaseId;
        public String evaluationResult;
    }
}
