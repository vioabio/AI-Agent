package com.vio.vioaiagent.security;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * PostgreSQL append-only HITL audit sink.  It writes only INSERT statements;
 * V17's trigger/privilege boundary rejects UPDATE/DELETE in the database too.
 */
@Repository
@Primary
@Profile({"prod", "demo"})
public class JdbcHitlAuditSink implements HitlAuditSink {

    private final JdbcTemplate jdbc;

    public JdbcHitlAuditSink(JdbcTemplate jdbc) {
        if (jdbc == null) throw new IllegalArgumentException("jdbc 不能为空");
        this.jdbc = jdbc;
    }

    @Override
    public void record(HitlAuditEvent event) {
        if (event == null) throw new IllegalArgumentException("audit event 不能为空");
        String hash = digest(event);
        int inserted = jdbc.update("""
                INSERT INTO audit_event
                    (event_id, approval_id, action, user_id, run_id, tool_name, tool_version,
                     parameter_digest, reason, occurred_at, retention_class, event_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'security', ?, CURRENT_TIMESTAMP)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), event.approvalId(), event.action(), event.userId(), event.runId(),
                event.toolName(), event.toolVersion(), event.parameterHash(), event.reason(),
                java.sql.Timestamp.from(event.occurredAt()), hash);
        if (inserted == 0) {
            String existingHash = jdbc.query("SELECT event_hash FROM audit_event WHERE event_id = ?",
                    rs -> rs.next() ? rs.getString("event_hash") : null, event.eventId());
            if (existingHash == null || !existingHash.equals(hash)) {
                throw new HitlApprovalConflictException("重复 eventId 的审计内容不一致: " + event.eventId());
            }
        }
    }

    private String digest(HitlAuditEvent event) {
        String canonical = String.join("\u001f", nullToEmpty(event.eventId()), nullToEmpty(event.approvalId()),
                nullToEmpty(event.action()), nullToEmpty(event.userId()), nullToEmpty(event.runId()),
                nullToEmpty(event.toolName()), nullToEmpty(event.toolVersion()), nullToEmpty(event.parameterHash()),
                nullToEmpty(event.reason()), event.occurredAt().toString());
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 不支持 SHA-256", ex);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
