package com.vio.vioaiagent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vio.vioaiagent.memory.model.MemoryEntry;
import com.vio.vioaiagent.memory.model.MemoryType;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 长期记忆 — JSON 文件持久化 + MD5 去重 + 关键词检索。
 *
 * <p>存储位置: {@code user.dir/memory/long-term.json}
 * 启动时自动加载，每次 add 自动持久化。
 *
 * @author vio
 */
@Slf4j
public class LongTermMemory {

    private final Map<String, MemoryEntry> store = new ConcurrentHashMap<>();
    private final Map<String, String> dedupHashes = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final File storageFile;

    public LongTermMemory() {
        this(new File(System.getProperty("user.dir"), "memory/long-term.json"));
    }

    public LongTermMemory(File storageFile) {
        this.storageFile = storageFile;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        load();
    }

    /** 持久化加载 */
    private void load() {
        if (!storageFile.exists()) {
            log.info("长期记忆文件不存在，将创建: {}", storageFile.getAbsolutePath());
            return;
        }
        try {
            List<Map<String, Object>> raw = mapper.readValue(storageFile,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> m : raw) {
                try {
                    MemoryEntry entry = mapToEntry(m);
                    store.put(entry.id(), entry);
                    dedupHashes.put(entry.id(), md5(entry.content()));
                } catch (Exception e) {
                    log.warn("跳过损坏的记忆条目: {}", e.getMessage());
                }
            }
            log.info("长期记忆已加载: {} 条, 文件={}", store.size(), storageFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("长期记忆加载失败: {}", e.getMessage());
        }
    }

    /** 添加记忆（自动去重） */
    public boolean add(MemoryEntry entry) {
        String hash = md5(entry.content());
        // 去重检测
        if (dedupHashes.containsValue(hash)) {
            return false; // 重复内容
        }
        store.put(entry.id(), entry);
        dedupHashes.put(entry.id(), hash);
        persist();
        return true;
    }

    /** 关键词搜索（简易：contains 匹配 + 时间衰减排序） */
    public List<MemoryEntry> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        String lower = query.toLowerCase();
        return store.values().stream()
                .filter(e -> e.content().toLowerCase().contains(lower))
                .sorted(Comparator.comparingDouble(e ->
                        -e.importance() * Math.exp(-0.1 * e.ageInHours())))
                .limit(10)
                .collect(Collectors.toList());
    }

    /** 提取所有关键事实（FACT 类型 + importance >= 7） */
    public List<MemoryEntry> extractKeyFacts() {
        return store.values().stream()
                .filter(e -> e.type() == MemoryType.FACT && e.importance() >= 7)
                .sorted(Comparator.comparingInt(MemoryEntry::importance).reversed())
                .limit(20)
                .toList();
    }

    /** 所有条目数 */
    public int size() { return store.size(); }

    /** JSON 持久化 */
    private void persist() {
        storageFile.getParentFile().mkdirs();
        try {
            List<Map<String, Object>> list = store.values().stream()
                    .map(this::entryToMap).toList();
            mapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, list);
        } catch (IOException e) {
            log.error("长期记忆持久化失败: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private MemoryEntry mapToEntry(Map<String, Object> m) {
        return new MemoryEntry(
                (String) m.get("id"),
                MemoryType.valueOf((String) m.get("type")),
                (String) m.get("content"),
                (String) m.get("sessionId"),
                ((Number) m.getOrDefault("importance", 5)).intValue(),
                ((Number) m.getOrDefault("tokens", 0)).intValue(),
                java.time.Instant.parse((String) m.get("timestamp")),
                (Map<String, Object>) m.getOrDefault("metadata", Map.of()));
    }

    private Map<String, Object> entryToMap(MemoryEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("type", e.type().name());
        m.put("content", e.content());
        m.put("sessionId", e.sessionId());
        m.put("importance", e.importance());
        m.put("tokens", e.tokens());
        m.put("timestamp", e.timestamp().toString());
        m.put("metadata", e.metadata());
        return m;
    }

    private String md5(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return input; }
    }
}
