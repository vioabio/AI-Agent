package com.vio.vioaiagent.security;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
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

        Set<Object> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        return maskMap(params, visiting);
    }

    /**
     * 审计参数可能包含嵌套对象、列表或数组。只处理顶层字段会让
     * {@code {"credentials":{"apiKey":"..."}}} 原样进入日志，因此所有
     * 容器都必须递归复制并脱敏；循环引用使用固定占位符终止遍历。
     */
    private Map<String, Object> maskMap(Map<?, ?> source, Set<Object> visiting) {
        if (!visiting.add(source)) {
            return Map.of("$cycle", REDACTED);
        }
        try {
            Map<String, Object> masked = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                // 容器字段（例如 credentials）不能整体替换为字符串，否则会
                // 丢失对其内部 password/apiKey 的递归脱敏；标量敏感值才直接
                // 替换，容器继续安全地向下遍历。
                masked.put(key, isSensitive(key) && !isContainer(value)
                        ? REDACTED
                        : maskValue(value, visiting));
            }
            return masked;
        } finally {
            visiting.remove(source);
        }
    }

    private Object maskValue(Object value, Set<Object> visiting) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> map) return maskMap(map, visiting);
        if (value instanceof Iterable<?> iterable) {
            if (!visiting.add(value)) return REDACTED;
            try {
                List<Object> masked = new ArrayList<>();
                for (Object item : iterable) masked.add(maskValue(item, visiting));
                return Collections.unmodifiableList(masked);
            } finally {
                visiting.remove(value);
            }
        }
        if (value.getClass().isArray()) {
            if (!visiting.add(value)) return REDACTED;
            try {
                List<Object> masked = new ArrayList<>();
                for (int i = 0; i < Array.getLength(value); i++) {
                    masked.add(maskValue(Array.get(value, i), visiting));
                }
                return Collections.unmodifiableList(masked);
            } finally {
                visiting.remove(value);
            }
        }
        return value;
    }

    private boolean isContainer(Object value) {
        return value instanceof Map<?, ?> || value instanceof Iterable<?>
                || (value != null && value.getClass().isArray());
    }

    /**
     * 判断字段名是否为敏感字段.
     */
    private boolean isSensitive(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String normalized = fieldName.toLowerCase()
                .replaceAll("[^a-z0-9]", "");
        // 仅对完整词或常见后缀命中，避免把普通字段（如 credentials 中的
        // "key" 子串、monkey 等）误判为整段敏感值。
        return SENSITIVE_KEYWORDS.stream().anyMatch(keyword -> {
            String token = keyword.replaceAll("[^a-z0-9]", "");
            if (token.equals("key")) {
                return normalized.equals("key")
                        || normalized.endsWith("apikey")
                        || normalized.endsWith("accesskey")
                        || normalized.endsWith("secretkey")
                        || normalized.endsWith("privatekey");
            }
            return normalized.equals(token) || normalized.endsWith(token);
        });
    }
}
