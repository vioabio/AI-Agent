package com.vio.vioaiagent.security;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 参数脱敏器 — 自动检测并替换敏感字段值.
 *
 * <p>在写入审计日志前对参数进行脱敏处理, 防止敏感信息
 * （密码、Token、API Key 等）泄露到日志文件中.
 *
 * <p>脱敏策略：匹配敏感字段名 → 值替换为 {@code ***REDACTED***}.
 * 采用大小写不敏感的包含匹配（如 "apiKey", "api_key", "API_KEY" 均命中）.
 *
 * @author vio
 */
public class ParameterMasker {

    /** 敏感字段关键词集合 */
    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "password", "passwd", "pass",
            "token", "apikey", "api_key", "api-key",
            "secret", "key",
            "authorization",
            "credential",
            "private"
    );

    /** 脱敏替换值 */
    public static final String REDACTED = "***REDACTED***";

    /**
     * 对参数 Map 进行脱敏处理后返回新 Map（不修改原 Map）.
     *
     * @param params 原始参数
     * @return 脱敏后的参数副本
     */
    public Map<String, Object> mask(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return params;
        }

        Map<String, Object> masked = new HashMap<>(params);
        for (String key : params.keySet()) {
            if (isSensitive(key)) {
                masked.put(key, REDACTED);
            }
        }
        return masked;
    }

    /**
     * 判断字段名是否为敏感字段.
     */
    private boolean isSensitive(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String lower = fieldName.toLowerCase();
        return SENSITIVE_KEYWORDS.stream().anyMatch(lower::contains);
    }
}
