package com.vio.vioaiagent.feedback;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 反馈运营权限配置。生产环境应通过 Secret/配置中心注入 userId 到角色的映射，
 * 不允许把请求头中的角色当作认证依据。
 */
@ConfigurationProperties(prefix = "vio.feedback")
public class FeedbackProperties {
    private boolean enabled = true;
    /** 生产反馈必须绑定 answer projection；本地旧程序化调用可显式关闭。 */
    private boolean requireLineage = false;
    private Map<String, List<String>> operatorRoles = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRequireLineage() {
        return requireLineage;
    }

    public void setRequireLineage(boolean requireLineage) {
        this.requireLineage = requireLineage;
    }

    public Map<String, List<String>> getOperatorRoles() {
        return operatorRoles;
    }

    public void setOperatorRoles(Map<String, List<String>> operatorRoles) {
        if (operatorRoles == null || operatorRoles.isEmpty()) {
            this.operatorRoles = new LinkedHashMap<>();
            return;
        }
        // 配置绑定完成后不再暴露调用方可变的 Map/List，避免运行时权限被意外修改。
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        operatorRoles.forEach((actor, roles) -> copy.put(actor,
                roles == null ? List.of() : List.copyOf(roles)));
        this.operatorRoles = copy;
    }

    /**
     * 判断主体是否存在显式角色绑定（即使绑定内容全是未知角色）。
     * 这用于本地安全关闭时的 fail-closed：显式配置不能被请求头绕过。
     */
    public boolean hasBinding(String actorId) {
        if (actorId == null || actorId.isBlank() || operatorRoles == null) return false;
        String normalized = actorId.trim();
        return operatorRoles.containsKey(normalized) || operatorRoles.containsKey("*");
    }

    public java.util.Set<FeedbackRole> rolesFor(String actorId) {
        if (actorId == null || actorId.isBlank() || operatorRoles == null) return java.util.Set.of();
        List<String> configured = operatorRoles.get(actorId.trim());
        if (configured == null) configured = operatorRoles.get("*");
        if (configured == null || configured.isEmpty()) return java.util.Set.of();
        java.util.EnumSet<FeedbackRole> roles = java.util.EnumSet.noneOf(FeedbackRole.class);
        for (String value : new ArrayList<>(configured)) {
            if (value == null || value.isBlank()) continue;
            try {
                roles.add(FeedbackRole.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // 配置中的未知角色按 fail-closed 处理，不阻断其他合法角色加载。
            }
        }
        return java.util.Set.copyOf(roles);
    }
}
