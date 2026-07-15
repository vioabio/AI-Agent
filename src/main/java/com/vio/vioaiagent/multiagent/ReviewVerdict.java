package com.vio.vioaiagent.multiagent;

import java.util.Collections;
import java.util.List;

/**
 * 审查结论 — Reviewer 对执行结果的评分和反馈.
 *
 * @param score        质量评分 (0-100)
 * @param passed       是否通过（score ≥ 60 为通过）
 * @param feedback     审查反馈文本
 * @param tasksToRetry 需要重试的任务 ID 列表
 * @author vio
 */
public record ReviewVerdict(
        int score,
        boolean passed,
        String feedback,
        List<String> tasksToRetry) {

    public static final int PASS_THRESHOLD = 60;
    public static final int MAX_RETRIES = 2;

    public static ReviewVerdict pass(String feedback) {
        return new ReviewVerdict(100, true, feedback, Collections.emptyList());
    }

    public static ReviewVerdict fail(int score, String feedback, List<String> retryTasks) {
        return new ReviewVerdict(score, false, feedback, retryTasks);
    }
}
