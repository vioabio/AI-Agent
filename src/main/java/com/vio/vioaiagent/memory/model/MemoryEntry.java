package com.vio.vioaiagent.memory.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆条目 — 三层记忆系统的统一数据单元。
 *
 * @param id         唯一标识
 * @param type       记忆类型
 * @param content    记忆内容
 * @param sessionId  来源会话 ID
 * @param importance 重要性 (1-10)
 * @param tokens     估算 Token 数
 * @param timestamp  创建时间
 * @param metadata   附加元数据
 * @author vio
 */
public record MemoryEntry(
        String id,
        MemoryType type,
        String content,
        String sessionId,
        int importance,
        int tokens,
        Instant timestamp,
        Map<String, Object> metadata) {

    public static MemoryEntry of(MemoryType type, String content, String sessionId) {
        return new MemoryEntry(
                UUID.randomUUID().toString().substring(0, 8),
                type, content, sessionId, 5, estimateTokens(content),
                Instant.now(), Map.of());
    }

    public static MemoryEntry of(MemoryType type, String content, String sessionId, int importance) {
        return new MemoryEntry(
                UUID.randomUUID().toString().substring(0, 8),
                type, content, sessionId, importance, estimateTokens(content),
                Instant.now(), Map.of());
    }

    /** 约 4 字符 = 1 token (英文) 或 1.5 字符 = 1 token (中文) */
    private static int estimateTokens(String text) {
        if (text == null) return 0;
        long chineseChars = text.codePoints().filter(c -> c >= 0x4E00 && c <= 0x9FFF).count();
        long otherChars = text.length() - chineseChars;
        return (int) (chineseChars / 1.5 + otherChars / 4);
    }

    /** 来源权重（用于检索排序） */
    public double sourceWeight() { return type.getSourceWeight(); }

    /** 年龄（小时） */
    public double ageInHours() {
        return (System.currentTimeMillis() - timestamp.toEpochMilli()) / 3600_000.0;
    }
}
