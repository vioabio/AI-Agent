package com.vio.vioaiagent.rag.contract;

import java.util.List;

/**
 * 宝可梦回答的稳定契约。无证据、版本冲突或版本缺失时使用 CLARIFICATION/REFUSED，禁止模型凭常识填空。
 */
public record AnswerContract(
        String answer,
        String applicableGameVersion,
        String battleFormat,
        String rulesetVersion,
        List<Citation> citations,
        List<String> limitations,
        String traceId,
        AnswerStatus status) {

    public AnswerContract {
        answer = answer == null ? "" : answer;
        citations = citations == null ? List.of() : List.copyOf(citations);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        status = status == null ? AnswerStatus.ANSWERED : status;
    }

    public static AnswerContract answered(String answer, String gameVersion, String battleFormat,
                                          String rulesetVersion, List<Citation> citations,
                                          List<String> limitations, String traceId) {
        return new AnswerContract(answer, gameVersion, battleFormat, rulesetVersion,
                citations, limitations, traceId, AnswerStatus.ANSWERED);
    }

    public static AnswerContract clarification(String message, String traceId, String limitation) {
        return new AnswerContract(message, null, null, null, List.of(), List.of(limitation),
                traceId, AnswerStatus.CLARIFICATION_REQUIRED);
    }

    public static AnswerContract refused(String message, String traceId, String limitation) {
        return new AnswerContract(message, null, null, null, List.of(), List.of(limitation),
                traceId, AnswerStatus.REFUSED);
    }

    public enum AnswerStatus { ANSWERED, CLARIFICATION_REQUIRED, REFUSED }
}

