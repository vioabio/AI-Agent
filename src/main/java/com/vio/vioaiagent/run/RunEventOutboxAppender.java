package com.vio.vioaiagent.run;

import java.util.Map;

/**
 * 原子追加 Run 事件及其事务 outbox 的边界。
 *
 * <p>生产实现必须在同一个数据库事务中完成两次写入；如果应用在两次
 * 写入之间崩溃，事件流和 outbox 会出现不可恢复的分叉。</p>
 */
public interface RunEventOutboxAppender {
    AgentEvent append(AgentRun run, AgentEventType type, long eventIdHint,
                      Map<String, Object> payload);
}
