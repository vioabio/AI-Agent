package com.vio.vioaiagent.run;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 开发/测试用 Run 仓储；通过 userId 过滤资源，避免跨用户泄漏。 */
@Repository
@org.springframework.context.annotation.Profile("!prod")
@Primary
public class InMemoryRunRepository implements AgentRunRepository {
    private final ConcurrentMap<String, AgentRun> runs = new ConcurrentHashMap<>();

    @Override
    public AgentRun save(AgentRun run) {
        if (run == null) throw new IllegalArgumentException("Run 不能为空");
        runs.compute(run.id(), (runId, current) -> {
            if (current == null) {
                if (run.version() != 0) throw new RunConflictException(run.id(), run.version());
                run.markPersistedVersion(0);
                return run;
            }
            if (current.version() != run.version()) {
                throw new RunConflictException(run.id(), run.version());
            }
            if (!isAllowedSnapshot(current, run)) {
                throw new RunConflictException(run.id(), run.version());
            }
            run.markPersistedVersion(current.version() + 1);
            return run;
        });
        return run;
    }

    private boolean isAllowedSnapshot(AgentRun current, AgentRun incoming) {
        if (current.status().isTerminal()) {
            return current.status() == incoming.status();
        }
        return current.status() == incoming.status()
                || current.status().canTransitionTo(incoming.status());
    }

    @Override
    public Optional<AgentRun> findById(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public Optional<AgentRun> findByIdForUser(String runId, String userId) {
        if (runId == null || userId == null) return Optional.empty();
        return findById(runId).filter(run -> userId.equals(run.userId()));
    }

    @Override
    public Optional<AgentRun> findByIdempotencyKey(String userId, String idempotencyKey) {
        if (userId == null || idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        return runs.values().stream()
                .filter(run -> userId.equals(run.userId()) && idempotencyKey.equals(run.idempotencyKey()))
                .findFirst();
    }

    /**
     * 返回当前进程仍未进入终态的 Run 快照。
     *
     * <p>内存仓储本身不会跨进程存活，因此不能模拟真正的重启恢复；但在
     * 测试或嵌入式启动中，同一个仓储实例可能已经装载了活动 Run。实现该
     * 查询可以让生命周期服务的恢复分支保持可测试，并与 JDBC 仓储的
     * {@code findActive()} 语义一致。</p>
     */
    @Override
    public List<AgentRun> findActive() {
        return runs.values().stream()
                .filter(run -> !run.status().isTerminal())
                .sorted(java.util.Comparator.comparing(AgentRun::createdAt).thenComparing(AgentRun::id))
                .toList();
    }
}
