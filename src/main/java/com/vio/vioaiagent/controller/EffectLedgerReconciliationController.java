package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.common.Result;
import com.vio.vioaiagent.feedback.FeedbackRole;
import com.vio.vioaiagent.run.EffectLedgerReconciliationService;
import com.vio.vioaiagent.run.ToolEffectLedgerEntry;
import com.vio.vioaiagent.run.ToolEffectStatus;
import com.vio.vioaiagent.security.PrincipalPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Operator-only manual confirmation of an UNKNOWN external effect. */
@RestController
@RequestMapping("/admin/effects")
public class EffectLedgerReconciliationController {
    private final EffectLedgerReconciliationService service;

    public EffectLedgerReconciliationController(EffectLedgerReconciliationService service) {
        this.service = service;
    }

    @Operation(operationId = "reconcileUnknownEffect", summary = "人工核对未知外部副作用",
            description = "Only OPERATOR may resolve an UNKNOWN effect to SUCCEEDED or FAILED. This never replays the tool.",
            security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "oidcSession"),
                    @SecurityRequirement(name = "apiKeyAuth")})
    @PostMapping("/reconcile")
    public ResponseEntity<Result<Map<String, Object>>> reconcile(@RequestBody Request body) {
        String actor = requireOperator();
        try {
            validate(body);
            ToolEffectLedgerEntry resolved = service.resolve(body.runId(), body.stepNo(), body.toolName(),
                    body.toolVersion(), body.idempotencyKey(), body.parameterHash(), body.finalStatus(),
                    body.resultDigest(), body.resultUri(), actor);
            return ResponseEntity.ok(Result.success(Map.of("runId", resolved.runId(), "status", resolved.status().name(),
                    "operatorId", actor)));
        } catch (IllegalArgumentException ex) {
            return failure(HttpStatus.BAD_REQUEST, "人工核对参数无效");
        } catch (IllegalStateException ex) {
            return failure(HttpStatus.CONFLICT, "副作用当前不可核对或核对审计不可用");
        }
    }

    private void validate(Request body) {
        if (body == null || body.stepNo() < 0 || body.finalStatus() == null
                || body.finalStatus() == ToolEffectStatus.UNKNOWN
                || body.finalStatus() == ToolEffectStatus.WAITING_APPROVAL
                || blank(body.runId()) || blank(body.toolName()) || blank(body.toolVersion())
                || blank(body.idempotencyKey()) || blank(body.parameterHash()) || blank(body.resultDigest())) {
            throw new IllegalArgumentException("核对请求字段不完整");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private String requireOperator() {
        String actor = RequestContext.getPrincipalId();
        PrincipalPolicy policy = RequestContext.getPrincipalPolicy();
        if (actor == null || actor.isBlank() || policy == null || policy.isExpired()
                || !policy.principalId().equals(actor) || !policy.allowsRole(FeedbackRole.OPERATOR.name())) {
            throw new AccessDeniedException();
        }
        return actor;
    }

    private static ResponseEntity<Result<Map<String, Object>>> failure(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Result.error(status.value(), message));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Map<String, Object>>> accessDenied() {
        return failure(HttpStatus.FORBIDDEN, "当前主体无权核对外部副作用");
    }

    public record Request(String runId, int stepNo, String toolName, String toolVersion,
                          String idempotencyKey, String parameterHash, ToolEffectStatus finalStatus,
                          String resultDigest, String resultUri) { }

    private static final class AccessDeniedException extends RuntimeException { }
}
