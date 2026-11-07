package com.vio.vioaiagent.run;

import java.time.Instant;
import java.util.Optional;

public interface ModelInvocationCacheRepository {
    Optional<ModelInvocationCacheEntry> findValid(String runId, int stepNo, String requestHash, Instant now);
    ModelInvocationCacheEntry save(ModelInvocationCacheEntry entry);
    int deleteExpired(Instant now, int limit);
}
