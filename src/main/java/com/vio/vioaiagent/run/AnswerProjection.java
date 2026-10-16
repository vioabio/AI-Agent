package com.vio.vioaiagent.run;

import com.vio.vioaiagent.rag.contract.AnswerContract;
import com.vio.vioaiagent.rag.contract.Citation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answer 的稳定只读投影。
 *
 * <p>投影把客户端看到的答案和产生它的 Run/trace 以及版本上下文绑定在一起。
 * 旧执行器暂时没有 data/knowledge/index release 时不会填入 {@code unknown}，
 * 而是明确标记为 {@link LineageStatus#PARTIAL}，反馈入口可据此拒绝不可追溯样本。</p>
 */
public record AnswerProjection(
        String answerId,
        String runId,
        String sessionId,
        String conversationId,
        String userId,
        String traceId,
        String status,
        String answer,
        String gameReleaseId,
        String dlcId,
        String battleFormatId,
        String rulesetVersionId,
        String dataReleaseId,
        String knowledgeReleaseId,
        String indexReleaseId,
        String contractVersion,
        LineageStatus lineageStatus,
        String answerDigest,
        List<Map<String, Object>> citations,
        List<String> limitations,
        Instant createdAt,
        Instant completedAt) {

    public AnswerProjection {
        require(answerId, "answerId");
        require(runId, "runId");
        require(sessionId, "sessionId");
        require(conversationId, "conversationId");
        require(userId, "userId");
        require(traceId, "traceId");
        status = normalize(status, "status");
        answer = answer == null ? "" : answer;
        contractVersion = normalize(contractVersion, "v1");
        lineageStatus = lineageStatus == null ? inferLineageStatus(gameReleaseId, battleFormatId,
                rulesetVersionId, dataReleaseId, knowledgeReleaseId, indexReleaseId) : lineageStatus;
        if (lineageStatus == LineageStatus.COMPLETE
                && inferLineageStatus(gameReleaseId, battleFormatId, rulesetVersionId,
                dataReleaseId, knowledgeReleaseId, indexReleaseId) != LineageStatus.COMPLETE) {
            throw new IllegalArgumentException("完整答案血缘必须包含 game/battle/rules/data/knowledge/index release");
        }
        answerDigest = normalize(answerDigest, digest(answer));
        citations = immutableCitations(citations);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        completedAt = completedAt == null ? createdAt : completedAt;
        if (completedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("completedAt 不能早于 createdAt");
        }
        // PostgreSQL TIMESTAMPTZ stores microseconds; immutable equality must survive JDBC readback.
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        completedAt = completedAt.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 从一次 Run 的结构化回答创建稳定投影。answerId 由调用方传入，通常使用
     * {@code answer-<runId>}，这样重试不会产生第二个答案。
     */
    public static AnswerProjection from(AgentRun run, AnswerContract contract, String answerId,
                                        Instant completedAt) {
        return from(run, contract, answerId, completedAt, AnswerLineage.empty());
    }

    /** 从回答和显式发布血缘创建投影。 */
    public static AnswerProjection from(AgentRun run, AnswerContract contract, String answerId,
                                        Instant completedAt, AnswerLineage lineage) {
        if (run == null) throw new IllegalArgumentException("Run 不能为空");
        lineage = lineage == null ? AnswerLineage.empty() : lineage;
        String answer = contract == null ? "" : contract.answer();
        String game = firstNonBlank(contract == null ? null : contract.applicableGameVersion(),
                run.gameReleaseId());
        String battle = firstNonBlank(contract == null ? null : contract.battleFormat(), run.battleFormat());
        String rules = firstNonBlank(contract == null ? null : contract.rulesetVersion(),
                run.rulesetVersionId());
        String trace = firstNonBlank(contract == null ? null : contract.traceId(), run.traceId());
        // 执行器可返回实际检索 release；未覆盖时沿用 Run 创建时冻结的上下文，
        // 这样答案投影不会因为旧执行器缺少扩展接口而丢失已知血缘。
        String dlc = firstNonBlank(lineage.dlcId(), run.dlcId());
        String dataRelease = firstNonBlank(lineage.dataReleaseId(), run.dataReleaseId());
        String knowledgeRelease = firstNonBlank(lineage.knowledgeReleaseId(), run.knowledgeReleaseId());
        String indexRelease = firstNonBlank(lineage.indexReleaseId(), run.indexReleaseId());
        // AnswerLineage.empty() carries the compatibility default v1. 只有当扩展
        // 实际返回 release 血缘时才让它覆盖 Run 创建时冻结的 contractVersion，
        // 避免旧执行器把显式 v1.1 请求降级成 v1。
        String contractVersion = lineage.hasReleaseLineage()
                ? firstNonBlank(lineage.contractVersion(), run.contractVersion())
                : firstNonBlank(run.contractVersion(), lineage.contractVersion());
        List<Map<String, Object>> citationPayloads = new ArrayList<>();
        if (contract != null && contract.citations() != null) {
            for (Citation citation : contract.citations()) {
                if (citation == null) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("documentId", citation.documentId());
                item.put("documentVersion", citation.documentVersion());
                item.put("sourceTitle", citation.sourceTitle());
                item.put("sourceUrl", citation.sourceUrl());
                item.put("chunkId", citation.chunkId());
                item.put("evidenceText", citation.evidenceText());
                item.put("retrievalScore", citation.retrievalScore());
                citationPayloads.add(item);
            }
        }
        return new AnswerProjection(
                answerId,
                run.id(),
                run.sessionId(),
                run.conversationId(),
                run.userId(),
                trace,
                // projection 表示即将提交的完成结果；run 在此处仍处于 RUNNING，
                // 因而无结构化契约时使用目标终态而不是把 RUNNING 落成答案状态。
                contract == null ? AgentRunStatus.COMPLETED.name() : contract.status().name(),
                answer,
                game,
                dlc,
                battle,
                rules,
                dataRelease,
                knowledgeRelease,
                indexRelease,
                contractVersion,
                null,
                null,
                citationPayloads,
                contract == null ? List.of() : contract.limitations(),
                run.createdAt(),
                completedAt == null ? Instant.now() : completedAt);
    }

    /** 旧代码/测试构造投影时使用的精简工厂。 */
    public static AnswerProjection of(String answerId, String runId, String sessionId,
                                      String conversationId, String userId, String traceId,
                                      String status, String answer, String gameReleaseId,
                                      String battleFormatId, String rulesetVersionId,
                                      String dataReleaseId, String knowledgeReleaseId,
                                      String indexReleaseId) {
        Instant now = Instant.now();
        return new AnswerProjection(answerId, runId, sessionId, conversationId, userId, traceId,
                status, answer, gameReleaseId, null, battleFormatId, rulesetVersionId,
                dataReleaseId, knowledgeReleaseId, indexReleaseId, "v1", null, null,
                List.of(), List.of(), now, now);
    }

    /** 是否已经拥有反馈闭环所需的完整版本血缘。 */
    public boolean lineageComplete() {
        return lineageStatus == LineageStatus.COMPLETE;
    }

    /** 面向事件/API 的稳定摘要；不包含内部对象或密钥。 */
    public Map<String, Object> lineagePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answerId", answerId);
        payload.put("runId", runId);
        payload.put("traceId", traceId);
        payload.put("lineageStatus", lineageStatus.name());
        payload.put("gameReleaseId", nullable(gameReleaseId));
        payload.put("dlcId", nullable(dlcId));
        payload.put("battleFormatId", nullable(battleFormatId));
        payload.put("rulesetVersionId", nullable(rulesetVersionId));
        payload.put("dataReleaseId", nullable(dataReleaseId));
        payload.put("knowledgeReleaseId", nullable(knowledgeReleaseId));
        payload.put("indexReleaseId", nullable(indexReleaseId));
        payload.put("contractVersion", contractVersion);
        payload.put("answerDigest", answerDigest);
        return Map.copyOf(payload);
    }

    public enum LineageStatus { COMPLETE, PARTIAL, UNTRACEABLE }

    private static LineageStatus inferLineageStatus(String game, String battle, String rules,
                                                    String data, String knowledge, String index) {
        if (isBlank(game) || isBlank(battle) || isBlank(rules)
                || isBlank(data) || isBlank(knowledge) || isBlank(index)) {
            return LineageStatus.PARTIAL;
        }
        return LineageStatus.COMPLETE;
    }

    private static List<Map<String, Object>> immutableCitations(List<Map<String, Object>> value) {
        if (value == null || value.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : value) {
            if (item == null) {
                result.add(Map.of());
            } else {
                // Citation 的可选字段允许为 null；使用不可变副本但不调用
                // Map.copyOf（其会拒绝 null value），避免读取旧来源时失败。
                result.add(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(item)));
            }
        }
        return List.copyOf(result);
    }

    private static String firstNonBlank(String first, String second) {
        return isBlank(first) ? (isBlank(second) ? null : second.trim()) : first.trim();
    }

    private static String nullable(String value) { return value == null ? "" : value; }

    private static String normalize(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank() || "unknown".equalsIgnoreCase(value.trim());
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    private static void require(String value, String field) {
        if (isBlank(value)) throw new IllegalArgumentException(field + " 不能为空");
    }
}
