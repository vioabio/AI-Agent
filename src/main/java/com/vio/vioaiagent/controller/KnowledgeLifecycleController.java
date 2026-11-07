package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.Result;
import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.feedback.FeedbackAuthorization;
import com.vio.vioaiagent.feedback.FeedbackRole;
import com.vio.vioaiagent.rag.domain.DocumentVersion;
import com.vio.vioaiagent.rag.domain.KnowledgeDocument;
import com.vio.vioaiagent.rag.domain.KnowledgeRelease;
import com.vio.vioaiagent.rag.domain.KnowledgeSource;
import com.vio.vioaiagent.rag.domain.VectorIndexRelease;
import com.vio.vioaiagent.rag.domain.IngestionBatch;
import com.vio.vioaiagent.rag.ingestion.KnowledgeIngestionRequest;
import com.vio.vioaiagent.rag.ingestion.KnowledgeIngestionResult;
import com.vio.vioaiagent.rag.ingestion.KnowledgeIngestionAttempt;
import com.vio.vioaiagent.rag.ingestion.KnowledgeIngestionDlqEntry;
import com.vio.vioaiagent.rag.ingestion.KnowledgeIngestionJob;
import com.vio.vioaiagent.rag.ingestion.KnowledgeIngestionJobRepository;
import com.vio.vioaiagent.rag.ingestion.KnowledgeIngestionJobService;
import com.vio.vioaiagent.rag.ingestion.KnowledgeIngestionConflictException;
import com.vio.vioaiagent.rag.ingestion.KnowledgeIngestionNotFoundException;
import com.vio.vioaiagent.rag.ingestion.KnowledgeLifecycleService;
import com.vio.vioaiagent.rag.promotion.ReleasePromotionRequest;
import com.vio.vioaiagent.rag.promotion.KnowledgePromotionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * M2 显式知识生命周期 API。所有写操作均由调用方触发，不在启动阶段扫描或建索引。
 * 生产 profile 通过 SecurityConfig 的 /knowledge/** API-Key 拦截器保护。
 */
@Tag(name = "知识生命周期")
@RestController
@RequestMapping("/knowledge")
public class KnowledgeLifecycleController {

    private final KnowledgeLifecycleService lifecycle;
    private final Path stagingRoot;
    private final SecurityProperties securityProperties;
    private final FeedbackAuthorization authorization;

    /** v1.1 异步摄取服务；在旧版程序化测试构造器中可以为空。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private KnowledgeIngestionJobService ingestionJobService;

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeLifecycleController(KnowledgeLifecycleService lifecycle,
                                        @Value("${vio.rag.ingestion.staging-root:${user.dir}/staging}") String stagingRoot,
                                        SecurityProperties securityProperties,
                                        FeedbackAuthorization authorization) {
        this.lifecycle = lifecycle;
        this.stagingRoot = Paths.get(stagingRoot).toAbsolutePath().normalize();
        this.securityProperties = securityProperties == null ? SecurityProperties.defaults() : securityProperties;
        this.authorization = authorization;
    }

    /** 程序化/离线测试构造器；本地未启用安全时保留直接调用能力。 */
    public KnowledgeLifecycleController(KnowledgeLifecycleService lifecycle, String stagingRoot) {
        this(lifecycle, stagingRoot, SecurityProperties.defaults(), null);
    }

    @Operation(summary = "提交显式知识摄取任务")
    @PostMapping("/ingestions")
    public ResponseEntity<Result<KnowledgeIngestionResult>> ingest(
            @RequestBody IngestBody body,
            @RequestHeader(value = "X-Role", required = false) String roleHeader,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        requireRole("knowledge.ingest", FeedbackRole.OPERATOR, FeedbackRole.REVIEWER,
                roleHeader, userHeader);
        if (body == null || body.source == null || body.document == null || body.stagedFile == null) {
            return badRequest("source、document、stagedFile 不能为空");
        }
        Path staged = stagingRoot.resolve(body.stagedFile).normalize();
        if (!staged.startsWith(stagingRoot) || !Files.isRegularFile(staged)
                || !isSafeStagedPath(staged)) {
            return badRequest("stagedFile 必须是 staging-root 下已上传的普通文件");
        }
        try {
            KnowledgeSource source = body.source.toDomain();
            KnowledgeDocument document = body.document.toDomain(source.id());
            KnowledgeIngestionRequest request = new KnowledgeIngestionRequest(staged, source, document,
                    body.versionNo, body.parserVersion, body.schemaVersion, body.contentVersion,
                    body.idempotencyKey, body.chunkSize, body.overlap);
            return ResponseEntity.accepted().body(Result.success(lifecycle.ingest(request)));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }
    }

    /**
     * v1.1 异步摄取入口。提交只创建持久化 Job，不在 HTTP 请求线程执行解析；
     * worker 通过 JobService 显式领取。旧的 /ingestions 同步契约保留用于兼容。
     */
    @Operation(summary = "提交持久化知识摄取 Job")
    @PostMapping("/ingestion-jobs")
    public ResponseEntity<Result<IngestionJobView>> submitIngestionJob(
            @RequestBody IngestJobBody body,
            @RequestHeader(value = "X-Role", required = false) String roleHeader,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        requireRole("knowledge.ingest", FeedbackRole.OPERATOR, FeedbackRole.REVIEWER, roleHeader, userHeader);
        if (ingestionJobService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Result.error(HttpStatus.SERVICE_UNAVAILABLE.value(), "持久化摄取 Job 服务未启用"));
        }
        if (body == null || body.source == null || body.document == null || body.stagedFile == null
                || body.targetKnowledgeReleaseId == null || body.idempotencyKey == null) {
            return badRequest("source、document、stagedFile、targetKnowledgeReleaseId、idempotencyKey 不能为空");
        }
        Path staged = stagingRoot.resolve(body.stagedFile).normalize();
        if (!staged.startsWith(stagingRoot) || !Files.isRegularFile(staged) || !isSafeStagedPath(staged)) {
            return badRequest("stagedFile 必须是 staging-root 下已上传的普通文件");
        }
        try {
            KnowledgeSource source = body.source.toDomain();
            KnowledgeDocument document = body.document.toDomain(source.id());
            KnowledgeIngestionRequest request = new KnowledgeIngestionRequest(staged, source, document,
                    body.versionNo, body.parserVersion, body.schemaVersion, body.contentVersion,
                    body.idempotencyKey, body.chunkSize, body.overlap);
            KnowledgeIngestionJobRepository.ReservationResult reservation = ingestionJobService.submit(request,
                    body.targetKnowledgeReleaseId, body.ingestionContractVersion,
                    body.idempotencyKey, body.maxAttempts);
            return ResponseEntity.accepted().body(Result.success(IngestionJobView.from(reservation.job(), reservation.created())));
        } catch (KnowledgeIngestionConflictException ex) {
            return conflict(ex.getMessage());
        } catch (KnowledgeIngestionNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (DataAccessException ex) {
            return serviceUnavailable();
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (RuntimeException ex) {
            return internalFailure();
        }
    }

    @Operation(summary = "查询持久化摄取 Job")
    @GetMapping("/ingestion-jobs/{jobId}")
    public ResponseEntity<Result<IngestionJobView>> ingestionJobStatus(
            @PathVariable String jobId,
            @RequestHeader(value = "X-Role", required = false) String roleHeader,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        requireRole("knowledge.status", FeedbackRole.OPERATOR, FeedbackRole.REVIEWER, roleHeader, userHeader);
        if (ingestionJobService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Result.error(HttpStatus.SERVICE_UNAVAILABLE.value(), "持久化摄取 Job 服务未启用"));
        }
        return ingestionJobService.findJob(jobId)
                .map(job -> ResponseEntity.ok(Result.success(IngestionJobView.from(job, false))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Result.error(HttpStatus.NOT_FOUND.value(), "找不到 ingestion job: " + jobId)));
    }

    @Operation(summary = "查询摄取 Job attempts")
    @GetMapping("/ingestion-jobs/{jobId}/attempts")
    public ResponseEntity<Result<java.util.List<KnowledgeIngestionAttempt>>> ingestionJobAttempts(
            @PathVariable String jobId,
            @RequestHeader(value = "X-Role", required = false) String roleHeader,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        requireRole("knowledge.status", FeedbackRole.OPERATOR, FeedbackRole.REVIEWER, roleHeader, userHeader);
        if (ingestionJobService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Result.error(HttpStatus.SERVICE_UNAVAILABLE.value(), "持久化摄取 Job 服务未启用"));
        }
        if (ingestionJobService.findJob(jobId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Result.error(HttpStatus.NOT_FOUND.value(), "找不到 ingestion job: " + jobId));
        }
        return ResponseEntity.ok(Result.success(ingestionJobService.findAttempts(jobId)));
    }

    @Operation(summary = "重放摄取 DLQ")
    @PostMapping("/ingestion-dlq/{dlqId}/replay")
    public ResponseEntity<Result<IngestionJobView>> replayIngestionDlq(
            @PathVariable String dlqId, @RequestBody ReplayBody body,
            @RequestHeader(value = "X-Role", required = false) String roleHeader,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        requireRole("knowledge.replay", FeedbackRole.OPERATOR, FeedbackRole.QUALITY, roleHeader, userHeader);
        if (ingestionJobService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Result.error(HttpStatus.SERVICE_UNAVAILABLE.value(), "持久化摄取 Job 服务未启用"));
        }
        if (body == null || body.reason == null || body.newIdempotencyKey == null) {
            return badRequest("reason 和 newIdempotencyKey 不能为空");
        }
        try {
            String operator = auditActor(userHeader, null);
            KnowledgeIngestionJob replay = ingestionJobService.replayDlq(dlqId, operator, body.reason,
                    body.newIdempotencyKey);
            return ResponseEntity.accepted().body(Result.success(IngestionJobView.from(replay, true)));
        } catch (KnowledgeIngestionConflictException ex) {
            return conflict(ex.getMessage());
        } catch (KnowledgeIngestionNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (DataAccessException ex) {
            return serviceUnavailable();
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (RuntimeException ex) {
            return internalFailure();
        }
    }

    @Operation(summary = "审核文档版本")
    @PostMapping("/versions/{versionId}/review")
    public ResponseEntity<Result<DocumentVersion>> review(@PathVariable String versionId,
                                                          @RequestBody ReviewBody body,
                                                          @RequestHeader(value = "X-Role", required = false) String roleHeader,
                                                          @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        requireRole("knowledge.review", FeedbackRole.REVIEWER, FeedbackRole.QUALITY, roleHeader, userHeader);
        if (body == null || body.status == null) return badRequest("status 不能为空");
        try {
            DocumentVersion.ReviewStatus status = DocumentVersion.ReviewStatus.valueOf(
                    body.status.trim().toUpperCase(Locale.ROOT));
            // 生产审计身份必须来自认证上下文，不能让请求体伪造 reviewer。
            String reviewer = auditActor(userHeader, body.reviewer);
            return ResponseEntity.ok(Result.success(lifecycle.review(versionId, status, reviewer, body.reason)));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return badRequest(ex.getMessage());
        }
    }

    @Operation(summary = "查询摄取批次状态")
    @GetMapping("/ingestions/{batchId}")
    public ResponseEntity<Result<IngestionBatch>> ingestionStatus(
            @PathVariable String batchId,
            @RequestHeader(value = "X-Role", required = false) String roleHeader,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        // 批次 ID 虽然不可预测，但状态中包含来源、错误和版本信息，不能作为匿名
        // 探测接口；生产调用方至少需要运营/审核角色。
        requireRole("knowledge.status", FeedbackRole.OPERATOR, FeedbackRole.REVIEWER,
                roleHeader, userHeader);
        return lifecycle.findBatch(batchId)
                .map(batch -> ResponseEntity.ok(Result.success(batch)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Result.error(HttpStatus.NOT_FOUND.value(), "找不到 ingestion_batch: " + batchId)));
    }

    @Operation(summary = "创建候选知识发布")
    @PostMapping("/releases")
    public ResponseEntity<Result<KnowledgeRelease>> createRelease(@RequestBody ReleaseBody body,
                                                                  @RequestHeader(value = "X-Role", required = false) String roleHeader,
                                                                  @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        requireRole("knowledge.release", FeedbackRole.PUBLISHER, FeedbackRole.QUALITY, roleHeader, userHeader);
        if (body == null) return badRequest("请求不能为空");
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(lifecycle.createCandidateRelease(
                    body.id, body.releaseNo, body.parserVersion, body.embeddingModel, body.dataReleaseId)));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return badRequest(ex.getMessage());
        }
    }

    @Operation(summary = "创建候选向量索引")
    @PostMapping("/indexes")
    public ResponseEntity<Result<VectorIndexRelease>> createIndex(@RequestBody IndexBody body,
                                                                  @RequestHeader(value = "X-Role", required = false) String roleHeader,
                                                                  @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        requireRole("knowledge.index", FeedbackRole.PUBLISHER, FeedbackRole.QUALITY, roleHeader, userHeader);
        if (body == null) return badRequest("请求不能为空");
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(lifecycle.createCandidateIndex(
                    body.id, body.knowledgeReleaseId, body.vectorStore, body.embeddingModel, body.dimension)));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return badRequest(ex.getMessage());
        }
    }

    @Operation(summary = "发布候选索引并切换 active 指针")
    @PostMapping("/indexes/{indexReleaseId}/publish")
    public ResponseEntity<Result<String>> publish(@PathVariable String indexReleaseId,
                                                  @RequestHeader(value = "X-Role", required = false) String roleHeader,
                                                  @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        requireRole("knowledge.publish", FeedbackRole.PUBLISHER, FeedbackRole.QUALITY, roleHeader, userHeader);
        try {
            lifecycle.publishIndex(indexReleaseId);
            return ResponseEntity.ok(Result.success("published"));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return badRequest(ex.getMessage());
        }
    }

    /**
     * N1b 受保护发布入口。旧的 /publish 保留用于兼容，但 Spring 装配 promotion
     * 服务后会 fail-closed；新客户端必须提交完整评测/血缘/审批上下文。
     */
    @Operation(summary = "通过 N1b 质量门禁发布候选索引")
    @PostMapping("/indexes/{indexReleaseId}/promote")
    public ResponseEntity<Result<KnowledgePromotionService.PromotionResult>> promote(
            @PathVariable String indexReleaseId,
            @RequestBody PromotionBody body,
            @RequestHeader(value = "X-Role", required = false) String roleHeader,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        requireRole("knowledge.publish", FeedbackRole.PUBLISHER, FeedbackRole.QUALITY, roleHeader, userHeader);
        if (body == null) return badRequest("promotion 请求不能为空");
        if (body.indexReleaseId != null && !body.indexReleaseId.isBlank()
                && !indexReleaseId.equals(body.indexReleaseId.trim())) {
            return badRequest("路径 indexReleaseId 与请求体不一致");
        }
        try {
            ReleasePromotionRequest request = new ReleasePromotionRequest(indexReleaseId,
                    body.knowledgeReleaseId, body.dataReleaseId, body.evaluationRunId,
                    body.reportHash, body.reviewer, body.approvalReason, body.requestedAt);
            return ResponseEntity.ok(Result.success(lifecycle.publishIndex(request)));
        } catch (KnowledgePromotionService.PromotionRejectedException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(HttpStatus.CONFLICT.value(), safeClientMessage(ex.getMessage(), "promotion 被拒绝")));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return badRequest(ex.getMessage());
        }
    }

    private <T> ResponseEntity<Result<T>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Result.error(HttpStatus.BAD_REQUEST.value(),
                safeClientMessage(message, "请求不合法")));
    }

    private <T> ResponseEntity<Result<T>> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.error(HttpStatus.CONFLICT.value(),
                safeClientMessage(message, "请求与当前资源状态冲突")));
    }

    private <T> ResponseEntity<Result<T>> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(HttpStatus.NOT_FOUND.value(),
                safeClientMessage(message, "资源不存在")));
    }

    private <T> ResponseEntity<Result<T>> serviceUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.error(HttpStatus.SERVICE_UNAVAILABLE.value(), "依赖服务暂不可用"));
    }

    private <T> ResponseEntity<Result<T>> internalFailure() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务内部错误"));
    }

    /**
     * Validation details are useful to callers, but exception text can contain
     * a local staging path, JDBC URL, or credential.  Keep a short, redacted
     * message for 4xx responses and use a category-level message otherwise.
     */
    private String safeClientMessage(String message, String fallback) {
        if (message == null || message.isBlank()) return fallback;
        String value = message.trim();
        if (value.matches("(?is).*?(?:authorization|bearer|token|secret|password|api[-_]?key)\\s*[:=].*")) {
            return fallback;
        }
        if (value.matches("(?is).*?(?:jdbc:|[A-Za-z]:\\\\|\\\\\\\\|/(?:etc|var|home|tmp)/).*")) {
            return fallback;
        }
        if (value.length() > 160) value = value.substring(0, 160) + "…";
        return value;
    }

    private void requireRole(String operation, FeedbackRole first, FeedbackRole second,
                             String roleHeader, String userHeader) {
        // 本地默认配置用于开发/离线演示；没有显式角色头时不阻断生命周期
        // 代码测试。生产 security.enabled=true 时必须经过配置的主体角色映射。
        if (!securityProperties.isEnabled() && (roleHeader == null || roleHeader.isBlank())) {
            return;
        }
        String principal = RequestContext.getPrincipalId();
        Long authenticatedUser = RequestContext.getUserId();
        if (securityProperties.isEnabled()
                && (principal == null || principal.isBlank()) && authenticatedUser == null) {
            // 生产请求必须先经过认证拦截器；绝不把可伪造的 X-User-Id 当作主体。
            throw new KnowledgeAuthorizationException("缺少认证身份", 401);
        }
        String actorId = firstNonBlank(principal,
                authenticatedUser == null ? null : String.valueOf(authenticatedUser),
                securityProperties.isEnabled() ? null : userHeader, "anonymous");
        Set<FeedbackRole> roles = authorization == null
                ? parseLocalRolesOrFailClosed(roleHeader)
                : authorization.rolesFor(actorId, roleHeader);
        if (roles.stream().noneMatch(Set.of(first, second)::contains)) {
            throw new KnowledgeAuthorizationException(operation + " 需要运营角色", 403);
        }
    }

    private Set<FeedbackRole> parseLocalRolesOrFailClosed(String header) {
        // Spring 生产上下文必须注入 FeedbackAuthorization；若缺失，不能退回到
        // 可伪造的 X-Role。仅在安全关闭的程序化测试构造器中允许本地角色头。
        if (securityProperties.isEnabled()) {
            throw new KnowledgeAuthorizationException("角色授权组件未配置", 503);
        }
        return parseLocalRoles(header);
    }

    private String auditActor(String userHeader, String requestedReviewer) {
        String principal = RequestContext.getPrincipalId();
        if (principal != null && !principal.isBlank()) return principal;
        Long authenticated = RequestContext.getUserId();
        if (authenticated != null) return String.valueOf(authenticated);
        if (securityProperties.isEnabled()) {
            throw new KnowledgeAuthorizationException("缺少认证身份", 401);
        }
        return firstNonBlank(userHeader, requestedReviewer, "anonymous");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "anonymous";
    }

    private Set<FeedbackRole> parseLocalRoles(String header) {
        if (header == null || header.isBlank()) return Set.of();
        java.util.EnumSet<FeedbackRole> result = java.util.EnumSet.noneOf(FeedbackRole.class);
        for (String item : header.split(",")) {
            try { result.add(FeedbackRole.parse(item)); } catch (IllegalArgumentException ignored) { }
        }
        return Set.copyOf(result);
    }

    @ExceptionHandler(KnowledgeAuthorizationException.class)
    public ResponseEntity<Result<Void>> forbidden(KnowledgeAuthorizationException ex) {
        return ResponseEntity.status(ex.status()).body(Result.error(ex.status(), ex.getMessage()));
    }

    private static final class KnowledgeAuthorizationException extends RuntimeException {
        private final int status;
        private KnowledgeAuthorizationException(String message, int status) { super(message); this.status = status; }
        private int status() { return status; }
    }

    /**
     * 仅接受隔离根目录内的真实文件；符号链接即使 normalize 后仍在根目录，
     * 也可能把摄取过程导向任意本机文件，因此必须按 real path 再做一次边界检查。
     */
    private boolean isSafeStagedPath(Path staged) {
        try {
            Path rootReal = stagingRoot.toRealPath();
            Path fileReal = staged.toRealPath();
            return fileReal.startsWith(rootReal) && Files.isRegularFile(fileReal);
        } catch (Exception ex) {
            return false;
        }
    }

    public static class IngestBody {
        public String stagedFile;
        public SourceBody source;
        public DocumentBody document;
        public String versionNo;
        public String parserVersion;
        public String schemaVersion;
        public String contentVersion;
        public String idempotencyKey;
        public int chunkSize = 1200;
        public int overlap = 120;
    }

    /** 异步 Job 请求在同步请求基础上增加目标 release 和 contract 版本。 */
    public static class IngestJobBody extends IngestBody {
        public String targetKnowledgeReleaseId;
        public String ingestionContractVersion = KnowledgeIngestionJobService.DEFAULT_CONTRACT_VERSION;
        public int maxAttempts = KnowledgeIngestionJobService.DEFAULT_MAX_ATTEMPTS;
    }

    public static class ReplayBody {
        public String reason;
        public String newIdempotencyKey;
    }

    /** 对外状态视图刻意不包含 request payload/staging 路径。 */
    public record IngestionJobView(String id, String sourceId, String contentChecksum,
                                   String targetKnowledgeReleaseId, String ingestionContractVersion,
                                   String status, int attemptCount, int maxAttempts,
                                   Instant nextAttemptAt, String leaseOwner, Instant leaseExpiresAt,
                                   String resultBatchId, Instant createdAt, Instant updatedAt,
                                   Instant completedAt, boolean created) {
        static IngestionJobView from(KnowledgeIngestionJob job, boolean created) {
            return new IngestionJobView(job.id(), job.sourceId(), job.contentChecksum(),
                    job.targetKnowledgeReleaseId(), job.ingestionContractVersion(), job.status().name(),
                    job.attemptCount(), job.maxAttempts(), job.nextAttemptAt(), job.leaseOwner(),
                    job.leaseExpiresAt(), job.resultBatchId(), job.createdAt(), job.updatedAt(),
                    job.completedAt(), created);
        }
    }

    public static class SourceBody {
        public String id;
        public String sourceUrl;
        public String title;
        public String license;
        public String trustLevel = "UNVERIFIED";
        public String ownerId;

        KnowledgeSource toDomain() {
            return new KnowledgeSource(id, sourceUrl, title, license,
                    KnowledgeSource.TrustLevel.valueOf(trustLevel == null ? "UNVERIFIED"
                            : trustLevel.toUpperCase(Locale.ROOT)),
                    ownerId, Instant.now(), Instant.now());
        }
    }

    public static class DocumentBody {
        public String id;
        public String canonicalKey;
        public String language;
        public String gameReleaseId;
        public String battleFormatId;
        public String rulesetVersionId;

        KnowledgeDocument toDomain(String sourceId) {
            return new KnowledgeDocument(id, sourceId, canonicalKey, language, gameReleaseId,
                    battleFormatId, rulesetVersionId, null, null, Instant.now());
        }
    }

    public static class ReviewBody { public String status; public String reviewer; public String reason; }
    public static class ReleaseBody { public String id; public String releaseNo; public String parserVersion; public String embeddingModel; public String dataReleaseId; }
    public static class IndexBody { public String id; public String knowledgeReleaseId; public String vectorStore; public String embeddingModel; public int dimension; }
    public static class PromotionBody {
        public String indexReleaseId;
        public String knowledgeReleaseId;
        public String dataReleaseId;
        public String evaluationRunId;
        public String reportHash;
        public String reviewer;
        public String approvalReason;
        public Instant requestedAt;
    }
}
