package com.vio.vioaiagent.memory;

import com.vio.vioaiagent.memory.model.MemoryEntry;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能检索器 — 加权排序：关键词匹配 + 时间衰减 + 来源权重。
 *
 * <p>评分公式: score = keywordMatch × 0.5 + timeDecay × 0.3 + sourceWeight × 0.2
 * <ul>
 *   <li>keywordMatch: 基于简易中文/英文分词的关键词命中率</li>
 *   <li>timeDecay: exp(-0.1 × hours) — 越新越高</li>
 *   <li>sourceWeight: FACT(1.0) > CONVERSATION(0.7) > TOOL_RESULT(0.5)</li>
 * </ul>
 *
 * @author vio
 */
@Slf4j
public class MemoryRetriever {

    /**
     * 从候选中检索 topK 条最相关的记忆。
     */
    public List<MemoryEntry> retrieve(List<MemoryEntry> candidates, String query, int topK) {
        if (candidates == null || candidates.isEmpty() || query == null) {
            return Collections.emptyList();
        }

        List<String> queryKeywords = tokenize(query);
        if (queryKeywords.isEmpty()) return candidates.stream().limit(topK).toList();

        return candidates.stream()
                .map(e -> new ScoredEntry(e, score(e, queryKeywords)))
                .filter(s -> s.score > 0.05) // 过滤极低相关度
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .limit(topK)
                .map(ScoredEntry::entry)
                .collect(Collectors.toList());
    }

    private double score(MemoryEntry entry, List<String> keywords) {
        double keywordScore = keywordMatch(entry.content(), keywords); // 0-1
        double timeDecay = Math.exp(-0.1 * entry.ageInHours());        // 0-1
        double sourceWeight = entry.sourceWeight();                    // 0.5-1.0
        return keywordScore * 0.5 + timeDecay * 0.3 + sourceWeight * 0.2;
    }

    /** 关键词匹配率 */
    private double keywordMatch(String content, List<String> keywords) {
        if (content == null || keywords.isEmpty()) return 0;
        String lower = content.toLowerCase();
        long hits = keywords.stream().filter(kw -> lower.contains(kw)).count();
        return (double) hits / keywords.size();
    }

    /** 简易分词：按空格/标点分割 + 提取 2-3 字中文子串 */
    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        List<String> tokens = new ArrayList<>();

        // 空格/标点分词
        tokens.addAll(Arrays.asList(text.toLowerCase().split("[\\s，。！？、,.:;!?\\-]+")));
        tokens.removeIf(t -> t.length() < 2);

        // 中文 Bigram: 每连续 2 字作为特征
        String chinese = text.replaceAll("[^\\u4E00-\\u9FFF]", "");
        for (int i = 0; i < chinese.length() - 1; i++) {
            tokens.add(chinese.substring(i, i + 2));
        }

        return tokens.stream().distinct().collect(Collectors.toList());
    }

    private record ScoredEntry(MemoryEntry entry, double score) {}
}
