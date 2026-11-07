package com.vio.vioaiagent.run;

import java.time.Instant;
import java.util.Optional;

/** Durable, immutable encrypted output for a replayable Run step. */
public interface RunStepOutputRepository {
    RunStepOutput save(RunStepOutput output);
    Optional<RunStepOutput> find(String runId, int stepNo);
    int deleteExpired(Instant now, int limit);
}
