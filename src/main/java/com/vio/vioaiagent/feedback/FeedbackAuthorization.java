package com.vio.vioaiagent.feedback;

import com.vio.vioaiagent.config.SecurityProperties;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/** 运营反馈操作授权适配器；生产只认配置的主体角色，本地安全关闭时才接受显式测试角色。 */
@Component
public class FeedbackAuthorization {
    private final FeedbackProperties properties;
    private final SecurityProperties securityProperties;

    public FeedbackAuthorization(FeedbackProperties properties, SecurityProperties securityProperties) {
        this.properties = properties;
        this.securityProperties = securityProperties;
    }

    public Set<FeedbackRole> rolesFor(String actorId, String localRoleHeader) {
        Set<FeedbackRole> configured = properties.rolesFor(actorId);
        if (!configured.isEmpty() || properties.hasBinding(actorId) || securityProperties.isEnabled()) {
            return configured;
        }
        if (localRoleHeader == null || localRoleHeader.isBlank()) return Set.of();
        EnumSet<FeedbackRole> local = EnumSet.noneOf(FeedbackRole.class);
        for (String role : localRoleHeader.split(",")) {
            if (role == null || role.isBlank()) continue;
            try {
                local.add(FeedbackRole.parse(role));
            } catch (IllegalArgumentException ignored) {
                // 本地测试头中的未知角色不应扩大权限。
            }
        }
        return Set.copyOf(local);
    }

    /**
     * 返回真正可用于状态迁移的角色集合。
     * 生产环境（或存在显式角色绑定时）只接受配置中心中的角色，绝不信任
     * Controller/内部调用方传入的任意角色；本地安全关闭且没有绑定时，才
     * 保留测试用的显式角色集合。
     */
    public Set<FeedbackRole> authorizedRoles(String actorId, Set<FeedbackRole> suppliedRoles) {
        Set<FeedbackRole> configured = properties.rolesFor(actorId);
        if (securityProperties.isEnabled() || properties.hasBinding(actorId)) return configured;
        if (suppliedRoles == null || suppliedRoles.isEmpty()) return Set.of();
        return Set.copyOf(suppliedRoles);
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    /** 是否必须从稳定答案投影回读完整发布血缘。 */
    public boolean requireLineage() {
        return properties.isRequireLineage() || securityProperties.isEnabled();
    }

    public void require(Set<FeedbackRole> roles, FeedbackState from, FeedbackState to) {
        if (roles == null || roles.isEmpty()
                || FeedbackStateMachine.allowedRoles(from, to).stream().noneMatch(roles::contains)) {
            throw new FeedbackStateMachine.FeedbackTransitionException("当前角色无权执行反馈操作", 403);
        }
    }
}
