package com.vio.vioaiagent.security;

import java.time.Instant;
import java.util.Optional;

public interface AuthorizationRevocationRepository {
    AuthorizationRevocation revoke(String runId, String reason, Instant revokedAt);
    Optional<AuthorizationRevocation> findByRunId(String runId);
}
