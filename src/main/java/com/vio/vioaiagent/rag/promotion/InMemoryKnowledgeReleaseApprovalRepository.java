package com.vio.vioaiagent.rag.promotion;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 开发/测试用的追加式审批仓储；同一自然键不可覆盖既有审批。 */
@Repository
@Primary
@Profile("!prod")
public class InMemoryKnowledgeReleaseApprovalRepository implements KnowledgeReleaseApprovalRepository {

    private final Map<String, KnowledgeReleaseApproval> byId = new LinkedHashMap<>();
    private final Map<String, String> naturalToId = new LinkedHashMap<>();

    @Override
    public synchronized ReservationResult createOrGet(KnowledgeReleaseApproval candidate) {
        if (candidate == null) throw new IllegalArgumentException("candidate 不能为空");
        String natural = naturalKey(candidate.knowledgeReleaseId(), candidate.indexReleaseId(),
                candidate.evaluationRunId(), candidate.reportHash());
        String existingId = naturalToId.get(natural);
        if (existingId != null) {
            KnowledgeReleaseApproval existing = byId.get(existingId);
            if (!sameNaturalApproval(existing, candidate)) {
                throw new IllegalStateException("审批自然键已存在且内容不可覆盖: " + natural);
            }
            return new ReservationResult(existing, false);
        }
        KnowledgeReleaseApproval existingById = byId.get(candidate.approvalId());
        if (existingById != null && !sameNaturalApproval(existingById, candidate)) {
            throw new IllegalStateException("approvalId 已存在且内容不可覆盖: " + candidate.approvalId());
        }
        byId.put(candidate.approvalId(), candidate);
        naturalToId.put(natural, candidate.approvalId());
        return new ReservationResult(candidate, true);
    }

    @Override
    public synchronized Optional<KnowledgeReleaseApproval> findByApprovalId(String approvalId) {
        return approvalId == null || approvalId.isBlank()
                ? Optional.empty() : Optional.ofNullable(byId.get(approvalId.trim()));
    }

    @Override
    public synchronized Optional<KnowledgeReleaseApproval> findByNaturalKey(String knowledgeReleaseId,
                                                                               String indexReleaseId,
                                                                               String evaluationRunId,
                                                                               String reportHash) {
        String id = naturalToId.get(naturalKey(knowledgeReleaseId, indexReleaseId, evaluationRunId, reportHash));
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    @Override
    public boolean durable() {
        return true;
    }

    private String naturalKey(String knowledgeReleaseId, String indexReleaseId,
                              String evaluationRunId, String reportHash) {
        return String.join("\u0000", required(knowledgeReleaseId), required(indexReleaseId),
                required(evaluationRunId), required(reportHash));
    }

    private String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("审批索引字段不能为空");
        return value.trim();
    }

    private boolean sameNaturalApproval(KnowledgeReleaseApproval left, KnowledgeReleaseApproval right) {
        return left.approvalId().equals(right.approvalId())
                && left.knowledgeReleaseId().equals(right.knowledgeReleaseId())
                && left.indexReleaseId().equals(right.indexReleaseId())
                && left.dataReleaseId().equals(right.dataReleaseId())
                && left.evaluationRunId().equals(right.evaluationRunId())
                && left.reportHash().equals(right.reportHash())
                && left.reviewer().equals(right.reviewer())
                && java.util.Objects.equals(left.reason(), right.reason())
                && left.gateVersion().equals(right.gateVersion());
    }
}
