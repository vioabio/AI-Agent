package com.vio.vioaiagent.run;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 本地/测试投影仓储；不对外宣称跨进程耐久性。 */
@Repository
@Profile("!prod")
@Primary
public class InMemoryAnswerProjectionRepository implements AnswerProjectionRepository {
    private final ConcurrentMap<String, AnswerProjection> byAnswer = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> answerByRun = new ConcurrentHashMap<>();

    @Override
    public synchronized AnswerProjection save(AnswerProjection projection) {
        if (projection == null) throw new IllegalArgumentException("Answer projection 不能为空");
        String existingAnswerId = answerByRun.get(projection.runId());
        if (existingAnswerId != null && !existingAnswerId.equals(projection.answerId())) {
            throw new RunConflictException(projection.runId(), 0L);
        }
        AnswerProjection existing = byAnswer.get(projection.answerId());
        if (existing != null && !existing.equals(projection)) {
            throw new IllegalStateException("answerId 已存在且投影内容不一致: " + projection.answerId());
        }
        if (existing != null) return existing;
        String answerForRun = answerByRun.putIfAbsent(projection.runId(), projection.answerId());
        if (answerForRun != null && !answerForRun.equals(projection.answerId())) {
            throw new RunConflictException(projection.runId(), 0L);
        }
        byAnswer.put(projection.answerId(), projection);
        return projection;
    }

    @Override
    public Optional<AnswerProjection> findById(String answerId) {
        return answerId == null || answerId.isBlank()
                ? Optional.empty() : Optional.ofNullable(byAnswer.get(answerId.trim()));
    }

    @Override
    public Optional<AnswerProjection> findByIdForUser(String answerId, String userId) {
        if (userId == null || userId.isBlank()) return Optional.empty();
        return findById(answerId).filter(value -> userId.trim().equals(value.userId()));
    }

    @Override
    public Optional<AnswerProjection> findByRunId(String runId) {
        if (runId == null || runId.isBlank()) return Optional.empty();
        String answerId = answerByRun.get(runId.trim());
        return answerId == null ? Optional.empty() : findById(answerId);
    }
}
