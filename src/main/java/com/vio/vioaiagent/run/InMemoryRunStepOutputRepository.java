package com.vio.vioaiagent.run;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Local/test implementation of the append-only per-step output store. */
@Repository
@Profile("!prod & !demo")
@Primary
public class InMemoryRunStepOutputRepository implements RunStepOutputRepository {
    private final ConcurrentMap<Key, RunStepOutput> outputs = new ConcurrentHashMap<>();

    @Override
    public synchronized RunStepOutput save(RunStepOutput output) {
        if (output == null) throw new IllegalArgumentException("Run step output 不能为空");
        Key key = new Key(output.runId(), output.stepNo());
        RunStepOutput existing = outputs.putIfAbsent(key, output);
        if (existing == null || existing.equals(output)) return existing == null ? output : existing;
        throw new RunStepOutputConflictException();
    }

    @Override
    public Optional<RunStepOutput> find(String runId, int stepNo) {
        if (runId == null || runId.isBlank() || stepNo < 0) return Optional.empty();
        return Optional.ofNullable(outputs.get(new Key(runId.trim(), stepNo)));
    }

    @Override
    public int deleteExpired(Instant now, int limit) {
        Instant point = now == null ? Instant.now() : now;
        int max = Math.max(1, Math.min(limit, 10_000));
        int deleted = 0;
        for (var entry : outputs.entrySet()) {
            if (deleted >= max) break;
            if (entry.getValue().expired(point) && outputs.remove(entry.getKey(), entry.getValue())) deleted++;
        }
        return deleted;
    }

    private record Key(String runId, int stepNo) { }
}
