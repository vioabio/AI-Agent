package com.vio.vioaiagent.run;

import com.vio.vioaiagent.security.AuditEntry;
import com.vio.vioaiagent.security.ToolAuditSink;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Explicit operator reconciliation boundary for uncertain external effects. */
@Service
public class EffectLedgerReconciliationService {
    private final EffectLedgerRepository repository;
    private final ToolAuditSink auditSink;

    public EffectLedgerReconciliationService(EffectLedgerRepository repository, ToolAuditSink auditSink) {
        this.repository = repository;
        this.auditSink = auditSink;
    }

    @Autowired
    public EffectLedgerReconciliationService(ObjectProvider<EffectLedgerRepository> provider,
                                             ObjectProvider<ToolAuditSink> auditProvider) {
        this.repository = provider == null ? null : provider.getIfAvailable();
        this.auditSink = auditProvider == null ? null : auditProvider.getIfAvailable();
    }

    public ToolEffectLedgerEntry resolve(String runId, int stepNo, String toolName, String toolVersion,
                                         String idempotencyKey, String parameterHash,
                                         ToolEffectStatus finalStatus, String resultDigest, String resultUri,
                                         String operatorId) {
        if (repository == null) throw new IllegalStateException("副作用账本未配置");
        if (auditSink == null || !auditSink.durable()) throw new IllegalStateException("人工核对审计出口未配置");
        if (operatorId == null || operatorId.isBlank()) throw new IllegalArgumentException("核对主体不能为空");
        if (finalStatus != ToolEffectStatus.SUCCEEDED && finalStatus != ToolEffectStatus.FAILED) {
            throw new IllegalArgumentException("人工核对只能确认成功或失败");
        }
        Optional<ToolEffectLedgerEntry> found = repository.find(runId, stepNo, toolName, toolVersion,
                idempotencyKey, parameterHash);
        if (found.isEmpty()) throw new IllegalArgumentException("副作用事实不存在");
        if (found.get().status() != ToolEffectStatus.UNKNOWN) {
            throw new IllegalStateException("只有 UNKNOWN 副作用允许人工核对");
        }
        ToolEffectLedgerEntry resolved = repository.resolveUnknown(found.get(), finalStatus, resultDigest, resultUri);
        auditSink.record(AuditEntry.of("effect-reconciliation", runId, operatorId.trim(),
                toolName, java.util.Map.of("stepNo", stepNo, "toolVersion", toolVersion,
                        "idempotencyKey", idempotencyKey, "parameterHash", parameterHash,
                        "fromStatus", ToolEffectStatus.UNKNOWN.name(), "toStatus", finalStatus.name()),
                "allow", "operator", 0));
        return resolved;
    }
}
