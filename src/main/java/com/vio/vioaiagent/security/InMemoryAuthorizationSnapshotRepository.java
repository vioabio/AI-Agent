package com.vio.vioaiagent.security;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryAuthorizationSnapshotRepository implements AuthorizationSnapshotRepository {
    private final ConcurrentMap<String, AuthorizationSnapshot> values = new ConcurrentHashMap<>();
    @Override public AuthorizationSnapshot save(AuthorizationSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("授权快照不能为空");
        AuthorizationSnapshot existing = values.putIfAbsent(snapshot.runId(), snapshot);
        if (existing != null && !existing.equals(snapshot)) throw new IllegalStateException("授权快照不可变冲突");
        return existing == null ? snapshot : existing;
    }
    @Override public Optional<AuthorizationSnapshot> findByRunId(String runId) {
        return runId == null ? Optional.empty() : Optional.ofNullable(values.get(runId));
    }
}
