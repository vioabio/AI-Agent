package com.vio.vioaiagent.security;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryAuthorizationRevocationRepository implements AuthorizationRevocationRepository {
    private final ConcurrentMap<String, AuthorizationRevocation> values = new ConcurrentHashMap<>();
    @Override public AuthorizationRevocation revoke(String runId, String reason, Instant revokedAt) {
        AuthorizationRevocation candidate = new AuthorizationRevocation(runId, revokedAt, reason);
        AuthorizationRevocation existing = values.putIfAbsent(runId, candidate);
        return existing == null ? candidate : existing;
    }
    @Override public Optional<AuthorizationRevocation> findByRunId(String runId) {
        return runId == null ? Optional.empty() : Optional.ofNullable(values.get(runId));
    }
}
