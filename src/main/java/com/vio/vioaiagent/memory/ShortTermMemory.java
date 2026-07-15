package com.vio.vioaiagent.memory;

import com.vio.vioaiagent.memory.model.MemoryEntry;
import com.vio.vioaiagent.memory.model.MemoryType;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 短期记忆 — 4 种记忆类型的滑动窗口 + Token 阈值自动淘汰。
 *
 * <p>Token 阈值默认 4000。超出时从最旧条目开始淘汰，
 * 淘汰前保留一条 SUMMARY 类型摘要。
 *
 * @author vio
 */
@Slf4j
public class ShortTermMemory {

    private final Deque<MemoryEntry> entries = new ConcurrentLinkedDeque<>();
    private final int maxTokens;
    private volatile String lastSummary = "";

    public ShortTermMemory() { this(4000); }
    public ShortTermMemory(int maxTokens) { this.maxTokens = maxTokens; }

    /** 添加一条记忆 */
    public void add(MemoryEntry entry) {
        entries.addLast(entry);
        evictIfNeeded();
    }

    /** 批量添加 */
    public void addAll(List<MemoryEntry> list) {
        entries.addAll(list);
        evictIfNeeded();
    }

    /** 获取所有条目 */
    public List<MemoryEntry> getAll() { return new ArrayList<>(entries); }

    /** 当前 Token 总数 */
    public int currentTokens() {
        return entries.stream().mapToInt(MemoryEntry::tokens).sum();
    }

    /** 获取最近摘要 */
    public String getLastSummary() { return lastSummary; }

    /** 按类型过滤 */
    public List<MemoryEntry> getByType(MemoryType type) {
        return entries.stream().filter(e -> e.type() == type).toList();
    }

    /** 清空 */
    public void clear() { entries.clear(); lastSummary = ""; }

    private void evictIfNeeded() {
        StringBuilder summaryBuilder = new StringBuilder();
        int evicted = 0;

        while (currentTokens() > maxTokens && !entries.isEmpty()) {
            MemoryEntry old = entries.pollFirst();
            if (old != null) {
                summaryBuilder.append("[").append(old.type()).append("] ")
                        .append(old.content().substring(0, Math.min(80, old.content().length())))
                        .append("\n");
                evicted++;
            }
        }

        if (evicted > 0) {
            lastSummary = "淘汰了 " + evicted + " 条记忆:\n" + summaryBuilder;
            // 将淘汰摘要作为 SUMMARY 类型重新加入
            if (lastSummary.length() > 10) {
                entries.addFirst(MemoryEntry.of(MemoryType.SUMMARY,
                        lastSummary, "system", 8));
            }
            log.debug("短期记忆淘汰: {} 条, 当前 Token: {}", evicted, currentTokens());
        }
    }
}
