package com.vio.vioaiagent.run;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 本地/测试用的耐久状态替身。它只覆盖当前 JVM，生产 profile 必须使用 JDBC 实现。
 */
@Repository
@Profile("!prod & !demo")
@Primary
public class InMemoryDurableRunStateRepository implements CheckpointRepository,
        EffectLedgerRepository, ModelInvocationCacheRepository {

    private final ConcurrentMap<String, AgentCheckpoint> checkpoints = new ConcurrentHashMap<>();
    private final ConcurrentMap<EffectKey, ToolEffectLedgerEntry> effects = new ConcurrentHashMap<>();
    private final ConcurrentMap<ModelKey, ModelInvocationCacheEntry> modelCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RunLease> leases = new ConcurrentHashMap<>();

    @Override
    public synchronized AgentCheckpoint save(AgentCheckpoint checkpoint) {
        AgentCheckpoint previous = checkpoints.get(key(checkpoint.runId(), checkpoint.stepNo()));
        if (previous != null && checkpoint.version() != previous.version()) {
            throw new IllegalStateException("checkpoint CAS 冲突: " + checkpoint.runId());
        }
        AgentCheckpoint persisted = new AgentCheckpoint(checkpoint.runId(), checkpoint.stepNo(),
                checkpoint.executorVersion(), checkpoint.contextHash(), checkpoint.lastEventId(),
                checkpoint.nextAction(), checkpoint.leaseOwner(), checkpoint.leaseExpiresAt(),
                checkpoint.heartbeatAt(), checkpoint.updatedAt(), checkpoint.version() + 1);
        checkpoints.put(key(persisted.runId(), persisted.stepNo()), persisted);
        return persisted;
    }

    @Override
    public Optional<AgentCheckpoint> findLatest(String runId) {
        return checkpoints.values().stream().filter(item -> item.runId().equals(runId))
                .max(Comparator.comparingInt(AgentCheckpoint::stepNo)
                        .thenComparing(AgentCheckpoint::updatedAt));
    }

    @Override
    public List<AgentCheckpoint> findByRun(String runId) {
        List<AgentCheckpoint> result = new ArrayList<>();
        checkpoints.values().stream().filter(item -> item.runId().equals(runId)).forEach(result::add);
        result.sort(Comparator.comparingInt(AgentCheckpoint::stepNo));
        return List.copyOf(result);
    }

    @Override
    public synchronized Optional<RunLease> acquireLease(String runId, String owner, String token, Duration ttl) {
        validateLeaseArgs(runId, owner, token, ttl);
        Instant now = Instant.now();
        RunLease current = leases.get(runId);
        if (current != null && current.activeAt(now)
                && (!owner.equals(current.owner()) || !token.equals(current.token()))) return Optional.empty();
        RunLease next = new RunLease(runId, owner, token, now.plus(ttl), now,
                current == null ? 0 : current.version() + 1);
        leases.put(runId, next);
        return Optional.of(next);
    }

    @Override
    public synchronized Optional<RunLease> heartbeat(String runId, String owner, String token, Duration ttl) {
        validateLeaseArgs(runId, owner, token, ttl);
        RunLease current = leases.get(runId);
        if (current == null || !owner.equals(current.owner()) || !token.equals(current.token())
                || !current.activeAt(Instant.now())) return Optional.empty();
        Instant now = Instant.now();
        RunLease next = new RunLease(runId, owner, token, now.plus(ttl), now, current.version() + 1);
        leases.put(runId, next);
        return Optional.of(next);
    }

    @Override
    public synchronized boolean releaseLease(String runId, String owner, String token) {
        RunLease current = leases.get(runId);
        return current != null && owner.equals(current.owner()) && token.equals(current.token())
                && leases.remove(runId, current);
    }

    @Override
    public Optional<RunLease> currentLease(String runId) {
        return Optional.ofNullable(leases.get(runId));
    }

    @Override
    public synchronized <T> T withLease(String runId, String owner, String token, Supplier<T> action) {
        if (runId == null || owner == null || token == null || action == null) throw new RunLeaseLostException();
        RunLease lease = leases.get(runId);
        if (lease == null || !owner.equals(lease.owner()) || !token.equals(lease.token())
                || !lease.activeAt(Instant.now())) throw new RunLeaseLostException();
        return action.get();
    }

    @Override
    public Optional<ToolEffectLedgerEntry> find(String runId, int stepNo, String toolName,
                                                 String toolVersion, String idempotencyKey,
                                                 String parameterHash) {
        return Optional.ofNullable(effects.get(new EffectKey(runId, stepNo, toolName, toolVersion,
                idempotencyKey, parameterHash)));
    }

    @Override
    public Optional<ToolEffectLedgerEntry> findByIdempotencyKey(String runId, int stepNo,
                                                                  String toolName, String toolVersion,
                                                                  String idempotencyKey) {
        return effects.values().stream().filter(value -> value.runId().equals(runId)
                && value.stepNo() == stepNo && value.toolName().equals(toolName)
                && value.toolVersion().equals(toolVersion)
                && value.idempotencyKey().equals(idempotencyKey)).findFirst();
    }

    @Override
    public boolean reserve(ToolEffectLedgerEntry entry) {
        EffectKey key = EffectKey.of(entry);
        ToolEffectLedgerEntry existing = effects.putIfAbsent(key, entry);
        if (existing == null) return true;
        // 键相同但关键字段不可能不同（record key 已包含全部组成部分）；重复调用只返回 false。
        return false;
    }

    @Override
    public ToolEffectLedgerEntry complete(ToolEffectLedgerEntry entry) {
        EffectKey key = EffectKey.of(entry);
        ToolEffectLedgerEntry existing = effects.get(key);
        if (existing == null) throw new IllegalStateException("副作用未预留: " + key);
        if (existing.status() != ToolEffectStatus.STARTED) {
            // 结算结果是不可变事实；重复/反向回写必须返回冲突，而不能
            // 让旧 worker 覆盖已经完成的副作用。
            if (existing.status() == entry.status()
                    && java.util.Objects.equals(existing.resultDigest(), entry.resultDigest())) {
                return existing;
            }
            throw new IllegalStateException("副作用账本已结算: " + key);
        }
        if (!effects.replace(key, existing, entry)) {
            throw new IllegalStateException("副作用账本 CAS 冲突: " + key);
        }
        return entry;
    }

    @Override
    public synchronized ToolEffectLedgerEntry resolveUnknown(ToolEffectLedgerEntry entry,
                                                               ToolEffectStatus finalStatus,
                                                               String resultDigest, String resultUri) {
        if (entry == null || finalStatus == ToolEffectStatus.UNKNOWN
                || (finalStatus != ToolEffectStatus.SUCCEEDED && finalStatus != ToolEffectStatus.FAILED)) {
            throw new IllegalArgumentException("UNKNOWN 核对终态无效");
        }
        EffectKey key = EffectKey.of(entry);
        ToolEffectLedgerEntry current = effects.get(key);
        if (current == null || current.status() != ToolEffectStatus.UNKNOWN) {
            throw new IllegalStateException("副作用不是 UNKNOWN 状态");
        }
        ToolEffectLedgerEntry resolved = current.complete(finalStatus, resultDigest, resultUri, current.approvalId());
        effects.put(key, resolved);
        return resolved;
    }

    @Override
    public Optional<ModelInvocationCacheEntry> findValid(String runId, int stepNo, String requestHash,
                                                         Instant now) {
        ModelInvocationCacheEntry entry = modelCache.get(new ModelKey(runId, stepNo, requestHash));
        if (entry == null || entry.expired(now)) return Optional.empty();
        return Optional.of(entry);
    }

    @Override
    public ModelInvocationCacheEntry save(ModelInvocationCacheEntry entry) {
        modelCache.put(new ModelKey(entry.runId(), entry.stepNo(), entry.requestHash()), entry);
        return entry;
    }

    @Override
    public int deleteExpired(Instant now, int limit) {
        Instant point = now == null ? Instant.now() : now;
        int max = Math.max(1, limit);
        int deleted = 0;
        for (var item : modelCache.entrySet()) {
            if (deleted >= max) break;
            if (item.getValue().expired(point) && modelCache.remove(item.getKey(), item.getValue())) deleted++;
        }
        return deleted;
    }

    private static String key(String runId, int stepNo) { return runId + "#" + stepNo; }

    private static void validateLeaseArgs(String runId, String owner, String token, Duration ttl) {
        if (runId == null || runId.isBlank() || owner == null || owner.isBlank()
                || token == null || token.isBlank() || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("租约参数无效");
        }
    }

    private record EffectKey(String runId, int stepNo, String toolName, String toolVersion,
                             String idempotencyKey, String parameterHash) {
        static EffectKey of(ToolEffectLedgerEntry entry) {
            return new EffectKey(entry.runId(), entry.stepNo(), entry.toolName(), entry.toolVersion(),
                    entry.idempotencyKey(), entry.parameterHash());
        }
    }

    private record ModelKey(String runId, int stepNo, String requestHash) { }
}
