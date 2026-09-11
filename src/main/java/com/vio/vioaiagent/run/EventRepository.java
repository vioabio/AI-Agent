package com.vio.vioaiagent.run;

import java.time.Instant;
import java.util.List;

/**
 * 事件追加和游标读取边界。
 *
 * <p>实现必须保证同一 Run 的事件号连续递增、终态唯一，并在终态事件
 * 已落库后拒绝新的非终态事件；这样跨实例的旧 worker 也不能重新打开
 * 已结束的事件流。</p>
 */
public interface EventRepository {
    AgentEvent append(AgentEvent event);

    /**
     * 为同一 Run 原子分配下一个事件序号并追加事件。默认实现保留旧仓储兼容性；
     * 生产 JDBC 实现会在数据库行锁内分配序号，避免多实例同时计算 MAX+1。
     */
    default AgentEvent appendNext(AgentEvent event) {
        return append(event);
    }

    List<AgentEvent> findAfter(String runId, long afterEventId);
    long lastEventId(String runId);
    boolean hasTerminalEvent(String runId);
    long retainedFrom(String runId);

    /** 删除指定游标之前的事件；默认实现保持兼容，生产实现应按批次执行并记录保留元数据。 */
    default int purgeBefore(String runId, long eventIdExclusive, int batchSize) { return 0; }

    /**
     * 按保留截止时间批量清理已经完成的 Run 事件。
     *
     * <p>默认实现保持向后兼容并且不删除任何数据。生产实现必须保留终态事件，
     * 跳过仍有未发布 outbox 的事件，并将清理结果写入保留账本。</p>
     */
    default int purgeExpired(String retentionClass, Instant cutoff, int batchSize) { return 0; }

    /**
     * 带保留类别的游标清理重载。旧实现只实现三参数方法时仍能工作，
     * 生产实现可覆盖该方法以把类别写入保留账本。
     */
    default int purgeBefore(String runId, String retentionClass,
                            long eventIdExclusive, int batchSize) {
        return purgeBefore(runId, eventIdExclusive, batchSize);
    }
}
