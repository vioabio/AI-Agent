package com.vio.vioaiagent.run;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Checkpoint/lease 持久化边界；生产实现必须使用数据库 CAS。 */
public interface CheckpointRepository {
    AgentCheckpoint save(AgentCheckpoint checkpoint);
    Optional<AgentCheckpoint> findLatest(String runId);
    List<AgentCheckpoint> findByRun(String runId);

    /** 只允许没有租约或已过期租约被抢占。返回新租约，失败返回 empty。 */
    Optional<RunLease> acquireLease(String runId, String owner, String token, Duration ttl);
    Optional<RunLease> heartbeat(String runId, String owner, String token, Duration ttl);
    boolean releaseLease(String runId, String owner, String token);
    Optional<RunLease> currentLease(String runId);
}
