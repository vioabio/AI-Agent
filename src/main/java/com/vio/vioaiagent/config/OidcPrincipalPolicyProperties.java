package com.vio.vioaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OIDC 主体→租户→角色→工具的单一、非 Secret 配置契约。
 *
 * <p>空映射不是“允许全部”，而是明确拒绝所有 OIDC 工具调用。生产环境应
 * 通过配置中心/受控配置文件提供精确的 tenantRoles 与 roleTools；不接受
 * 请求头、Run body 或模型输出中的角色覆盖。</p>
 */
@ConfigurationProperties(prefix = "vio.security.oidc.policy")
public class OidcPrincipalPolicyProperties {
    private boolean enabled = true;
    private boolean required = true;
    private boolean requireTenant = true;
    private String tenantClaim = "tenant_id";
    private String policyVersion = "v1";
    private long bindingTtlSeconds = 300;
    private int maxRequestsPerMinute = 60;
    /** 可选的显式 principal→tenant 绑定；非空时未知主体拒绝。 */
    private Map<String, String> principalTenants = new LinkedHashMap<>();
    /** tenant→允许的角色；未知 tenant/角色默认拒绝。 */
    private Map<String, List<String>> tenantRoles = new LinkedHashMap<>();
    /** role→工具白名单；为空时仅从能力卡 allowedRoles 计算，仍受 tenantRoles 限制。 */
    private Map<String, List<String>> roleTools = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isRequired() { return required; }
    public boolean required() { return required; }
    public void setRequired(boolean required) { this.required = required; }

    public boolean isRequireTenant() { return requireTenant; }
    public boolean requireTenant() { return requireTenant; }
    public void setRequireTenant(boolean requireTenant) { this.requireTenant = requireTenant; }

    public String getTenantClaim() { return trim(tenantClaim); }
    public String tenantClaim() { return getTenantClaim(); }
    public void setTenantClaim(String value) { tenantClaim = trim(value); }

    public String getPolicyVersion() { return trim(policyVersion); }
    public String policyVersion() { return getPolicyVersion(); }
    public void setPolicyVersion(String value) { policyVersion = trim(value); }

    public long getBindingTtlSeconds() { return bindingTtlSeconds; }
    public long bindingTtlSeconds() { return bindingTtlSeconds; }
    public void setBindingTtlSeconds(long value) { bindingTtlSeconds = value; }

    public int getMaxRequestsPerMinute() { return maxRequestsPerMinute; }
    public int maxRequestsPerMinute() { return maxRequestsPerMinute; }
    public void setMaxRequestsPerMinute(int value) { maxRequestsPerMinute = value; }

    public Map<String, String> getPrincipalTenants() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(principalTenants));
    }
    public Map<String, String> principalTenants() { return getPrincipalTenants(); }
    public void setPrincipalTenants(Map<String, String> values) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        if (values != null) values.forEach((key, value) -> {
            if (key != null && value != null) copy.put(key.trim(), value.trim());
        });
        principalTenants = copy;
    }

    public Map<String, List<String>> getTenantRoles() {
        return immutableMap(tenantRoles);
    }
    public Map<String, List<String>> tenantRoles() { return getTenantRoles(); }
    public void setTenantRoles(Map<String, List<String>> values) {
        tenantRoles = normalizeMap(values, false);
    }

    public Map<String, List<String>> getRoleTools() {
        return immutableMap(roleTools);
    }
    public Map<String, List<String>> roleTools() { return getRoleTools(); }
    public void setRoleTools(Map<String, List<String>> values) {
        roleTools = normalizeMap(values, true);
    }

    /**
     * 只做本地字段校验，不发现网络、不读取 Secret。调用方可在启动门禁中使用；
     * 映射为空不作为配置语法错误，而是在 OIDC 请求时安全拒绝。
     */
    public List<String> validationErrors() {
        List<String> errors = new ArrayList<>();
        if (!enabled) {
            if (required) errors.add("OIDC policy 被标记为 required，但 enabled=false");
            return List.copyOf(errors);
        }
        if (!tenantClaim.matches("^[A-Za-z][A-Za-z0-9_-]{0,63}$")) {
            errors.add("tenantClaim 不是合法 claim 名称");
        }
        if (!policyVersion.matches("^[A-Za-z0-9._-]{1,128}$")) {
            errors.add("policyVersion 不是合法版本标识");
        }
        if (bindingTtlSeconds < 30 || bindingTtlSeconds > 3_600) {
            errors.add("bindingTtlSeconds 必须在 30..3600 之间");
        }
        if (maxRequestsPerMinute < 0 || maxRequestsPerMinute > 1_000_000) {
            errors.add("maxRequestsPerMinute 必须在 0..1000000 之间");
        }
        principalTenants.forEach((principal, tenant) -> {
            if (!validId(principal, 256) || !validId(tenant, 128)) {
                errors.add("principalTenants 含非法主体或租户标识");
            }
        });
        tenantRoles.forEach((tenant, roles) -> {
            if (!validId(tenant, 128) || roles == null || roles.isEmpty()) {
                errors.add("tenantRoles 必须为每个租户配置至少一个角色");
            }
            if (roles != null) for (String role : roles) if (!validRole(role)) {
                errors.add("tenantRoles 含非法角色标识");
            }
        });
        roleTools.forEach((role, tools) -> {
            if (!validRole(role) || tools == null || tools.isEmpty()) {
                errors.add("roleTools 必须为每个角色配置至少一个工具");
            }
            if (tools != null) for (String tool : tools) if (!validId(tool, 256)) {
                errors.add("roleTools 含非法工具标识");
            }
        });
        return List.copyOf(new LinkedHashSet<>(errors));
    }

    private Map<String, List<String>> normalizeMap(Map<String, List<String>> values, boolean roleKeys) {
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        if (values == null) return copy;
        values.forEach((key, list) -> {
            if (key == null) return;
            String normalizedKey = roleKeys ? key.trim().toLowerCase(Locale.ROOT) : key.trim();
            LinkedHashSet<String> entries = new LinkedHashSet<>();
            if (list != null) for (String value : list) {
                if (value != null && !value.isBlank()) {
                    entries.add(roleKeys ? value.trim() : value.trim().toLowerCase(Locale.ROOT));
                }
            }
            copy.put(normalizedKey, List.copyOf(entries));
        });
        return copy;
    }

    private Map<String, List<String>> immutableMap(Map<String, List<String>> values) {
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        if (values != null) values.forEach((key, value) ->
                copy.put(key, value == null ? List.of() : List.copyOf(value)));
        return Collections.unmodifiableMap(copy);
    }

    private boolean validRole(String value) {
        return value != null && value.matches("^[A-Za-z][A-Za-z0-9._:-]{0,63}$");
    }

    private boolean validId(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
                && value.chars().noneMatch(Character::isISOControl);
    }

    private String trim(String value) { return value == null ? "" : value.trim(); }
}
