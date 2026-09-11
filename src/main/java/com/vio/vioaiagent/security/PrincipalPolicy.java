package com.vio.vioaiagent.security;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 已认证主体在一次 Run 中使用的最小授权快照。
 *
 * <p>该对象只保存稳定主体、租户、角色、工具白名单和短期绑定摘要，绝不
 * 保存 JWT、Bearer token 或其它 Secret。它可以跨线程传给异步 Run worker；
 * worker 不需要依赖请求线程中的 Spring SecurityContext。</p>
 */
public record PrincipalPolicy(
        String principalId,
        String tenantId,
        Set<String> roles,
        Set<String> allowedTools,
        String policyVersion,
        Instant expiresAt,
        TokenBinding tokenBinding) {

    /** 便于纯单测/嵌入式调用的最小构造器；空工具集合表示拒绝所有工具。 */
    public PrincipalPolicy(String principalId, String tenantId,
                           Set<String> roles, Set<String> allowedTools) {
        this(principalId, tenantId, roles, allowedTools, "v1", null, null);
    }

    public PrincipalPolicy {
        principalId = required(principalId, "principalId");
        tenantId = required(tenantId, "tenantId");
        roles = normalizeRoles(roles);
        allowedTools = normalizeTools(allowedTools);
        policyVersion = policyVersion == null || policyVersion.isBlank()
                ? "v1" : policyVersion.trim();
        if (policyVersion.length() > 128 || hasControl(policyVersion)) {
            throw new IllegalArgumentException("policyVersion 不合法");
        }
        if (tokenBinding != null) {
            if (!tokenBinding.isOidcPolicyBinding()) {
                throw new IllegalArgumentException("PrincipalPolicy 必须使用 OIDC policy binding");
            }
            if (!principalId.equals(tokenBinding.subjectId())
                    || !tenantId.equals(tokenBinding.tenantId())
                    || !policyVersion.equals(tokenBinding.policyVersion())
                    || !roles.equals(tokenBinding.roles())
                    || !allowedTools.equals(tokenBinding.allowedTools())) {
                throw new IllegalArgumentException("PrincipalPolicy 与 TokenBinding 快照不一致");
            }
            if (expiresAt == null || tokenBinding.expiresAt() == null
                    || !expiresAt.equals(tokenBinding.expiresAt())) {
                throw new IllegalArgumentException("PrincipalPolicy 与 TokenBinding 过期时间不一致");
            }
        }
    }

    public boolean allowsTool(String toolName) {
        return !isExpired() && toolName != null && !toolName.isBlank()
                && allowedTools.contains(toolName.trim());
    }

    public boolean allowsRole(String role) {
        return role != null && roles.contains(role.trim().toLowerCase(Locale.ROOT));
    }

    public boolean isExpired() {
        return expiresAt != null && !Instant.now().isBefore(expiresAt);
    }

    public boolean isOidc() {
        return tokenBinding != null && tokenBinding.isOidcPolicyBinding();
    }

    /** 稳定的非 Secret 快照摘要，供审计/事件关联；不包含原始凭据。 */
    public String fingerprint() {
        String sortedRoles = roles.stream().sorted().collect(java.util.stream.Collectors.joining(","));
        String sortedTools = allowedTools.stream().sorted().collect(java.util.stream.Collectors.joining(","));
        return TokenBinding.sha256Hex(String.join("\u001f", principalId, tenantId,
                policyVersion, sortedRoles, sortedTools));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256 || hasControl(value)) {
            throw new IllegalArgumentException(field + " 不能为空或包含非法字符");
        }
        return value.trim();
    }

    private static Set<String> normalizeRoles(Set<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) result.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(result);
    }

    private static Set<String> normalizeTools(Set<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank() && value.length() <= 256 && !hasControl(value)) {
                    result.add(value.trim());
                }
            }
        }
        return Set.copyOf(result);
    }

    private static boolean hasControl(String value) {
        return value != null && value.chars().anyMatch(Character::isISOControl);
    }
}
