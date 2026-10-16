package com.vio.vioaiagent.run;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

/** 按 Run 保存有序事件；重复追加同一 eventId 必须是同一事件，保证重试幂等。 */
@Repository
@org.springframework.context.annotation.Profile("!prod & !demo")
@Primary
public class InMemoryEventRepository implements EventRepository {
    private final Map<String, NavigableMap<Long, AgentEvent>> events = new ConcurrentHashMap<>();

    @Override
    public AgentEvent append(AgentEvent event) {
        if (event == null) throw new IllegalArgumentException("事件不能为空");
        NavigableMap<Long, AgentEvent> stream = events.computeIfAbsent(event.runId(), ignored ->
                Collections.synchronizedNavigableMap(new TreeMap<>()));
        synchronized (stream) {
            return appendLocked(stream, event);
        }
    }

    @Override
    public AgentEvent appendNext(AgentEvent event) {
        if (event == null) throw new IllegalArgumentException("事件不能为空");
        NavigableMap<Long, AgentEvent> stream = events.computeIfAbsent(event.runId(), ignored ->
                Collections.synchronizedNavigableMap(new TreeMap<>()));
        synchronized (stream) {
            long next = stream.isEmpty() ? 1 : stream.lastKey() + 1;
            AgentEvent normalized = event.eventId() == next
                    ? event
                    : new AgentEvent(event.schemaVersion(), next, event.type(), event.sessionId(),
                    event.runId(), event.traceId(), event.timestamp(), event.payload());
            return appendLocked(stream, normalized);
        }
    }

    private AgentEvent appendLocked(NavigableMap<Long, AgentEvent> stream, AgentEvent event) {
            AgentEvent existing = stream.get(event.eventId());
            if (existing != null) {
                if (!existing.equals(event)) throw new IllegalStateException("eventId 重复且内容不一致");
                return existing;
            }
            long expected = stream.isEmpty() ? 1 : stream.lastKey() + 1;
            if (event.eventId() != expected) {
                throw new IllegalStateException("eventId 必须连续递增，期望 " + expected);
            }
            // 终态一旦落入事件流，旧 worker/重试请求不得再追加普通事件。
            // 审批拒绝的 approval.resolved 会在 run.failed 之前写入，因此
            // 这里只需依据事件流中是否已经存在终态判断，不会阻断正常顺序。
            if (hasTerminalEvent(stream) && !event.type().isTerminal()) {
                throw new IllegalStateException("终态事件后不能追加非终态事件");
            }
            if (event.type().isTerminal() && hasTerminalEvent(stream)) {
                throw new IllegalStateException("一个 Run 只能有一个终态事件");
            }
            stream.put(event.eventId(), event);
            return event;
    }

    @Override
    public List<AgentEvent> findAfter(String runId, long afterEventId) {
        NavigableMap<Long, AgentEvent> stream = events.get(runId);
        if (stream == null) return List.of();
        synchronized (stream) {
            return List.copyOf(new ArrayList<>(stream.tailMap(Math.max(0, afterEventId) + 1, true).values()));
        }
    }

    @Override
    public long lastEventId(String runId) {
        NavigableMap<Long, AgentEvent> stream = events.get(runId);
        if (stream == null) return 0;
        synchronized (stream) { return stream.isEmpty() ? 0 : stream.lastKey(); }
    }

    @Override
    public boolean hasTerminalEvent(String runId) {
        NavigableMap<Long, AgentEvent> stream = events.get(runId);
        if (stream == null) return false;
        synchronized (stream) { return hasTerminalEvent(stream); }
    }

    private boolean hasTerminalEvent(NavigableMap<Long, AgentEvent> stream) {
        return stream.values().stream().anyMatch(event -> event.type().isTerminal());
    }

    @Override
    public long retainedFrom(String runId) {
        NavigableMap<Long, AgentEvent> stream = events.get(runId);
        if (stream == null) return 1;
        synchronized (stream) { return stream.isEmpty() ? 1 : stream.firstKey(); }
    }

    @Override
    public int purgeBefore(String runId, long eventIdExclusive, int batchSize) {
        NavigableMap<Long, AgentEvent> stream = events.get(runId);
        if (stream == null) return 0;
        int limit = Math.max(1, Math.min(batchSize, 10_000));
        synchronized (stream) {
            List<Long> keys = stream.headMap(Math.max(1, eventIdExclusive), false).entrySet().stream()
                    .filter(entry -> !entry.getValue().type().isTerminal())
                    .map(Map.Entry::getKey)
                    .limit(limit).toList();
            keys.forEach(stream::remove);
            return keys.size();
        }
    }

    @Override
    public int purgeExpired(String retentionClass, Instant cutoff, int batchSize) {
        if (retentionClass == null || retentionClass.isBlank()) {
            throw new IllegalArgumentException("retentionClass 不能为空");
        }
        if (cutoff == null) throw new IllegalArgumentException("cutoff 不能为空");
        int remaining = Math.max(1, Math.min(batchSize, 10_000));
        int deleted = 0;
        // 只清理已经产生终态的 Run，并始终保留终态事件。这样本地实现也不会
        // 让活跃 Run 的事件号出现不可恢复的空洞或把唯一终态删掉。
        for (NavigableMap<Long, AgentEvent> stream : events.values()) {
            if (deleted >= remaining) break;
            synchronized (stream) {
                if (!hasTerminalEvent(stream)) continue;
                List<Long> doomed = stream.values().stream()
                        .filter(event -> event.timestamp().isBefore(cutoff) && !event.type().isTerminal())
                        .map(AgentEvent::eventId)
                        .limit(remaining - deleted)
                        .toList();
                doomed.forEach(stream::remove);
                deleted += doomed.size();
            }
        }
        return deleted;
    }
}
