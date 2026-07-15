package com.vio.vioaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;
import java.util.Set;

/**
 * 宝可梦属性克制查询工具 — 18 种属性的攻防克制矩阵。
 *
 * <p>覆盖所有属性间的 2x/0.5x/0x 克制关系。
 * 基于 Pokémon 官方属性克制表。
 *
 * @author vio
 */
public class PokeTypeTool {

    /** 属性克制表：攻击属性 → {防御属性 → 倍率} */
    private static final Map<String, Map<String, Double>> TYPE_CHART = Map.ofEntries(
            Map.entry("一般", Map.ofEntries(Map.entry("岩石", 0.5), Map.entry("钢", 0.5), Map.entry("幽灵", 0.0))),
            Map.entry("火", Map.ofEntries(Map.entry("草", 2.0), Map.entry("冰", 2.0), Map.entry("虫", 2.0), Map.entry("钢", 2.0),
                    Map.entry("火", 0.5), Map.entry("水", 0.5), Map.entry("岩石", 0.5), Map.entry("龙", 0.5))),
            Map.entry("水", Map.ofEntries(Map.entry("火", 2.0), Map.entry("地面", 2.0), Map.entry("岩石", 2.0),
                    Map.entry("水", 0.5), Map.entry("草", 0.5), Map.entry("龙", 0.5))),
            Map.entry("电", Map.ofEntries(Map.entry("水", 2.0), Map.entry("飞行", 2.0),
                    Map.entry("电", 0.5), Map.entry("草", 0.5), Map.entry("龙", 0.5), Map.entry("地面", 0.0))),
            Map.entry("草", Map.ofEntries(Map.entry("水", 2.0), Map.entry("地面", 2.0), Map.entry("岩石", 2.0),
                    Map.entry("草", 0.5), Map.entry("火", 0.5), Map.entry("毒", 0.5), Map.entry("飞行", 0.5), Map.entry("虫", 0.5), Map.entry("龙", 0.5), Map.entry("钢", 0.5))),
            Map.entry("冰", Map.ofEntries(Map.entry("草", 2.0), Map.entry("地面", 2.0), Map.entry("飞行", 2.0), Map.entry("龙", 2.0),
                    Map.entry("火", 0.5), Map.entry("水", 0.5), Map.entry("冰", 0.5), Map.entry("钢", 0.5))),
            Map.entry("格斗", Map.ofEntries(Map.entry("一般", 2.0), Map.entry("冰", 2.0), Map.entry("岩石", 2.0), Map.entry("恶", 2.0), Map.entry("钢", 2.0),
                    Map.entry("毒", 0.5), Map.entry("飞行", 0.5), Map.entry("超能力", 0.5), Map.entry("虫", 0.5), Map.entry("妖精", 0.5), Map.entry("幽灵", 0.0))),
            Map.entry("毒", Map.ofEntries(Map.entry("草", 2.0), Map.entry("妖精", 2.0),
                    Map.entry("毒", 0.5), Map.entry("地面", 0.5), Map.entry("岩石", 0.5), Map.entry("幽灵", 0.5), Map.entry("钢", 0.0))),
            Map.entry("地面", Map.ofEntries(Map.entry("火", 2.0), Map.entry("电", 2.0), Map.entry("毒", 2.0), Map.entry("岩石", 2.0), Map.entry("钢", 2.0),
                    Map.entry("草", 0.5), Map.entry("虫", 0.5), Map.entry("飞行", 0.0))),
            Map.entry("飞行", Map.ofEntries(Map.entry("草", 2.0), Map.entry("格斗", 2.0), Map.entry("虫", 2.0),
                    Map.entry("电", 0.5), Map.entry("岩石", 0.5), Map.entry("钢", 0.5))),
            Map.entry("超能力", Map.ofEntries(Map.entry("格斗", 2.0), Map.entry("毒", 2.0),
                    Map.entry("超能力", 0.5), Map.entry("钢", 0.5), Map.entry("恶", 0.0))),
            Map.entry("虫", Map.ofEntries(Map.entry("草", 2.0), Map.entry("超能力", 2.0), Map.entry("恶", 2.0),
                    Map.entry("火", 0.5), Map.entry("格斗", 0.5), Map.entry("毒", 0.5), Map.entry("飞行", 0.5), Map.entry("幽灵", 0.5), Map.entry("钢", 0.5), Map.entry("妖精", 0.5))),
            Map.entry("岩石", Map.ofEntries(Map.entry("火", 2.0), Map.entry("冰", 2.0), Map.entry("飞行", 2.0), Map.entry("虫", 2.0),
                    Map.entry("格斗", 0.5), Map.entry("地面", 0.5), Map.entry("钢", 0.5))),
            Map.entry("幽灵", Map.ofEntries(Map.entry("超能力", 2.0), Map.entry("幽灵", 2.0),
                    Map.entry("恶", 0.5), Map.entry("一般", 0.0))),
            Map.entry("龙", Map.ofEntries(Map.entry("龙", 2.0), Map.entry("钢", 0.5), Map.entry("妖精", 0.0))),
            Map.entry("恶", Map.ofEntries(Map.entry("超能力", 2.0), Map.entry("幽灵", 2.0),
                    Map.entry("格斗", 0.5), Map.entry("恶", 0.5), Map.entry("妖精", 0.5))),
            Map.entry("钢", Map.ofEntries(Map.entry("冰", 2.0), Map.entry("岩石", 2.0), Map.entry("妖精", 2.0),
                    Map.entry("火", 0.5), Map.entry("水", 0.5), Map.entry("电", 0.5), Map.entry("钢", 0.5))),
            Map.entry("妖精", Map.ofEntries(Map.entry("格斗", 2.0), Map.entry("龙", 2.0), Map.entry("恶", 2.0),
                    Map.entry("火", 0.5), Map.entry("毒", 0.5), Map.entry("钢", 0.5)))
    );

    private static final Set<String> ALL_TYPES = Set.of(
            "一般", "火", "水", "电", "草", "冰", "格斗", "毒", "地面",
            "飞行", "超能力", "虫", "岩石", "幽灵", "龙", "恶", "钢", "妖精"
    );

    @Tool(description = "查询宝可梦属性克制关系。输入攻击属性和防御属性，返回克制倍率（2.0=克制, 0.5=抵抗, 0.0=免疫）")
    public String checkTypeEffectiveness(
            @ToolParam(description = "攻击方属性（中文），如'火'、'水'、'电'") String attackType,
            @ToolParam(description = "防御方属性（中文），如'草'、'飞行'、'龙'") String defendType) {
        if (!ALL_TYPES.contains(attackType)) {
            return "未知攻击属性: " + attackType + "。支持的属性: " + String.join(", ", ALL_TYPES);
        }
        if (!ALL_TYPES.contains(defendType)) {
            return "未知防御属性: " + defendType + "。支持的属性: " + String.join(", ", ALL_TYPES);
        }

        Double multiplier = TYPE_CHART.getOrDefault(attackType, Map.of())
                .getOrDefault(defendType, 1.0);
        String desc = switch ((int)(multiplier * 10)) {
            case 20 -> "效果拔群！2x 伤害";
            case 5 -> "效果一般…0.5x 伤害";
            case 0 -> "完全没有效果！";
            default -> "普通效果 1x 伤害";
        };
        return attackType + " → " + defendType + ": " + desc + " (x" + multiplier + ")";
    }

    @Tool(description = "查询某攻击属性对所有18种防御属性的克制关系一览表")
    public String getTypeChart(
            @ToolParam(description = "要查询的攻击属性（中文）") String attackType) {
        if (!ALL_TYPES.contains(attackType)) {
            return "未知属性: " + attackType + "。支持的属性: " + String.join(", ", ALL_TYPES);
        }
        Map<String, Double> defenders = TYPE_CHART.getOrDefault(attackType, Map.of());
        StringBuilder sb = new StringBuilder(attackType + " 属性的攻击克制关系:\n");
        sb.append("克制(2x): ");
        defenders.entrySet().stream().filter(e -> e.getValue() == 2.0)
                .forEach(e -> sb.append(e.getKey()).append(" "));
        sb.append("\n抵抗(0.5x): ");
        defenders.entrySet().stream().filter(e -> e.getValue() == 0.5)
                .forEach(e -> sb.append(e.getKey()).append(" "));
        sb.append("\n免疫(0x): ");
        defenders.entrySet().stream().filter(e -> e.getValue() == 0.0)
                .forEach(e -> sb.append(e.getKey()).append(" "));
        sb.append("\n普通(1x): ");
        ALL_TYPES.stream().filter(t -> !defenders.containsKey(t))
                .forEach(t -> sb.append(t).append(" "));
        return sb.toString();
    }
}
