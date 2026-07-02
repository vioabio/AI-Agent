package com.vio.vioaiagent.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求上下文 —— 存储当前请求的相关信息（如用户信息、TraceId 等）
 * <p>
 * 通过 Filter 在请求开始时初始化，请求结束时清理。
 */
public final class RequestContext {

    private static final ThreadLocal<Map<String, Object>> CONTEXT = ThreadLocal.withInitial(ConcurrentHashMap::new);

    private static final String KEY_TRACE_ID = "traceId";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_REQUEST_URI = "requestUri";
    private static final String KEY_CLIENT_IP = "clientIp";

    private RequestContext() {
    }

    // ==================== 存取 ====================

    public static void set(String key, Object value) {
        CONTEXT.get().put(key, value);
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(String key) {
        return (T) CONTEXT.get().get(key);
    }

    public static String getString(String key) {
        Object value = CONTEXT.get().get(key);
        return value != null ? value.toString() : null;
    }

    // ==================== 便捷方法 ====================

    public static void setTraceId(String traceId) {
        set(KEY_TRACE_ID, traceId);
    }

    public static String getTraceId() {
        return getString(KEY_TRACE_ID);
    }

    public static void setUserId(Long userId) {
        set(KEY_USER_ID, userId);
    }

    public static Long getUserId() {
        return get(KEY_USER_ID);
    }

    public static void setUsername(String username) {
        set(KEY_USERNAME, username);
    }

    public static String getUsername() {
        return getString(KEY_USERNAME);
    }

    public static void setRequestUri(String uri) {
        set(KEY_REQUEST_URI, uri);
    }

    public static String getRequestUri() {
        return getString(KEY_REQUEST_URI);
    }

    public static void setClientIp(String ip) {
        set(KEY_CLIENT_IP, ip);
    }

    public static String getClientIp() {
        return getString(KEY_CLIENT_IP);
    }

    // ==================== 生命周期 ====================

    public static Map<String, Object> getAll() {
        return CONTEXT.get();
    }

    /**
     * 请求结束后必须调用，防止内存泄漏
     */
    public static void clear() {
        CONTEXT.remove();
    }
}