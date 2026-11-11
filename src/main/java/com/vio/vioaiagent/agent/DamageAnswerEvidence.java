package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Validates the actual engine result and renders numbers without model rewriting. */
public final class DamageAnswerEvidence {
    private final ObjectMapper mapper = new ObjectMapper();
    private boolean initialized;
    private boolean requested;
    private JsonNode evidence;
    private String failureMessage;
    private boolean calculationAttempted;
    public boolean calculationFailed() { return calculationAttempted && !complete(); }
    private static final String REFERENCE_FAILURE="宝可梦参考资料暂时无法读取，本次未能生成伤害范围。请检查资料连接，或导入所需本地资料后重试。";
    public void begin(String prompt) {
        initialized = true;
        requested = ExplicitDamageRequest.hasInput(prompt) || prompt.contains("calculateBattleDamage") || prompt.contains("请解释以下真实伤害")
                || prompt.contains("请用中文解释以下真实伤害") || prompt.matches("(?s).*?(?:请|帮我)计算.{0,40}伤害.*");
    }
    public boolean initialized() { return initialized; }
    public boolean active() { return requested || evidence != null; }
    public boolean complete() { return evidence != null; }
    public void observe(String tool, String output) {
        if (!Set.of("calculateBattleDamage","calculateGymDamage").contains(tool)) {
            if(requested&&evidence==null&&Set.of("lookupPokemonReference","lookupPokemonResource","lookupPokemonTeamReference").contains(tool)&&referenceFailure(output))failureMessage=REFERENCE_FAILURE;
            return;
        }
        calculationAttempted = true; requested = true; evidence = null;
        failureMessage=null;
        if (output == null || output.length() > 1_048_576) return;
        if(output!=null&&(output.startsWith("[tool.error]")||output.startsWith("[tool.denied]"))) {
            if(referenceFailure(output))failureMessage=REFERENCE_FAILURE;
            else if(output.contains("本地资料缓存校验失败"))failureMessage="本地资料未通过校验，本次未发布伤害范围。请检查对应缓存文件，或将已校验的资料包导入新目录后重试。";
            else if(output.contains("TOOL_EXECUTION_TIMEOUT"))failureMessage="本次计算工具执行超时，暂未取得有效伤害结果。请稍后重试，必要时检查本地计算依赖。";
            else if(output.startsWith("[tool.denied]"))failureMessage="本次计算请求被参数校验或工具策略拒绝，暂未生成伤害范围。请查看执行记录并修正请求。";
            else failureMessage="本次计算工具执行失败，暂未取得有效伤害结果。请查看执行记录后重试。";
            return;
        }
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
    private static boolean referenceFailure(String output) {
        return output!=null&&output.length()<=1_048_576&&output.startsWith("[tool.error]")
                &&(output.contains("PokéAPI 正文读取超时")||output.contains("PokéAPI 暂不可达")||output.contains("PokéAPI 暂时无法提供资料"));
    }
    public String missingAnswer() { return failureMessage!=null?failureMessage:"尚未获得有效的伤害计算结果，暂不提供伤害范围。请补齐双方物种、等级、招式、特性、道具、性格、努力值、个体值和场地条件后重试。"; }
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
            text.append(side.equals("attacker") ? "攻击方：" : "防御方：").append(safe(PokemonDisplayNames.species(member.path("species").asText())))
                    .append("，等级").append(member.path("level").asInt()).append("，性格 ").append(PokemonDisplayNames.nature(member.path("nature").asText()))
                    .append("，特性 ").append(safe(PokemonDisplayNames.calculationLabel("ability",member.path("ability").asText()))).append("，道具 ").append(member.path("item").isTextual()&&member.path("item").asText().isEmpty()?"无道具（计算假设）":safe(PokemonDisplayNames.calculationLabel("item",member.path("item").asText())))
                    .append("；努力值 ").append(stats(member.path("evs"))).append("；个体值 ").append(stats(member.path("ivs"))).append("。\n");
        }
        var defaults=evidence.path("conditionValidation").path("engineDefaultAbilitySides");
        if(defaults.isArray()&&!defaults.isEmpty())text.append("注意：未指定的特性采用了引擎默认特性（见上方实际条件），请确认该假设；这不表示用户已经选择了该特性。\n");
        if(gym()){
            text.append("招式：").append(safe(PokemonDisplayNames.calculationLabel("move",assumptions.path("move").asText()))).append("；场地：").append(safe(PokemonDisplayNames.condition(assumptions.path("field").path("gameType").asText()))).append("。\n");
            for(String side:List.of("attacker","defender")){var state=assumptions.path(side).path("state");text.append(side.equals("attacker")?"攻击方状态：":"防御方状态：").append("剩余HP ").append(state.path("hpPercent").asInt()).append("%；太晶 ").append(state.path("teraActive").asBoolean()?safe(PokemonDisplayNames.type(state.path("activeTeraType").asText())):"未激活").append("；异常状态 ").append(safe(PokemonDisplayNames.condition(state.path("status").asText()))).append("；能力变化 ").append(boosts(state.path("boosts"))).append("。\n");}
            text.append("NPC性格、努力值与个体值是计算假设，不是已核实的隐藏数值。来源：本地 @smogon/calc 0.12.0；按已提供的HP、太晶、状态、能力变化与场地计算单次伤害，不包含多回合行动、命中率、回合末结算或胜率。\n");
            return text.toString();
        }
        text.append("招式：").append(safe(PokemonDisplayNames.calculationLabel("move",assumptions.path("move").asText()))).append("；场地：")
                .append(safe(PokemonDisplayNames.condition(assumptions.path("field").path("gameType").asText()))).append("；天气：")
                .append(safe(PokemonDisplayNames.condition(assumptions.path("field").path("weather").asText("")))).append("；地形：")
                .append(safe(PokemonDisplayNames.condition(assumptions.path("field").path("terrain").asText(""))))
                .append("。\n来源：本地 @smogon/calc 0.12.0。假设满HP；未模拟未提供的强化、异常状态、墙、场地伤害和太晶。伤害范围不是胜率或赛制合法性证明，也不单独保证击倒结果。\n");
        return text.toString();
    }
    public String finalInstruction() {
        return "\n计算事实已经由程序展示。只输出简短的条件性训练建议和需继续验证的问题，"
                + "不要重述伤害数字、倍率、天气效果、特性机制或胜率，不保证必定击倒。不要输出JSON。";
    }
    /** Arithmetic interpretation only; never infer mechanics, turn counts or a win probability. */
    public String verifiedInterpretation() {
        if (!complete()) return missingAnswer();
        double min = evidence.path("range").path("minimum").asDouble();
        double max = evidence.path("range").path("maximum").asDouble();
        double hp = evidence.path("defenderHp").asDouble();
        String comparison;
        if (max == 0) {
            comparison = "本次计算结果为零伤害；仅凭这个结果不能认定是属性、特性或其他机制导致。";
            if(gym()&&"Ground".equals(evidence.path("moveType").asText())&&"Levitate".equals(evidence.path("assumptions").path("defender").path("ability").asText())&&!evidence.path("assumptions").path("field").path("isGravity").asBoolean()
                    &&!Set.of("Mold Breaker","Teravolt","Turboblaze").contains(evidence.path("assumptions").path("attacker").path("ability").asText()))comparison="本次计算为零伤害：在当前无重力、无破格类特性等所列条件下，地面招式受到防御方飘浮免疫；电太晶不会移除其特性。不能把这个零伤害当作整场胜负结论。";
        } else if (min >= hp) {
            comparison = "本次伤害区间的下界已达到防御方满HP参考阈值；这只是所列条件下的数值比较，不保证实战击倒。";
        } else if (max < hp) {
            comparison = "本次伤害区间的上界仍低于防御方满HP参考阈值；不能据此推断连续攻击所需回合。";
        } else {
            comparison = "本次区间包含低于和达到防御方满HP参考阈值的结果；仅有上下界不足以给出击倒概率。";
        }
        return "### 对局解读（程序核对）\n" + comparison
                + "\n百分比以防御方最大HP为分母，不是胜率；区间上下界也不代表每个伤害值等概率出现。\n"
                + "\n### 下一步验证建议\n"
                + "- 将上方双方配置与场地保留为比较基线。若要优化培养，先明确目标对局，一次只改变一个配置项并重新计算；新结果出来前不认定改动更优。\n"
                + "- 如需讨论队伍搭配，还要提供其他成员和具体赛制，并分别核对资料与目标对局；单次伤害不能证明整队强度或配招合法性。\n";
    }
    private static String stats(JsonNode value) {
        String[] keys = {"hp","atk","def","spa","spd","spe"};
        String[] names = {"HP","攻","防","特攻","特防","速"};
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < keys.length; index++) parts.add(names[index] + value.path(keys[index]).asInt());
        return String.join("/", parts);
    }
    private boolean gym(){return evidence!=null&&"GYM_SINGLE_ACTION_V1".equals(evidence.path("calculationScope").asText());}
    private static String boosts(JsonNode values){String[] keys={"atk","def","spa","spd","spe"};String[] names={"攻击","防御","特攻","特防","速度"};var result=new java.util.StringJoiner("、");for(int i=0;i<keys.length;i++)if(values.path(keys[i]).asInt()!=0)result.add(names[i]+String.format(Locale.ROOT,"%+d",values.path(keys[i]).asInt()));return result.length()==0?"无":result.toString();}
    private static String number(JsonNode value) { return String.format(Locale.ROOT,"%.0f",value.asDouble()); }
    private static String percent(JsonNode value) { return String.format(Locale.ROOT,"%.2f",value.asDouble()); }
    private static String safe(String value) { return value.replaceAll("[|\\r\\n*`<>]", " "); }
}
