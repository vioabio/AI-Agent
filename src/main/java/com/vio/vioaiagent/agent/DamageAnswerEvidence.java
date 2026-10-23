package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Validates the actual engine result and renders numbers without model rewriting. */
public final class DamageAnswerEvidence {
    private final ObjectMapper mapper = new ObjectMapper();
    private boolean initialized;
    private boolean requested;
    private JsonNode evidence;
    public void begin(String prompt) {
        initialized = true;
        requested = prompt.contains("calculateBattleDamage") || prompt.contains("请解释以下真实伤害")
                || prompt.contains("请用中文解释以下真实伤害") || prompt.matches("(?s).*?(?:请|帮我)计算.{0,40}伤害.*");
    }
    public boolean initialized() { return initialized; }
    public boolean active() { return requested || evidence != null; }
    public boolean complete() { return evidence != null; }
    public void observe(String tool, String output) {
        if (!"calculateBattleDamage".equals(tool)) return;
        requested = true; evidence = null;
        if (output == null || output.length() > 1_048_576) return;
        try {
            JsonNode value = mapper.readTree(output);
            if (!"@smogon/calc".equals(value.path("engine").asText())
                    || !"0.12.0".equals(value.path("engineVersion").asText())
                    || !"pokeapi-reference-v1".equals(value.path("dataReleaseId").asText())
                    || value.path("generation").asInt() != 9) return;
            double min = value.path("range").path("minimum").asDouble(-1);
            double max = value.path("range").path("maximum").asDouble(-1);
            double hp = value.path("defenderHp").asDouble(-1);
            double minPercent = value.path("percent").path("minimum").asDouble(-1);
            double maxPercent = value.path("percent").path("maximum").asDouble(-1);
            if (!value.path("range").path("minimum").isIntegralNumber()
                    || !value.path("range").path("maximum").isIntegralNumber() || !value.path("defenderHp").isIntegralNumber()) return;
            if (!Double.isFinite(min + max + hp + minPercent + maxPercent) || min < 0 || max < min || hp <= 0
                    || Math.abs(minPercent - 100 * min / hp) > .01 || Math.abs(maxPercent - 100 * max / hp) > .01) return;
            JsonNode assumptions = value.path("assumptions");
            if (!assumptions.isObject() || assumptions.path("move").asText().isBlank()) return;
            for (String side : List.of("attacker", "defender")) {
                JsonNode member = assumptions.path(side);
                if (member.path("species").asText().isBlank() || member.path("level").asInt() < 1
                        || member.path("level").asInt() > 100
                        || !member.path("evs").isObject() || !member.path("ivs").isObject()) return;
                for (String stat : List.of("hp","atk","def","spa","spd","spe")) {
                    for (String kind : List.of("evs","ivs")) {
                        JsonNode number = member.path(kind).path(stat);
                        if (!number.isIntegralNumber() || number.asInt() < 0 || number.asInt() > (kind.equals("evs") ? 252 : 31)) return;
                    }
                }
            }
            evidence = value;
        } catch (Exception ignored) { /* failure strings are not calculations */ }
    }
    public String missingAnswer() { return "尚未获得有效的伤害计算结果，暂不提供伤害范围。请补齐双方物种、等级、招式、特性、道具、性格、努力值、个体值和场地条件后重试。"; }
    public String verifiedSummary() {
        if (!complete()) return missingAnswer();
        JsonNode assumptions = evidence.path("assumptions");
        StringBuilder text = new StringBuilder("### 伤害结果（计算事实）\n| 项目 | 实际计算结果 |\n| --- | --- |\n");
        text.append("| 伤害范围 | ").append(number(evidence.path("range").path("minimum"))).append("～")
                .append(number(evidence.path("range").path("maximum"))).append(" HP |\n| 防御方最大HP | ")
                .append(number(evidence.path("defenderHp"))).append(" |\n| 伤害百分比 | ")
                .append(percent(evidence.path("percent").path("minimum"))).append("%～")
                .append(percent(evidence.path("percent").path("maximum"))).append("% |\n\n");
        for (String side : List.of("attacker", "defender")) {
            JsonNode member = assumptions.path(side);
            text.append(side.equals("attacker") ? "攻击方：" : "防御方：").append(safe(member.path("species").asText()))
                    .append("，Lv.").append(member.path("level").asInt()).append("，性格 ").append(safe(member.path("nature").asText("未填")))
                    .append("，特性 ").append(safe(member.path("ability").asText("未填"))).append("，道具 ").append(safe(member.path("item").asText("未填")))
                    .append("；EV ").append(stats(member.path("evs"))).append("；IV ").append(stats(member.path("ivs"))).append("。\n");
        }
        text.append("招式：").append(safe(assumptions.path("move").asText())).append("；场地：")
                .append(safe(assumptions.path("field").path("gameType").asText("未填"))).append("；天气：")
                .append(safe(assumptions.path("field").path("weather").asText("无"))).append("；地形：")
                .append(safe(assumptions.path("field").path("terrain").asText("无")))
                .append("。\n来源：本地 @smogon/calc 0.12.0。假设满HP；未模拟未提供的强化、异常状态、墙、场地伤害和太晶。伤害范围不是胜率或赛制合法性证明，也不单独保证击倒结果。\n");
        return text.toString();
    }
    public String finalInstruction() {
        return "\n计算事实已经由程序展示。只输出简短的条件性训练建议和需继续验证的问题，"
                + "不要重述伤害数字、倍率、天气效果、特性机制或胜率，不保证必定击倒。不要输出JSON。";
    }
    private static String stats(JsonNode value) {
        String[] keys = {"hp","atk","def","spa","spd","spe"};
        String[] names = {"HP","攻","防","特攻","特防","速"};
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < keys.length; index++) parts.add(names[index] + value.path(keys[index]).asInt());
        return String.join("/", parts);
    }
    private static String number(JsonNode value) { return String.format(Locale.ROOT,"%.0f",value.asDouble()); }
    private static String percent(JsonNode value) { return String.format(Locale.ROOT,"%.2f",value.asDouble()); }
    private static String safe(String value) { return value.replaceAll("[|\\r\\n*`<>]", " "); }
}
