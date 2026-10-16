package com.vio.vioaiagent.security;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
@Profile({"prod", "demo"})
@Primary
public class JdbcAuthorizationRevocationRepository implements AuthorizationRevocationRepository {
    private final JdbcTemplate jdbc;
    public JdbcAuthorizationRevocationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override @Transactional
    public AuthorizationRevocation revoke(String runId, String reason, Instant revokedAt) {
        AuthorizationRevocation value = new AuthorizationRevocation(runId, revokedAt, reason);
        jdbc.update("""
                INSERT INTO agent_authorization_snapshot_revocation (run_id, revoked_at, reason)
                VALUES (?, ?, ?) ON CONFLICT (run_id) DO NOTHING
                """, value.runId(), Timestamp.from(value.revokedAt()), value.reason());
        return findByRunId(runId).orElseThrow(() -> new IllegalStateException("撤销事实写入后不可读"));
    }
    @Override public Optional<AuthorizationRevocation> findByRunId(String runId) {
        if (runId == null || runId.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT run_id, revoked_at, reason FROM agent_authorization_snapshot_revocation WHERE run_id = ?
                    """, (rs, row) -> new AuthorizationRevocation(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3)), runId));
        } catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }
}
