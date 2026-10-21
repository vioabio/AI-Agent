package com.vio.vioaiagent.tools.governance;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.vio.vioaiagent.tools.governance.ToolCapabilityCard.RiskLevel.HIGH;
import static com.vio.vioaiagent.tools.governance.ToolCapabilityCard.RiskLevel.LOW;
import static com.vio.vioaiagent.tools.governance.ToolCapabilityCard.RiskLevel.MEDIUM;
import static com.vio.vioaiagent.tools.governance.ToolCapabilityCard.SideEffect.*;

/**
 * 内置工具能力卡目录。
 *
 * <p>目录刻意采用显式白名单。新增本地工具或 MCP 工具时，必须先补充能力卡，
 * 否则治理网关会以 fail-closed 方式拒绝调用。</p>
 */
public final class ToolCapabilityCatalog {

    private static final Map<String, ToolCapabilityCard> CARDS = Map.ofEntries(
            Map.entry("lookupPokemonReference", card("lookupPokemonReference", LOW, NETWORK, true, 25_000, List.of("trainer", "operator"))),
            Map.entry("lookupPokemonResource", card("lookupPokemonResource", LOW, NETWORK, true, 10_000, List.of("trainer", "operator"))),
            Map.entry("calculateBattleDamage", card("calculateBattleDamage", LOW, NETWORK, true, 45_000, List.of("trainer", "operator"))),
            Map.entry("searchPokemonGuides", card("searchPokemonGuides", LOW, NONE, true, 3_000, List.of("trainer", "operator"))),
            Map.entry("checkTypeEffectiveness", card("checkTypeEffectiveness", LOW, NONE, true, 2_000,
                    List.of("trainer", "operator"))),
            Map.entry("getTypeChart", card("getTypeChart", LOW, NONE, true, 2_000,
                    List.of("trainer", "operator"))),
            Map.entry("searchWeb", card("searchWeb", LOW, NETWORK, true, 5_000,
                    List.of("trainer", "operator"))),
            Map.entry("web_search", card("web_search", LOW, NETWORK, true, 5_000,
                    List.of("trainer", "operator"))),
            // 文件读取仍属于本地资源访问：即使高风险工具开关被打开，也不能
            // 让玩家角色通过通用工具读取服务器文件；玩家应使用结构化图鉴/RAG。
            Map.entry("readFile", card("readFile", LOW, NONE, true, 2_000,
                    List.of("operator", "admin"))),
            Map.entry("scrapeWebPage", card("scrapeWebPage", MEDIUM, NETWORK, true, 8_000,
                    List.of("operator", "admin"))),
            Map.entry("generatePDF", card("generatePDF", MEDIUM, WRITE, false, 10_000,
                    List.of("operator", "admin"))),
            Map.entry("writeFile", card("writeFile", MEDIUM, WRITE, false, 5_000,
                    List.of("operator", "admin"))),
            Map.entry("downloadResource", card("downloadResource", MEDIUM, WRITE, false, 15_000,
                    List.of("operator"))),
            Map.entry("executeTerminalCommand", card("executeTerminalCommand", HIGH, EXECUTE, false, 5_000,
                    List.of("operator", "admin"))),
            Map.entry("terminal_operation", card("terminal_operation", HIGH, EXECUTE, false, 5_000,
                    List.of("operator", "admin"))),
            Map.entry("doTerminate", card("doTerminate", LOW, NONE, true, 2_000,
                    List.of("trainer", "operator", "admin")))
    );

    private ToolCapabilityCatalog() {
    }

    public static Optional<ToolCapabilityCard> find(String toolName) {
        return Optional.ofNullable(CARDS.get(toolName));
    }

    public static Map<String, ToolCapabilityCard> all() {
        return CARDS;
    }

    /** 为显式登记的 MCP 工具创建默认卡，调用方仍应根据实际能力覆盖风险等级。 */
    public static ToolCapabilityCard mcpReadOnlyCard(String name, String schema) {
        return new ToolCapabilityCard(name, "1.0.0", LOW, NONE, true, 5_000,
                List.of("trainer", "operator"), schema, "{}");
    }

    private static ToolCapabilityCard card(String name,
                                            ToolCapabilityCard.RiskLevel risk,
                                            ToolCapabilityCard.SideEffect effect,
                                            boolean idempotent,
                                            long timeout,
                                            List<String> roles) {
        return new ToolCapabilityCard(name, "1.0.0", risk, effect, idempotent, timeout,
                roles, "{}", "{}");
    }
}
