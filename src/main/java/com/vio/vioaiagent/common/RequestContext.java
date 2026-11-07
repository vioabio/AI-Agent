package com.vio.vioaiagent.common;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求上下文 —— 存储当前请求的相关信息（如用户信息、TraceId 等）
 * <p>
 * 通过 Filter 在请求开始时初始化，请求结束时清理。
 */
public final class RequestContext {

    private static final ThreadLocal<Map<String, Object>> CONTEXT = ThreadLocal.withInitial(ConcurrentHashMap::new);

    private static final String KEY_TRACE_ID = "traceId";
    /** 显式业务 Run 标识；与 traceId 分离，避免两种命名空间混用。 */
    private static final String KEY_RUN_ID = "runId";
    /**
     * 认证适配器生成的稳定主体标识。该值可用于审计/角色映射，不能直接放入响应。
     */
    private static final String KEY_PRINCIPAL_ID = "principalId";
    /** 内部归属键（issuer+tenant+subject 派生）；见 ADR-002。 */
    private static final String KEY_OWNER_ID = "ownerId";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_REQUEST_URI = "requestUri";
    private static final String KEY_CLIENT_IP = "clientIp";
    /** 已认证主体所属租户；只保存非 Secret 标识。 */
    private static final String KEY_TENANT_ID = "tenantId";
    /** 认证适配器解析出的不可变角色快照。 */
    private static final String KEY_ROLES = "roles";
    /** principal→tenant→role→tool 的短期策略快照。 */
    private static final String KEY_PRINCIPAL_POLICY = "principalPolicy";
    /** 认证阶段解析出的令牌绑定；仅存于当前请求线程，不会写入持久化 Run。 */
    private static final String KEY_TOKEN_BINDING = "tokenBinding";

    private RequestContext() {
    }

    // ==================== 存取 ====================

    /**
     * 写入上下文。
     *
     * <p>null 一律按"清除该键"处理，而不是执行 {@code map.put(key, null)}：
     * 底层是 {@link ConcurrentHashMap}，它**不允许 null 值**，直接 put null 会抛
     * NullPointerException。这类异常最容易被误读成"字段没生效"——
     * 实际是整个请求在拦截器里就失败了。把语义定为"写 null = 清除"可以一次性
     * 消除这个陷阱，也让所有可空的 setter（tenantId、roles、tokenBinding、ownerId…）
     * 不必各自写一遍判空。
     */
    public static void set(String key, Object value) {
        if (value == null) {
            CONTEXT.get().remove(key);
            return;
        }
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

    public static void setRunId(String runId) {
        if (runId == null || runId.isBlank()) CONTEXT.get().remove(KEY_RUN_ID);
        else set(KEY_RUN_ID, runId.trim());
    }

    public static String getRunId() {
        return getString(KEY_RUN_ID);
    }

    /**
     * 设置当前请求的稳定主体标识。
     *
     * <p>与历史 {@link #getUserId()} 数字兼容字段并存，便于逐步迁移到
     * OIDC/Session 主体，而不是让业务层继续使用 API Key 本身作为身份。</p>
     */
    public static void setPrincipalId(String principalId) {
        set(KEY_PRINCIPAL_ID, principalId);
    }

    public static String getPrincipalId() {
        return getString(KEY_PRINCIPAL_ID);
    }

    /**
     * 资源归属键（ADR-002 步骤 1）。
     *
     * <p>与 {@link #getPrincipalId()} 的区别：{@code principalId} 是**外部标识**
     * （OIDC 下即裸 {@code sub}），只保证在单个 issuer 内唯一；本字段是
     * **内部归属键**，同时承载 issuer / tenant / subject（见
     * {@code InternalPrincipalKey}），因此可以作为持久化的 owner 值。
     *
     * <p>刻意与 {@code principalId} 并存而不是替换它：现有数据行以旧格式存储，
     * 在不做迁移的情况下改变 owner 语义会导致既有资源"消失"。迁移与双读流程
     * 见 ADR-002 §3，本字段先提供正确的形状，再由各入口逐步切换。
     */
    public static void setOwnerId(String ownerId) {
        set(KEY_OWNER_ID, ownerId);
    }

    public static String getOwnerId() {
        return getString(KEY_OWNER_ID);
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

    public static void setTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) CONTEXT.get().remove(KEY_TENANT_ID);
        else set(KEY_TENANT_ID, tenantId.trim());
    }

    public static String getTenantId() {
        return getString(KEY_TENANT_ID);
    }

    public static void setRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty()) CONTEXT.get().remove(KEY_ROLES);
        else set(KEY_ROLES, Set.copyOf(roles));
    }

    @SuppressWarnings("unchecked")
    public static Set<String> getRoles() {
        Object value = CONTEXT.get().get(KEY_ROLES);
        return value instanceof Set<?> ? (Set<String>) value : Set.of();
    }

    public static void setPrincipalPolicy(com.vio.vioaiagent.security.PrincipalPolicy policy) {
        if (policy == null) CONTEXT.get().remove(KEY_PRINCIPAL_POLICY);
        else set(KEY_PRINCIPAL_POLICY, policy);
    }

    public static com.vio.vioaiagent.security.PrincipalPolicy getPrincipalPolicy() {
        return get(KEY_PRINCIPAL_POLICY);
    }

    public static void setTokenBinding(com.vio.vioaiagent.security.TokenBinding binding) {
        if (binding == null) {
            CONTEXT.get().remove(KEY_TOKEN_BINDING);
        } else {
            set(KEY_TOKEN_BINDING, binding);
        }
    }

    public static com.vio.vioaiagent.security.TokenBinding getTokenBinding() {
        return get(KEY_TOKEN_BINDING);
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
