package com.vio.vioaiagent.memory;

import com.vio.vioaiagent.memory.model.MemoryEntry;
import com.vio.vioaiagent.memory.model.MemoryType;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩器 — Map-Reduce 策略压缩历史对话。
 *
 * <p><b>算法：</b>
 * <ol>
 *   <li><b>保留</b>: 最近 3 轮完整对话（不压缩）</li>
 *   <li><b>Map</b>: 旧消息每 5 条为一组，逐组生成摘要</li>
 *   <li><b>Reduce</b>: 合并所有组摘要 → 最终摘要</li>
 *   <li><b>提取</b>: 从摘要中提取关键事实 → 存入长期记忆</li>
 * </ol>
 *
 * <p>注意：此实现为本地压缩（无需 LLM），使用截断策略作为默认。
 * 可扩展为 LLM 驱动的 Map-Reduce（注入 ChatModel）。
 *
 * @author vio
 */
@Slf4j
public class ContextCompressor {

    private static final int RECENT_KEEP = 6;  // 保留最近 6 条（≈3 轮）
    private static final int CHUNK_SIZE = 5;    // Map 分片大小

    private final MemoryRetriever retriever;

    public ContextCompressor() {
        this.retriever = new MemoryRetriever();
    }

    /**
     * 压缩消息列表。
     *
     * @param messages 完整的消息列表
     * @return [压缩摘要, 最近消息...]
     */
    public CompressResult compress(List<MemoryEntry> messages) {
        if (messages == null || messages.isEmpty()) {
            return new CompressResult("", List.of(), List.of());
        }

        // 1. 保留最近消息
        int splitIndex = Math.max(0, messages.size() - RECENT_KEEP);
        List<MemoryEntry> recent = messages.subList(splitIndex, messages.size());
        List<MemoryEntry> older = messages.subList(0, splitIndex);

        if (older.isEmpty()) {
            return new CompressResult("", recent, List.of());
        }

        // 2. Map: 分片摘要
        List<String> chunkSummaries = new ArrayList<>();
        for (int i = 0; i < older.size(); i += CHUNK_SIZE) {
            int end = Math.min(i + CHUNK_SIZE, older.size());
            List<MemoryEntry> chunk = older.subList(i, end);
            String summary = summarizeChunk(chunk);
            chunkSummaries.add(summary);
        }

        // 3. Reduce: 合并摘要
        String combinedSummary = String.join(" | ", chunkSummaries);
        if (combinedSummary.length() > 500) {
            combinedSummary = combinedSummary.substring(0, 500) + "...";
        }

        // 4. 提取关键事实
        List<MemoryEntry> keyFacts = extractFacts(older);

        log.info("上下文压缩: {} 条 → 摘要 ({} chars) + 最近 {} 条 + {} 关键事实",
                older.size(), combinedSummary.length(), recent.size(), keyFacts.size());

        return new CompressResult(combinedSummary, recent, keyFacts);
    }

    /** 对一小组消息生成摘要（本地截断策略，可扩展 LLM） */
    private String summarizeChunk(List<MemoryEntry> chunk) {
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry e : chunk) {
            String snippet = e.content().length() > 100
                    ? e.content().substring(0, 100) + "..." : e.content();
            sb.append("[").append(e.type()).append("] ").append(snippet).append(" | ");
        }
        return sb.toString();
    }

    /** 提取重要事实（importance ≥ 7 的条目） */
    private List<MemoryEntry> extractFacts(List<MemoryEntry> entries) {
        return entries.stream()
                .filter(e -> e.importance() >= 7)
                .toList();
    }

    /**
     * 压缩结果。
     *
     * @param summary   压缩摘要文本
     * @param recent    保留的最近消息
     * @param keyFacts  提取的关键事实（可存入长期记忆）
     */
    public record CompressResult(String summary, List<MemoryEntry> recent,
                                  List<MemoryEntry> keyFacts) {}
}
