package com.vio.vioaiagent.security;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * 令牌-协议绑定模型.
 *
 * <p>每个 API Key 绑定到一个或多个协议（MCP Server）,
 * 并限制可调用的工具范围和频率. 防止令牌跨协议越权调用.
 *
 * @param tokenId      令牌标识（即 API Key 本身）
 * @param protocolId   绑定的协议 ID（如 "amap-maps", "image-search"）
 * @param allowedTools 允许调用的工具名称集合
 * @param maxRequestsPerMinute 每分钟最大请求数（0 表示不限制）
 * @param expiresAt    过期时间（null 表示永不过期）
 * @author vio
 */
public record TokenBinding(
        String tokenId,
        String protocolId,
        Set<String> allowedTools,
        int maxRequestsPerMinute,
        Instant expiresAt,
        String subjectId,
        String tenantId,
        Set<String> roles,
        String policyVersion,
        String source) {

    /** v1.0 API-Key compatibility constructor. */
    public TokenBinding(String tokenId, String protocolId, Set<String> allowedTools,
                        int maxRequestsPerMinute, Instant expiresAt) {
        this(tokenId, protocolId, allowedTools, maxRequestsPerMinute, expiresAt,
                null, null, Set.of(), "", "api-key");
    }

    /** OIDC policy convenience constructor; metadata is retained without credentials. */
    public TokenBinding(String tokenId, String protocolId, Set<String> allowedTools,
                        int maxRequestsPerMinute, Instant expiresAt, String subjectId,
                        String tenantId, Set<String> roles, String policyVersion) {
        this(tokenId, protocolId, allowedTools, maxRequestsPerMinute, expiresAt,
                subjectId, tenantId, roles, policyVersion, "oidc");
    }

    public TokenBinding {
        tokenId = tokenId == null || tokenId.isBlank() ? "anonymous" : tokenId;
        protocolId = protocolId == null || protocolId.isBlank() ? "default" : protocolId;
        allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        subjectId = subjectId == null ? "" : subjectId.trim();
        tenantId = tenantId == null ? "" : tenantId.trim();
        roles = roles == null ? Set.of() : roles.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        policyVersion = policyVersion == null ? "" : policyVersion.trim();
        source = source == null || source.isBlank() ? "api-key" : source.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("api-key", "oidc", "system").contains(source)) {
            throw new IllegalArgumentException("TokenBinding source 不受支持");
        }
        if ("oidc".equals(source)) {
            if (subjectId.isBlank() || tenantId.isBlank() || roles.isEmpty() || policyVersion.isBlank()) {
                throw new IllegalArgumentException("OIDC TokenBinding 缺少主体/租户/角色/策略版本");
            }
        }
        if (maxRequestsPerMinute < 0) {
            throw new IllegalArgumentException("maxRequestsPerMinute 不能为负数");
        }
    }

    /**
     * 创建无限制的令牌绑定（允许所有工具, 不限制频率, 永不过期）.
     */
    public static TokenBinding unlimited(String tokenId, String protocolId) {
        return new TokenBinding(tokenId, protocolId, Set.of(), 0, null);
    }

    /** 创建不含原始 JWT/Secret 的短期 OIDC 工具绑定。 */
    public static TokenBinding oidc(String subjectId, String tenantId, Set<String> roles,
                                    Set<String> allowedTools, String policyVersion,
                                    int maxRequestsPerMinute, Instant expiresAt) {
        String subject = subjectId == null ? "" : subjectId.trim();
        String tenant = tenantId == null ? "" : tenantId.trim();
        String version = policyVersion == null ? "" : policyVersion.trim();
        String tokenId = "oidc:" + sha256Hex(String.join("\u001f", subject, tenant, version));
        return new TokenBinding(tokenId, "oidc:" + tenant, allowedTools,
                maxRequestsPerMinute, expiresAt, subject, tenant, roles, version, "oidc");
    }

    /**
     * 检查令牌是否已过期.
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * 检查是否允许调用指定工具.
     *
     * @param toolName 工具名称
     * @return true 表示允许调用（allowedTools 为空表示允许所有）
     */
    public boolean allowsTool(String toolName) {
        return allowedTools.isEmpty() || allowedTools.contains(toolName);
    }

    public boolean isOidcPolicyBinding() {
        return "oidc".equals(source);
    }

    /** SHA-256 helper shared by non-secret policy fingerprints and synthetic IDs. */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JRE 不支持 SHA-256", ex);
        }
    }
}
