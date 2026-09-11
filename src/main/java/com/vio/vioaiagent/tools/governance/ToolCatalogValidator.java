package com.vio.vioaiagent.tools.governance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动时校验玩家/运营工具目录的边界。目录是 fail-closed 的能力声明，
 * 不是模型可自行扩展的工具列表。
 */
public final class ToolCatalogValidator {
    private ToolCatalogValidator() { }

    public static CatalogSnapshot validate() {
        Map<String, ToolCapabilityCard> player = new LinkedHashMap<>();
        Map<String, ToolCapabilityCard> operator = new LinkedHashMap<>();
        for (ToolCapabilityCard card : ToolCapabilityCatalog.all().values()) {
            boolean trainer = card.allowedRoles().contains("trainer");
            boolean privileged = card.allowedRoles().stream().anyMatch(role ->
                    role.equals("operator") || role.equals("admin"));
            if (trainer) player.put(card.name(), card);
            if (privileged) operator.put(card.name(), card);
            if (trainer && card.riskLevel() != ToolCapabilityCard.RiskLevel.LOW) {
                throw new IllegalStateException("玩家目录不能包含中/高风险工具: " + card.name());
            }
            if (trainer && card.isWriteLike()) {
                throw new IllegalStateException("玩家目录不能包含写工具: " + card.name());
            }
        }
        if (player.keySet().stream().anyMatch(name -> !ToolCapabilityCatalog.all().containsKey(name))) {
            throw new IllegalStateException("玩家目录存在未登记工具");
        }
        if (operator.keySet().stream().anyMatch(name -> !ToolCapabilityCatalog.all().containsKey(name))) {
            throw new IllegalStateException("运营目录存在未登记工具");
        }
        return new CatalogSnapshot(Map.copyOf(player), Map.copyOf(operator));
    }

    public record CatalogSnapshot(Map<String, ToolCapabilityCard> playerTools,
                                  Map<String, ToolCapabilityCard> operatorTools) {
        public CatalogSnapshot {
            playerTools = playerTools == null ? Map.of() : Map.copyOf(playerTools);
            operatorTools = operatorTools == null ? Map.of() : Map.copyOf(operatorTools);
        }
    }
}
