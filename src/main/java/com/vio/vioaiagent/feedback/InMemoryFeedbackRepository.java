package com.vio.vioaiagent.feedback;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 本地/测试反馈仓储；使用同一锁保证状态快照替换和查询的一致性。 */
@Repository
@Primary
@Profile("!prod & !demo")
public class InMemoryFeedbackRepository implements FeedbackRepository {
    private final ConcurrentMap<String, CorrectionCase> cases = new ConcurrentHashMap<>();

    @Override
    public CorrectionCase save(CorrectionCase correctionCase) {
        if (correctionCase == null) throw new IllegalArgumentException("反馈工单不能为空");
        cases.compute(correctionCase.feedbackId(), (id, previous) -> {
            if (previous != null && correctionCase.version() <= previous.version()
                    && !correctionCase.equals(previous)) {
                throw new FeedbackConflictException("反馈版本冲突，拒绝覆盖更新");
            }
            return correctionCase;
        });
        return correctionCase;
    }

    @Override
    public Optional<CorrectionCase> findById(String feedbackId) {
        return Optional.ofNullable(feedbackId == null ? null : cases.get(feedbackId));
    }

    @Override
    public Optional<CorrectionCase> findByIdForUser(String feedbackId, String userId) {
        if (feedbackId == null || userId == null) return Optional.empty();
        return findById(feedbackId).filter(value -> userId.equals(value.userId()));
    }

    @Override
    public List<CorrectionCase> findForUser(String userId, int limit) {
        if (userId == null || userId.isBlank() || limit <= 0) return List.of();
        return sorted(limit, value -> userId.equals(value.userId()));
    }

    @Override
    public List<CorrectionCase> findForUserAndAnswer(String userId, String answerId, int limit) {
        if (userId == null || userId.isBlank() || answerId == null || answerId.isBlank() || limit <= 0) {
            return List.of();
        }
        return sorted(limit, value -> userId.equals(value.userId()) && answerId.equals(value.answerId()));
    }

    @Override
    public List<CorrectionCase> findAll(FeedbackState status, int limit) {
        if (limit <= 0) return List.of();
        return sorted(limit, value -> status == null || status == value.status());
    }

    private List<CorrectionCase> sorted(int limit, java.util.function.Predicate<CorrectionCase> predicate) {
        List<CorrectionCase> result = new ArrayList<>();
        cases.values().stream().filter(predicate)
                .sorted(Comparator.comparing(CorrectionCase::updatedAt).reversed()
                        .thenComparing(CorrectionCase::feedbackId))
                .limit(Math.min(limit, 200))
                .forEach(result::add);
        return List.copyOf(result);
    }
}
