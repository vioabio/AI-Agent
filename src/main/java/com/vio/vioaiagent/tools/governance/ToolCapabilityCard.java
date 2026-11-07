package com.vio.vioaiagent.tools.governance;

import java.util.List;

/**
 * 工具能力卡（Tool Contract）。
 *
 * <p>能力卡是治理层使用的机器可读契约，不等同于给模型展示的 description。
 * 每个进入 Agent 的工具都必须有能力卡；没有卡的工具默认拒绝执行。</p>
 */
public record ToolCapabilityCard(
        String name,
        String version,
        RiskLevel riskLevel,
        SideEffect sideEffect,
        boolean idempotent,
        long timeoutMs,
        List<String> allowedRoles,
        String inputSchema,
        String outputSchema) {

    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum SideEffect {
        NONE,
        NETWORK,
        WRITE,
        EXECUTE
    }

    public ToolCapabilityCard {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        version = version == null || version.isBlank() ? "1.0.0" : version;
        riskLevel = riskLevel == null ? RiskLevel.HIGH : riskLevel;
        sideEffect = sideEffect == null ? SideEffect.NONE : sideEffect;
        timeoutMs = timeoutMs <= 0 ? 2_000L : timeoutMs;
        allowedRoles = allowedRoles == null || allowedRoles.isEmpty()
                ? List.of("trainer")
                : List.copyOf(allowedRoles);
        inputSchema = inputSchema == null || inputSchema.isBlank() ? "{}" : inputSchema;
        outputSchema = outputSchema == null || outputSchema.isBlank() ? "{}" : outputSchema;
    }

    public boolean isWriteLike() {
        return sideEffect == SideEffect.WRITE || sideEffect == SideEffect.EXECUTE;
    }

    public boolean requiresApproval() {
        return riskLevel != RiskLevel.LOW || isWriteLike();
    }
}
