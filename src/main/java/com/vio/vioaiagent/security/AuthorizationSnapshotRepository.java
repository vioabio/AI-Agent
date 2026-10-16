package com.vio.vioaiagent.security;

import java.util.Optional;

/** Append-only authorization snapshot keyed by Run; no bearer token is stored. */
public interface AuthorizationSnapshotRepository {
    AuthorizationSnapshot save(AuthorizationSnapshot snapshot);
    Optional<AuthorizationSnapshot> findByRunId(String runId);
}
