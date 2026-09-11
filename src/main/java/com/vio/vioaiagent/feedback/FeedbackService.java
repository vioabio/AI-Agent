package com.vio.vioaiagent.feedback;

import com.vio.vioaiagent.observability.AgentMetrics;
import com.vio.vioaiagent.run.AnswerProjection;
import com.vio.vioaiagent.run.AnswerProjectionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** M6 反馈纠错应用服务：提交、资源隔离、状态迁移和指标记录均在此收口。 */
@Service
public class FeedbackService {
    private final FeedbackRepository repository;
    private final AgentMetrics metrics;
    private final FeedbackAuthorization authorization;
    /** N6b 前置：存在投影仓储时反馈必须回读真实答案血缘。 */
    private volatile AnswerProjectionRepository answerProjectionRepository;

    public FeedbackService(FeedbackRepository repository, AgentMetrics metrics,
                           FeedbackAuthorization authorization) {
        this.repository = require(repository, "repository");
        this.metrics = metrics;
        this.authorization = require(authorization, "authorization");
    }

    /** 可选注入保持旧程序化构造器兼容；Spring v1.1 上下文会自动装配投影仓储。 */
    @Autowired(required = false)
    public void setAnswerProjectionRepository(AnswerProjectionRepository repository) {
        this.answerProjectionRepository = repository;
    }

    /** 提交一条用户反馈，初始状态固定为 SUBMITTED。 */
    @Transactional
    public FeedbackView submit(SubmitFeedbackCommand command) {
        if (!authorization.enabled()) {
            throw new FeedbackOperationException("反馈模块已关闭", 503);
        }
        if (command == null) throw new IllegalArgumentException("反馈命令不能为空");
        String feedbackId = command.feedbackId() == null || command.feedbackId().isBlank()
                ? "fb-" + UUID.randomUUID() : command.feedbackId().trim();
        Optional<CorrectionCase> existing = repository.findById(feedbackId);
        if (existing.isPresent()) {
            if (command.userId().equals(existing.get().userId())) {
                // 客户端重试同一 feedbackId 时返回原快照，不重复计数或写入第二条工单。
                return FeedbackView.from(existing.get());
            }
            throw new FeedbackOperationException("feedbackId 已被其他主体占用", 409);
        }
        AnswerProjection projection = resolveAnswerLineage(command);
        String traceId = projection == null ? command.traceId() : projection.traceId();
        String dataReleaseId = projection == null ? command.dataReleaseId() : projection.dataReleaseId();
        String knowledgeReleaseId = projection == null ? command.knowledgeReleaseId() : projection.knowledgeReleaseId();
        String indexReleaseId = projection == null ? command.indexReleaseId() : projection.indexReleaseId();
        if (projection == null && isPlaceholder(traceId)) {
            throw new FeedbackOperationException("缺少答案 traceId，反馈不可追溯", 422);
        }
        CorrectionCase value = CorrectionCase.submit(feedbackId, command.answerId(), traceId,
                command.errorType(), command.evidence(), command.userId(), dataReleaseId,
                knowledgeReleaseId, indexReleaseId, command.feedbackType(),
                command.priority(), command.createdAt());
        CorrectionCase stored = repository.save(value);
        recordMetric("submitted");
        return FeedbackView.from(stored);
    }

    @Transactional
    public FeedbackView transition(FeedbackTransitionCommand command) {
        if (!authorization.enabled()) {
            throw new FeedbackOperationException("反馈模块已关闭", 503);
        }
        if (command == null) throw new IllegalArgumentException("反馈迁移命令不能为空");
        CorrectionCase current = repository.findById(command.feedbackId())
                .orElseThrow(() -> new FeedbackNotFoundException(command.feedbackId()));
        // 不能把 command.roles 当作认证结果：生产内部调用或未来新增入口
        // 可能构造任意命令，必须在服务边界再次从配置/认证适配器收口。
        Set<FeedbackRole> effectiveRoles = authorization.authorizedRoles(command.actorId(), command.roles());
        CorrectionCase next = FeedbackStateMachine.transition(current, command.target(), command.actorId(),
                effectiveRoles, command.reason(), command.traceId(), command.fixReleaseId(),
                command.evaluationResult());
        CorrectionCase stored = repository.save(next);
        recordMetric(next.status().name());
        return FeedbackView.from(stored);
    }

    @Transactional(readOnly = true)
    public FeedbackView findForUser(String feedbackId, String userId) {
        return repository.findByIdForUser(feedbackId, userId)
                .map(FeedbackView::from)
                .orElseThrow(() -> new FeedbackNotFoundException(feedbackId));
    }

    @Transactional(readOnly = true)
    public List<FeedbackView> findForUser(String userId, int limit) {
        return repository.findForUser(userId, boundedLimit(limit)).stream().map(FeedbackView::from).toList();
    }

    /**
     * 查询用户对指定答案的反馈。过滤条件下沉到仓储，保证分页不会因先截断用户
     * 全量列表而漏掉目标答案的记录。
     */
    @Transactional(readOnly = true)
    public List<FeedbackView> findForUserAndAnswer(String userId, String answerId, int limit) {
        return repository.findForUserAndAnswer(userId, answerId, boundedLimit(limit)).stream()
                .map(FeedbackView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<FeedbackView> findAll(FeedbackState status, int limit) {
        return repository.findAll(status, boundedLimit(limit)).stream().map(FeedbackView::from).toList();
    }

    /** 运营侧入口统一检查角色，避免 Controller 绕过状态机直接写仓储。 */
    public Set<FeedbackRole> resolveRoles(String actorId, String localRoleHeader) {
        return authorization.rolesFor(actorId, localRoleHeader);
    }

    /**
     * 回读 answerId 对应的不可变投影并校验调用方传入的版本键。
     * 生产/启用投影时，找不到答案或缺少 release 会明确返回 422，禁止
     * unknown/猜测值进入纠错发布队列；旧程序化构造器未注入仓储时保留 v1.0 语义。
     */
    private AnswerProjection resolveAnswerLineage(SubmitFeedbackCommand command) {
        AnswerProjectionRepository projections = answerProjectionRepository;
        if (projections == null) {
            if (authorization.requireLineage()) {
                throw new FeedbackOperationException("答案血缘仓储不可用，反馈不可追溯", 503);
            }
            return null;
        }
        AnswerProjection projection = projections.findByIdForUser(command.answerId(), command.userId())
                .orElseThrow(() -> new FeedbackOperationException(
                        "答案不存在或不属于当前用户，反馈不可追溯", 422));
        if (!projection.lineageComplete()) {
            throw new FeedbackOperationException("答案缺少完整 data/knowledge/index release 血缘", 422);
        }
        assertMatches("traceId", command.traceId(), projection.traceId());
        assertMatches("dataReleaseId", command.dataReleaseId(), projection.dataReleaseId());
        assertMatches("knowledgeReleaseId", command.knowledgeReleaseId(), projection.knowledgeReleaseId());
        assertMatches("indexReleaseId", command.indexReleaseId(), projection.indexReleaseId());
        return projection;
    }

    private void assertMatches(String field, String supplied, String canonical) {
        if (isPlaceholder(supplied)) return;
        if (canonical == null || !canonical.equals(supplied.trim())) {
            throw new FeedbackOperationException(field + " 与答案投影不一致", 409);
        }
    }

    private boolean isPlaceholder(String value) {
        return value == null || value.isBlank() || "unknown".equalsIgnoreCase(value.trim())
                || "trace-unknown".equalsIgnoreCase(value.trim());
    }

    private void recordMetric(String type) {
        if (metrics != null) metrics.recordFeedback(type);
    }

    private int boundedLimit(int limit) {
        return Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }

    /** 反馈模块依赖或开关错误，携带稳定 HTTP 语义。 */
    public static class FeedbackOperationException extends RuntimeException {
        private final int status;

        public FeedbackOperationException(String message, int status) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
