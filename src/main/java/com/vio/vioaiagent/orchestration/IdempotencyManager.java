package com.vio.vioaiagent.orchestration;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等管理器 — 防止同一个工具调用被重复执行.
 *
 * <p>幂等 Key = MD5(sessionId + ":" + stepIndex + ":" + toolName + ":" + paramsHash).
 * 已执行的调用结果缓存 24 小时, 重复请求直接返回缓存结果.
 *
 * @author vio
 */
@Slf4j
public class IdempotencyManager {

    /** 缓存: idempotencyKey → 执行结果 */
    private final Map<String, String> resultCache = new ConcurrentHashMap<>();
    /** 缓存过期时间 */
    private final Duration cacheTtl;

    public IdempotencyManager() {
        this(Duration.ofHours(24));
    }

    public IdempotencyManager(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    /**
     * 生成幂等 Key.
     */
    public String generateKey(String sessionId, int stepIndex, String toolName, String paramsJson) {
        String raw = sessionId + ":" + stepIndex + ":" + toolName + ":" + paramsJson;
        return md5(raw);
    }

    /**
     * 检查是否为重复调用（已执行过）.
     */
    public boolean isDuplicate(String idempotencyKey) {
        return resultCache.containsKey(idempotencyKey);
    }

    /**
     * 获取已缓存的执行结果.
     */
    public String getCachedResult(String idempotencyKey) {
        return resultCache.get(idempotencyKey);
    }

    /**
     * 记录执行结果（用于后续幂等返回）.
     */
    public void record(String idempotencyKey, String result) {
        resultCache.put(idempotencyKey, result);
        log.debug("幂等记录已保存: {}", idempotencyKey);
    }

    /** 缓存条目数 */
    public int cacheSize() {
        return resultCache.size();
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 算法不可用", e);
        }
    }
}
