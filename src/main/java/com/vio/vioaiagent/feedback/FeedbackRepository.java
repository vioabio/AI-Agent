package com.vio.vioaiagent.feedback;

import java.util.List;
import java.util.Optional;

/** 反馈/纠错持久化边界；实现按 profile 切换，业务层不依赖具体数据库。 */
public interface FeedbackRepository {
    CorrectionCase save(CorrectionCase correctionCase);

    Optional<CorrectionCase> findById(String feedbackId);

    Optional<CorrectionCase> findByIdForUser(String feedbackId, String userId);

    List<CorrectionCase> findForUser(String userId, int limit);

    /**
     * 按用户和答案 ID 在仓储层过滤反馈，再应用分页上限。
     *
     * <p>默认实现保留对旧仓储实现的兼容；生产 JDBC 实现应覆盖该方法，
     * 以免先取一页用户反馈再在内存过滤导致结果缺页。</p>
     */
    default List<CorrectionCase> findForUserAndAnswer(String userId, String answerId, int limit) {
        if (answerId == null || answerId.isBlank()) return List.of();
        return findForUser(userId, limit).stream()
                .filter(value -> answerId.equals(value.answerId()))
                .toList();
    }

    List<CorrectionCase> findAll(FeedbackState status, int limit);
}
