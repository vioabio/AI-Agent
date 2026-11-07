package com.vio.vioaiagent.security;

import com.vio.vioaiagent.config.OidcPrincipalPolicyProperties;
import com.vio.vioaiagent.config.OidcProperties;
import com.vio.vioaiagent.tools.governance.ToolCapabilityCard;
import com.vio.vioaiagent.tools.governance.ToolCapabilityCatalog;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 将已验签的 OIDC Authentication 映射为单一的 principal→tenant→role→tool
 * 策略快照。
 *
 * <p>该类只消费 Spring Security 已经验证过的身份，不负责 JWT 验签、网络发现
 * 或 token exchange。所有映射均来自签名 claims/authorities 与显式配置；缺少
 * subject、tenant、角色、租户绑定或工具白名单时返回拒绝结果，不使用默认租户
 * 或默认管理员角色。</p>
 */
@Component
public class OidcPrincipalPolicyAdapter {
    private final OidcProperties oidcProperties;
    private final OidcPrincipalPolicyProperties policyProperties;
    private final Clock clock;

    @Autowired
    public OidcPrincipalPolicyAdapter(OidcProperties oidcProperties,
                                      OidcPrincipalPolicyProperties policyProperties) {
        this(oidcProperties, policyProperties, Clock.systemUTC());
    }

    public OidcPrincipalPolicyAdapter(OidcProperties oidcProperties,
                                      OidcPrincipalPolicyProperties policyProperties, Clock clock) {
        this.oidcProperties = oidcProperties == null ? new OidcProperties() : oidcProperties;
        this.policyProperties = policyProperties == null
                ? new OidcPrincipalPolicyProperties() : policyProperties;
        this.clock = clock;
    }

    /**
     * 解析当前认证。OIDC 未启用时返回 {@code notApplicable}，使旧 API-Key 和
     * 程序化调用路径保持不变；OIDC 启用但策略未配置时明确拒绝。
     */
    public PrincipalPolicyDecision resolve(Authentication authentication) {
        return resolve(authentication, null);
    }

    /** A browser deadline must come from the server-side session lifetime filter. */
    public PrincipalPolicyDecision resolve(Authentication authentication, Instant maximumExpiresAt) {
        if (!oidcProperties.enabled()) return PrincipalPolicyDecision.notApplicable();
        if (!policyProperties.enabled()) return PrincipalPolicyDecision.deny("OIDC 主体策略未启用");
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return PrincipalPolicyDecision.deny("缺少已认证 OIDC 主体");
        }
        Instant now = clock.instant();
        if (OidcSessionLifetime.isBrowserAuthentication(authentication)
                && (maximumExpiresAt == null || !now.isBefore(maximumExpiresAt))) {
            return PrincipalPolicyDecision.deny("OIDC 浏览器会话缺少有效绝对期限");
        }
        if (!policyProperties.validationErrors().isEmpty()) {
            return PrincipalPolicyDecision.deny("OIDC 主体策略配置无效");
        }

        Map<String, Object> claims = claims(authentication);
        String principal = firstText(authentication.getName(),
                textClaim(claims, oidcProperties.subjectClaim()).orElse(null));
        if (principal == null || principal.isBlank()) {
            return PrincipalPolicyDecision.deny("OIDC 主体缺少 subject");
        }
        principal = principal.trim();

        String claimTenant = scalarClaim(claims, policyProperties.tenantClaim()).orElse(null);
        String tenant = resolveTenant(principal, claimTenant);
        if (tenant == null || tenant.isBlank()) {
            return PrincipalPolicyDecision.deny("OIDC 主体缺少有效 tenant");
        }

        Set<String> claimedRoles = extractRoles(authentication, claims);
        if (claimedRoles.isEmpty()) return PrincipalPolicyDecision.deny("OIDC 主体缺少有效 role");
        List<String> configuredTenantRoles = policyProperties.tenantRoles().get(tenant);
        if (configuredTenantRoles == null || configuredTenantRoles.isEmpty()) {
            return PrincipalPolicyDecision.deny("tenant 未配置角色策略");
        }
        Set<String> tenantRoles = normalizeRoles(configuredTenantRoles);
        Set<String> effectiveRoles = new LinkedHashSet<>(claimedRoles);
        effectiveRoles.retainAll(tenantRoles);
        if (effectiveRoles.isEmpty()) return PrincipalPolicyDecision.deny("主体角色不属于该 tenant");

        Set<String> allowedTools = toolsFor(effectiveRoles);
        if (allowedTools.isEmpty()) return PrincipalPolicyDecision.deny("主体角色未配置工具策略");

        Instant expiresAt = now.plusSeconds(policyProperties.bindingTtlSeconds());
        if (maximumExpiresAt != null && maximumExpiresAt.isBefore(expiresAt)) expiresAt = maximumExpiresAt;
        if (authentication instanceof JwtAuthenticationToken jwt && jwt.getToken().getExpiresAt() != null
                && jwt.getToken().getExpiresAt().isBefore(expiresAt)) expiresAt = jwt.getToken().getExpiresAt();
        if (!now.isBefore(expiresAt)) return PrincipalPolicyDecision.deny("OIDC 身份已过期");
        TokenBinding binding;
        try {
            binding = TokenBinding.oidc(principal, tenant, effectiveRoles, allowedTools,
                    policyProperties.policyVersion(), policyProperties.maxRequestsPerMinute(), expiresAt);
        } catch (RuntimeException ex) {
            return PrincipalPolicyDecision.deny("OIDC 工具绑定生成失败");
        }
        PrincipalPolicy policy;
        try {
            policy = new PrincipalPolicy(principal, tenant, effectiveRoles, allowedTools,
                    policyProperties.policyVersion(), expiresAt, binding);
        } catch (RuntimeException ex) {
            return PrincipalPolicyDecision.deny("OIDC 主体策略快照无效");
        }
        return PrincipalPolicyDecision.allow(policy);
    }

    /** 对已解析快照和能力卡执行二次工具级门禁。 */
    public PrincipalPolicyDecision authorize(PrincipalPolicy policy,
                                             ToolCapabilityCard card) {
        if (policy == null || !policy.isOidc() || policy.isExpired()
                || policy.tokenBinding() == null || policy.tokenBinding().isExpired()) {
            return PrincipalPolicyDecision.deny("OIDC 主体策略已过期或缺少短期绑定");
        }
        if (card == null || card.name() == null || card.name().isBlank()) {
            return PrincipalPolicyDecision.deny("工具能力卡缺失");
        }
        if (!policy.allowsTool(card.name())) {
            return PrincipalPolicyDecision.deny("主体策略未授权该工具");
        }
        boolean roleMatches = card.allowedRoles().stream().map(this::normalizeRole)
                .anyMatch(policy.roles()::contains);
        if (!roleMatches) return PrincipalPolicyDecision.deny("主体角色与工具能力卡不匹配");
        return PrincipalPolicyDecision.allow(policy);
    }

    /** 便捷的 resolve + tool authorization。 */
    public PrincipalPolicyDecision authorize(Authentication authentication,
                                             ToolCapabilityCard card) {
        PrincipalPolicyDecision resolved = resolve(authentication);
        if (!resolved.allowed() || resolved.policy() == null) return resolved;
        return authorize(resolved.policy(), card);
    }

    /** 配置对象只读暴露，便于启动诊断/测试；不含 Secret。 */
    public OidcPrincipalPolicyProperties properties() { return policyProperties; }

    private String resolveTenant(String principal, String claimTenant) {
        Map<String, String> explicit = policyProperties.principalTenants();
        if (!explicit.isEmpty()) {
            String mapped = explicit.get(principal);
            if (mapped == null || mapped.isBlank()) return null;
            if (claimTenant != null && !claimTenant.equals(mapped)) return null;
            return mapped;
        }
        if (policyProperties.requireTenant() && (claimTenant == null || claimTenant.isBlank())) return null;
        return claimTenant;
    }

    private Set<String> toolsFor(Set<String> roles) {
        Map<String, List<String>> configured = policyProperties.roleTools();
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        if (!configured.isEmpty()) {
            for (String role : roles) {
                List<String> values = configured.get(role);
                if (values != null) tools.addAll(values);
            }
            return Set.copyOf(tools);
        }
        // No roleTools map means use the versioned capability catalog's role
        // matrix. Dynamic/MCP tools therefore remain denied until explicitly mapped.
        for (ToolCapabilityCard card : ToolCapabilityCatalog.all().values()) {
            if (card.allowedRoles().stream().map(this::normalizeRole).anyMatch(roles::contains)) {
                tools.add(card.name());
            }
        }
        return Set.copyOf(tools);
    }

    private Set<String> extractRoles(Authentication authentication, Map<String, Object> claims) {
        LinkedHashSet<String> claimRoles = new LinkedHashSet<>();
        Object claim = claims.get(oidcProperties.roleClaim());
        addRoleValues(claimRoles, claim);
        LinkedHashSet<String> authorityRoles = new LinkedHashSet<>();
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        if (authorities != null) {
            for (GrantedAuthority authority : authorities) {
                if (authority != null) addRoleValue(authorityRoles, authority.getAuthority());
            }
        }
        if (claimRoles.isEmpty()) return Set.copyOf(authorityRoles);
        if (authorityRoles.isEmpty()) return Set.copyOf(claimRoles);
        // Resource Server converter derives ROLE_* from the configured signed
        // role claim. A mismatch indicates a custom/forged Authentication
        // adapter; take only the intersection so neither side can expand rights.
        claimRoles.retainAll(authorityRoles);
        return Set.copyOf(claimRoles);
    }

    private void addRoleValues(Set<String> roles, Object value) {
        if (value instanceof Collection<?> values) {
            for (Object item : values) addRoleValues(roles, item);
            return;
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) addRoleValues(roles, java.lang.reflect.Array.get(value, i));
            return;
        }
        if (value != null) {
            String text = String.valueOf(value);
            for (String item : text.split("[,\\s]+")) addRoleValue(roles, item);
        }
    }

    private void addRoleValue(Set<String> roles, String value) {
        if (value == null || value.isBlank()) return;
        String normalized = value.trim();
        if (normalized.regionMatches(true, 0, "ROLE_", 0, 5)) normalized = normalized.substring(5);
        if (normalized.regionMatches(true, 0, "SCOPE_", 0, 6)) return;
        if (normalized.matches("^[A-Za-z][A-Za-z0-9._:-]{0,63}$")) {
            roles.add(normalizeRole(normalized));
        }
    }

    private String normalizeRole(String role) {
        return role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
    }

    private Set<String> normalizeRoles(Collection<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) for (String value : values) {
            if (value != null && !value.isBlank()) result.add(normalizeRole(value));
        }
        return Set.copyOf(result);
    }

    private Map<String, Object> claims(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getTokenAttributes();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof OAuth2AuthenticatedPrincipal oauth) {
            return oauth.getAttributes();
        }
        if (principal instanceof Map<?, ?> map) return stringMap(map);
        Object details = authentication.getDetails();
        if (details instanceof Map<?, ?> map) return stringMap(map);
        return Map.of();
    }

    private Map<String, Object> stringMap(Map<?, ?> source) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    private Optional<String> textClaim(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        return value == null || value instanceof Collection<?> || value.getClass().isArray()
                ? Optional.empty() : Optional.ofNullable(String.valueOf(value)).filter(v -> !v.isBlank());
    }

    private Optional<String> scalarClaim(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (value == null) return Optional.empty();
        if (value instanceof Collection<?> values) {
            if (values.size() != 1) return Optional.empty();
            value = values.iterator().next();
        }
        if (value.getClass().isArray()) {
            if (java.lang.reflect.Array.getLength(value) != 1) return Optional.empty();
            value = java.lang.reflect.Array.get(value, 0);
        }
        if (value == null) return Optional.empty();
        String result = String.valueOf(value).trim();
        return result.isBlank() ? Optional.empty() : Optional.of(result);
    }

    private String firstText(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second;
    }
}
