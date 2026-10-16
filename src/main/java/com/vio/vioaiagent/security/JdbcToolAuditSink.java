package com.vio.vioaiagent.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * 生产普通工具审计适配器。
 *
 * <p>普通工具与 HITL 事件共用 {@code audit_event} append-only 表，普通工具
 * 的 {@code approval_id} 固定为 {@code NULL}。事件 ID 由规范化事实摘要得到，
 * 因而同一事实重试只会留下一个 INSERT；若相同 ID 对应不同 hash，则明确失败。</p>
 */
@Repository
@Primary
@Profile({"prod", "demo"})
public class JdbcToolAuditSink implements ToolAuditSink {

    private final JdbcTemplate jdbc;
    private final ParameterMasker masker;
    private final ObjectMapper mapper;

    @Autowired
    public JdbcToolAuditSink(JdbcTemplate jdbc, ParameterMasker masker) {
        if (jdbc == null || masker == null) {
            throw new IllegalArgumentException("工具审计依赖不能为空");
        }
        this.jdbc = jdbc;
        this.masker = masker;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /** 便于极简 Spring/单元装配；生产 Spring 会优先使用带 masker 的构造器。 */
    public JdbcToolAuditSink(JdbcTemplate jdbc) {
        this(jdbc, new ParameterMasker());
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public boolean failClosedOnError() {
        return true;
    }

    @Override
    public void record(AuditEntry entry) {
        Facts facts = facts(entry);
        int inserted;
        try {
            inserted = jdbc.update("""
                    INSERT INTO audit_event
                        (event_id, approval_id, action, user_id, run_id, tool_name, tool_version,
                         parameter_digest, reason, occurred_at, retention_class, event_hash, created_at)
                    VALUES (?, NULL, ?, ?, ?, ?, NULL, ?, ?, ?, 'security', ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (event_id) DO NOTHING
                    """, facts.eventId(), facts.action(), facts.userId(), facts.runId(), facts.toolName(),
                    facts.parameterDigest(), facts.reason(), Timestamp.from(facts.occurredAt()), facts.eventHash());
        } catch (RuntimeException ex) {
            // 不把 SQL/连接细节或审计参数回传给模型/调用方。
            throw new AuditWriteException("生产工具审计写入失败", ex);
        }
        if (inserted == 0) {
            try {
                String existingHash = jdbc.query("SELECT event_hash FROM audit_event WHERE event_id = ?",
                        rs -> rs.next() ? rs.getString("event_hash") : null, facts.eventId());
                if (existingHash == null || !existingHash.equals(facts.eventHash())) {
                    throw new AuditWriteException("重复工具审计事件内容不一致");
                }
            } catch (AuditWriteException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new AuditWriteException("生产工具审计幂等校验失败", ex);
            }
        }
    }

    private Facts facts(AuditEntry entry) {
        if (entry == null) throw new IllegalArgumentException("审计条目不能为空");
        String traceId = required(entry.traceId(), "traceId");
        String runId = required(entry.sessionId(), "runId");
        String userId = required(entry.userId(), "userId");
        String toolName = required(entry.toolName(), "toolName");
        String outcome = required(entry.outcome(), "outcome").toLowerCase(Locale.ROOT);
        String approver = entry.approver() == null || entry.approver().isBlank()
                ? "none" : bounded(entry.approver(), 128, "approver");
        if (!SetLike.isSafeOutcome(outcome)) {
            throw new IllegalArgumentException("不支持的工具审计 outcome");
        }
        if (entry.durationMs() < 0) throw new IllegalArgumentException("审计耗时不能为负数");
        Instant occurredAt = entry.timestamp() == null ? Instant.now() : entry.timestamp();
        Map<String, Object> masked = masker.mask(entry.params() == null ? Map.of() : entry.params());
        String paramsJson = json(masked == null ? Map.of() : masked);
        String parameterDigest = sha256(paramsJson);
        String action = "TOOL_" + outcome.toUpperCase(Locale.ROOT);
        String reason = bounded("outcome=" + outcome + ";approver=" + approver
                + ";durationMs=" + entry.durationMs(), 2000, "reason");
        String canonical = String.join("\u001f", traceId, runId, userId, toolName,
                parameterDigest, action, approver, Long.toString(entry.durationMs()), occurredAt.toString());
        String eventHash = sha256(canonical);
        // event_id 使用完整摘要，避免把可预测的 trace/run 文本直接作为主键。
        return new Facts(eventHash, action, userId, runId, toolName, parameterDigest,
                reason, occurredAt, eventHash);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new AuditWriteException("工具审计参数无法规范化", ex);
        }
    }

    private String required(String value, String field) {
        return bounded(value, 128, field);
    }

    private String bounded(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(field + " 不能为空或超出长度");
        }
        return value;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 不支持 SHA-256", ex);
        }
    }

    private record Facts(String eventId, String action, String userId, String runId,
                         String toolName, String parameterDigest, String reason,
                         Instant occurredAt, String eventHash) { }

    /** 避免引入新的可变配置；普通工具审计只接受稳定的三种结果。 */
    private static final class SetLike {
        private SetLike() { }

        private static boolean isSafeOutcome(String value) {
            return "allow".equals(value) || "deny".equals(value) || "error".equals(value);
        }
    }
}
