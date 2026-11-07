package com.vio.vioaiagent.pokemon.calculator;

import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 纯函数属性裁判：双属性相乘，免疫优先体现为 0。 */
public final class TypeEffectivenessCalculator {
    public static final String CALCULATION_VERSION = "type-chart-sv-v1";
    private static final List<String> TYPE_ORDER = List.of("一般", "火", "水", "电", "草", "冰", "格斗", "毒", "地面", "飞行", "超能力", "虫", "岩石", "幽灵", "龙", "恶", "钢", "妖精");
    private static final Set<String> TYPES = Set.copyOf(TYPE_ORDER);
    private static final Map<String, Map<String, Double>> CHART = Map.ofEntries(
            Map.entry("一般", Map.of("岩石", .5, "钢", .5, "幽灵", 0d)),
            Map.entry("火", Map.of("草", 2d, "冰", 2d, "虫", 2d, "钢", 2d, "火", .5, "水", .5, "岩石", .5, "龙", .5)),
            Map.entry("水", Map.of("火", 2d, "地面", 2d, "岩石", 2d, "水", .5, "草", .5, "龙", .5)),
            Map.entry("电", Map.of("水", 2d, "飞行", 2d, "电", .5, "草", .5, "龙", .5, "地面", 0d)),
            Map.entry("草", Map.of("水", 2d, "地面", 2d, "岩石", 2d, "草", .5, "火", .5, "毒", .5, "飞行", .5, "虫", .5, "龙", .5, "钢", .5)),
            Map.entry("冰", Map.of("草", 2d, "地面", 2d, "飞行", 2d, "龙", 2d, "火", .5, "水", .5, "冰", .5, "钢", .5)),
            Map.entry("格斗", Map.ofEntries(Map.entry("一般", 2d), Map.entry("冰", 2d), Map.entry("岩石", 2d), Map.entry("恶", 2d), Map.entry("钢", 2d), Map.entry("毒", .5), Map.entry("飞行", .5), Map.entry("超能力", .5), Map.entry("虫", .5), Map.entry("妖精", .5), Map.entry("幽灵", 0d))),
            Map.entry("毒", Map.of("草", 2d, "妖精", 2d, "毒", .5, "地面", .5, "岩石", .5, "幽灵", .5, "钢", 0d)),
            Map.entry("地面", Map.of("火", 2d, "电", 2d, "毒", 2d, "岩石", 2d, "钢", 2d, "草", .5, "虫", .5, "飞行", 0d)),
            Map.entry("飞行", Map.of("草", 2d, "格斗", 2d, "虫", 2d, "电", .5, "岩石", .5, "钢", .5)),
            Map.entry("超能力", Map.of("格斗", 2d, "毒", 2d, "超能力", .5, "钢", .5, "恶", 0d)),
            Map.entry("虫", Map.ofEntries(Map.entry("草", 2d), Map.entry("超能力", 2d), Map.entry("恶", 2d), Map.entry("火", .5), Map.entry("格斗", .5), Map.entry("毒", .5), Map.entry("飞行", .5), Map.entry("幽灵", .5), Map.entry("钢", .5), Map.entry("妖精", .5))),
            Map.entry("岩石", Map.of("火", 2d, "冰", 2d, "飞行", 2d, "虫", 2d, "格斗", .5, "地面", .5, "钢", .5)),
            Map.entry("幽灵", Map.of("超能力", 2d, "幽灵", 2d, "恶", .5, "一般", 0d)),
            Map.entry("龙", Map.of("龙", 2d, "钢", .5, "妖精", 0d)),
            Map.entry("恶", Map.of("超能力", 2d, "幽灵", 2d, "格斗", .5, "恶", .5, "妖精", .5)),
            Map.entry("钢", Map.of("冰", 2d, "岩石", 2d, "妖精", 2d, "火", .5, "水", .5, "电", .5, "钢", .5)),
            Map.entry("妖精", Map.of("格斗", 2d, "龙", 2d, "恶", 2d, "火", .5, "毒", .5, "钢", .5)));

    public double calculate(String attackType, String... defendTypes) {
        attackType = normalize(attackType);
        requireType(attackType);
        if (defendTypes == null || defendTypes.length == 0 || defendTypes.length > 2) {
            throw new IllegalArgumentException("防御属性必须为 1～2 个");
        }
        double result = 1d;
        for (int i = 0; i < defendTypes.length; i++) {
            String defendType = normalize(defendTypes[i]);
            requireType(defendType);
            result *= CHART.getOrDefault(attackType, Map.of()).getOrDefault(defendType, 1d);
        }
        return result;
    }

    public boolean supports(String type) { return type != null && TYPES.contains(normalize(type)); }

    public List<String> supportedTypes() { return TYPE_ORDER; }

    private void requireType(String type) {
        if (!supports(type)) throw new IllegalArgumentException("未知属性: " + type);
    }

    private String normalize(String type) {
        return type == null ? null : type.trim();
    }
}
