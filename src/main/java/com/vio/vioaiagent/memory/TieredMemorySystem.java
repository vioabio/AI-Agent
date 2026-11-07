package com.vio.vioaiagent.memory;

import com.vio.vioaiagent.memory.model.MemoryEntry;
import com.vio.vioaiagent.memory.model.MemoryType;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 三层记忆系统门面。
 *
 * <p>组合 WorkingMemory + ShortTermMemory + LongTermMemory，
 * 提供统一的 remember/recall/compress/shutdown 接口。
 *
 * <pre>{@code
 * TieredMemorySystem memory = new TieredMemorySystem();
 * memory.remember("用户喜欢宝可梦", MemoryType.FACT, 8);
 * List<MemoryEntry> result = memory.recall("宝可梦", 5);
 * String context = memory.buildContext("用户的当前问题");
 * memory.shutdown();
 * }</pre>
 *
 * @author vio
 */
@Slf4j
public class TieredMemorySystem {

    private final WorkingMemory working;
    private final ShortTermMemory shortTerm;
    private final LongTermMemory longTerm;
    private final ContextCompressor compressor;
    private final MemoryRetriever retriever;

    public TieredMemorySystem() {
        this.working = new WorkingMemory();
        this.shortTerm = new ShortTermMemory();
        this.longTerm = new LongTermMemory();
        this.compressor = new ContextCompressor();
        this.retriever = new MemoryRetriever();
    }

    /** 记录一条记忆（同时写入工作记忆和短期记忆） */
    public void remember(String content, MemoryType type, int importance) {
        MemoryEntry entry = MemoryEntry.of(type, content, "active", importance);
        if (type == MemoryType.CONVERSATION) {
            working.addConversation(entry);
        }
        shortTerm.add(entry);

        // 高重要性事实 → 同时写入长期记忆
        if (importance >= 7 && type == MemoryType.FACT) {
            if (longTerm.add(entry)) {
                log.debug("长期记忆已记录: {}", entry.id());
            }
        }
    }

    /** 记录对话（便捷方法） */
    public void rememberConversation(String content) {
        remember(content, MemoryType.CONVERSATION, 5);
    }

    /** 记录事实（便捷方法，importance ≥ 7 会写入长期记忆） */
    public void rememberFact(String content, int importance) {
        remember(content, MemoryType.FACT, importance);
    }

    /** 检索相关记忆 */
    public List<MemoryEntry> recall(String query, int topK) {
        // 聚合：短期 + 长期
        List<MemoryEntry> all = new ArrayList<>();
        all.addAll(shortTerm.getAll());
        all.addAll(longTerm.search(query));
        return retriever.retrieve(all, query, topK);
    }

    /** 构建 Agent 可用的记忆上下文 */
    public String buildContext(String currentQuery) {
        StringBuilder sb = new StringBuilder();

        // 1. 检索长期相关记忆
        List<MemoryEntry> longTermRecall = recall(currentQuery, 5);
        if (!longTermRecall.isEmpty()) {
            sb.append("[长期记忆]\n");
            for (MemoryEntry e : longTermRecall) {
                sb.append("- ").append(e.content()).append("\n");
            }
            sb.append("\n");
        }

        // 2. 短期记忆摘要
        if (!shortTerm.getLastSummary().isEmpty()) {
            sb.append("[历史摘要]\n").append(shortTerm.getLastSummary()).append("\n\n");
        }

        // 3. 工作记忆中的最近对话
        List<MemoryEntry> recent = working.recentConversations();
        if (!recent.isEmpty()) {
            sb.append("[最近对话]\n");
            for (MemoryEntry e : recent) {
                String snippet = e.content().length() > 200
                        ? e.content().substring(0, 200) + "..." : e.content();
                sb.append("- ").append(snippet).append("\n");
            }
        }

        return sb.toString();
    }

    /** 压缩记忆 */
    public void compress() {
        List<MemoryEntry> overflow = working.drainOverflow();
        if (!overflow.isEmpty()) {
            var result = compressor.compress(overflow);
            if (!result.summary().isEmpty()) {
                shortTerm.add(MemoryEntry.of(MemoryType.SUMMARY, result.summary(), "system", 8));
            }
            for (MemoryEntry fact : result.keyFacts()) {
                longTerm.add(fact);
            }
        }
    }

    /** 关闭（持久化长期记忆） */
    public void shutdown() {
        compress();
        log.info("记忆系统关闭: 短期={}, 长期={}", shortTerm.currentTokens(), longTerm.size());
    }

    /** 清空短期和工作记忆 */
    public void clearSession() { working.clear(); shortTerm.clear(); working.clearSteps(); }
}
