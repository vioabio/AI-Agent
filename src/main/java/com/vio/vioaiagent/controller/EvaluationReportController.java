package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.common.Result;
import com.vio.vioaiagent.feedback.FeedbackAuthorization;
import com.vio.vioaiagent.feedback.FeedbackRole;
import com.vio.vioaiagent.rag.promotion.EvaluationReport;
import com.vio.vioaiagent.rag.promotion.EvaluationReportConflictException;
import com.vio.vioaiagent.rag.promotion.EvaluationReportRepository;
import com.vio.vioaiagent.security.PrincipalPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Set;

/** Operator-only import of an offline report. No arbitrary projection/boolean write endpoint. */
@Tag(name = "Knowledge Lifecycle")
@SecurityScheme(name = "oidcSession", type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE, paramName = "VIO_SESSION",
        description = "OIDC browser session; VIO_SESSION is the configurable default cookie name. State changes require CSRF and an allowed Origin.")
@RestController
@RequestMapping("/knowledge/evaluation-reports")
public class EvaluationReportController {
    private static final int MAX_ARTIFACT_BYTES = 1_048_576;
    private final EvaluationReportRepository reports;
    private final FeedbackAuthorization authorization;

    public EvaluationReportController(EvaluationReportRepository reports,
                                      FeedbackAuthorization authorization) {
        this.reports = reports;
        this.authorization = authorization;
    }

    @Operation(operationId = "importEvaluationReportArtifact", summary = "导入离线评测报告原文",
            description = "Global operator domain. Requires OPERATOR or QUALITY. Send the exact original UTF-8 metrics.json file/Blob without parsing or JSON reserialization, at most 1 MiB. The digest includes whitespace and line endings. A successful archival import may return passed=false and does not publish an index. The source label is not a source signature.",
            security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "oidcSession"),
                    @SecurityRequirement(name = "apiKeyAuth")},
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,
                    description = "Required when OIDC is enabled, including authenticated Bearer requests; obtain the matching CSRF cookie/token first."),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    description = "Exact original metrics.json bytes; never the returned EvaluationReport projection.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(type = "string", format = "binary"))),
            responses = {
                    @ApiResponse(responseCode = "200", description = "Verified report projection; identical bytes replay the existing report", useReturnTypeSchema = true),
                    @ApiResponse(responseCode = "400", description = "Missing headers or invalid artifact, digest, frozen rules or baseline"),
                    @ApiResponse(responseCode = "401", description = "Unauthenticated"),
                    @ApiResponse(responseCode = "403", description = "Role, Origin or CSRF denied"),
                    @ApiResponse(responseCode = "409", description = "evaluationRunId exists with different original bytes"),
                    @ApiResponse(responseCode = "413", description = "Original artifact exceeds 1 MiB"),
                    @ApiResponse(responseCode = "503", description = "Report storage or integrity verification unavailable")
            })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Result<EvaluationReport>> importReport(
            HttpServletRequest request,
            @Parameter(description = "SHA-256 of the exact original request bytes, including whitespace and line endings.",
                    schema = @Schema(pattern = "^sha256:[a-f0-9]{64}$"))
            @RequestHeader("X-Evaluation-Report-Hash") String expectedHash,
            @Parameter(description = "Non-secret provenance label; the authenticated importer is recorded by the server.",
                    schema = @Schema(minLength = 1, maxLength = 512))
            @RequestHeader("X-Evaluation-Source") String source) throws IOException {
        String actor = requireRole(Set.of(FeedbackRole.OPERATOR, FeedbackRole.QUALITY));
        // Bound the actual stream as well as Content-Length, including chunked uploads.
        byte[] artifact = request.getInputStream().readNBytes(MAX_ARTIFACT_BYTES + 1);
        if (artifact.length > MAX_ARTIFACT_BYTES) {
            return failure(HttpStatus.PAYLOAD_TOO_LARGE, "评测报告超过 1 MiB 上限");
        }
        try {
            return ResponseEntity.ok(Result.success(reports.importArtifact(
                    artifact, expectedHash, source, actor)));
        } catch (EvaluationReportConflictException ex) {
            return failure(HttpStatus.CONFLICT, "evaluationRunId 已存在且原始报告不可覆盖");
        } catch (IllegalArgumentException ex) {
            return failure(HttpStatus.BAD_REQUEST, "评测报告内容、摘要或基线不符合冻结契约");
        } catch (DataAccessException | UnsupportedOperationException ex) {
            return failure(HttpStatus.SERVICE_UNAVAILABLE, "评测报告仓储暂不可用");
        } catch (IllegalStateException ex) {
            return failure(HttpStatus.SERVICE_UNAVAILABLE, "评测报告完整性校验失败");
        }
    }

    @Operation(operationId = "getEvaluationReport", summary = "读取经校验的评测报告投影",
            description = "Global operator domain. Requires OPERATOR, QUALITY, REVIEWER or PUBLISHER. Every read verifies the stored original bytes, digest and baseline chain. Raw artifact bytes are not returned.",
            security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "oidcSession"),
                    @SecurityRequirement(name = "apiKeyAuth")},
            responses = {
                    @ApiResponse(responseCode = "200", description = "Verified report projection", useReturnTypeSchema = true),
                    @ApiResponse(responseCode = "401", description = "Unauthenticated"),
                    @ApiResponse(responseCode = "403", description = "Role denied"),
                    @ApiResponse(responseCode = "404", description = "Report not found"),
                    @ApiResponse(responseCode = "503", description = "Report read or integrity verification failed")
            })
    @GetMapping("/{evaluationRunId}")
    public ResponseEntity<Result<EvaluationReport>> findReport(@PathVariable String evaluationRunId) {
        requireRole(Set.of(FeedbackRole.OPERATOR, FeedbackRole.QUALITY,
                FeedbackRole.REVIEWER, FeedbackRole.PUBLISHER));
        try {
            return reports.findByEvaluationRunId(evaluationRunId)
                    .map(report -> ResponseEntity.ok(Result.success(report)))
                    .orElseGet(() -> failure(HttpStatus.NOT_FOUND, "评测报告不存在"));
        } catch (DataAccessException | IllegalStateException | IllegalArgumentException ex) {
            return failure(HttpStatus.SERVICE_UNAVAILABLE, "评测报告读取或完整性校验失败");
        }
    }

    private String requireRole(Set<FeedbackRole> required) {
        String actor = RequestContext.getPrincipalId();
        if (actor == null || actor.isBlank()) {
            throw new ReportAccessException(HttpStatus.UNAUTHORIZED, "缺少认证身份");
        }
        PrincipalPolicy policy = RequestContext.getPrincipalPolicy();
        Set<FeedbackRole> configured = authorization.rolesFor(actor, null);
        boolean allowed = policy == null
                ? configured.stream().anyMatch(required::contains)
                : policy.principalId().equals(actor) && !policy.isExpired()
                    && required.stream().anyMatch(role -> policy.allowsRole(role.name()));
        if (!allowed) throw new ReportAccessException(HttpStatus.FORBIDDEN, "当前主体无权操作评测报告");
        return actor;
    }

    @ExceptionHandler(ReportAccessException.class)
    public ResponseEntity<Result<EvaluationReport>> accessFailure(ReportAccessException ex) {
        return failure(ex.status, ex.getMessage());
    }

    private static ResponseEntity<Result<EvaluationReport>> failure(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Result.error(status.value(), message));
    }

    private static class ReportAccessException extends RuntimeException {
        private final HttpStatus status;
        private ReportAccessException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
    }
}
