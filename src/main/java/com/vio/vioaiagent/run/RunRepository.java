package com.vio.vioaiagent.run;

import java.util.Optional;
import java.util.List;

/**
 * Run 持久化边界；实现可切换为 JDBC，服务层不依赖具体存储。
 *
 * <p>{@link #save(AgentRun)} 以聚合快照中的 {@code version} 作为期望版本执行
 * 乐观锁 CAS：新建快照使用版本 0，更新成功后仓储会把同一聚合的版本推进 1；
 * 版本不匹配必须抛出 {@link RunConflictException}，禁止旧实例覆盖新状态。</p>
 */
public interface RunRepository {
    AgentRun save(AgentRun run);
    Optional<AgentRun> findById(String runId);
    Optional<AgentRun> findByIdForUser(String runId, String userId);
    Optional<AgentRun> findByIdempotencyKey(String userId, String idempotencyKey);

    /**
     * 返回仍处于非终态的 Run，供服务启动时做恢复/孤儿收敛。
     * 内存实现只能覆盖同一仓储实例（不会跨进程保存）；生产恢复依赖 JDBC
     * 持久化数据。
     */
    default List<AgentRun> findActive() {
        return List.of();
    }
}
