package com.vio.vioaiagent.run;

import java.util.Optional;

/** Answer 只读投影持久化边界；实现必须按 answerId/runId 幂等且拒绝内容冲突。 */
public interface AnswerProjectionRepository {
    AnswerProjection save(AnswerProjection projection);

    Optional<AnswerProjection> findById(String answerId);

    Optional<AnswerProjection> findByIdForUser(String answerId, String userId);

    Optional<AnswerProjection> findByRunId(String runId);

    default Optional<AnswerProjection> findByRunIdForUser(String runId, String userId) {
        return findByRunId(runId).filter(value -> userId != null && userId.equals(value.userId()));
    }
}
