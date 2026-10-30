package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.calculator.TypeEffectivenessCalculator;
import com.vio.vioaiagent.pokemon.reference.*;
import java.util.*;
import java.util.regex.Pattern;

/** Per-agent evidence gate. It deliberately does not claim universal prose verification. */
public final class TeamAnswerEvidence {
    private static final Pattern TEAM_REQUEST = Pattern.compile("(?:分析|检查|评估|点评|优化).{0,30}(?:队伍|阵容)|(?:队伍|阵容).{0,20}(?:分析|检查|评估|优化)");
    private static final Pattern REQUIRED = Pattern.compile("(?m)^参考成员[：:]\\s*([^\\r\\n]+)$");
    // These assertions require separate verification; the model is asked for conditional
    // coaching only. Type facts and base weather factors are rendered by the program.
    private static final Pattern UNVERIFIED_MECHANICS = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:%|倍|点伤害)|种族值|克制|免疫|抵抗|弱点(?:是|为|包括)|弱[火水电草冰地岩幽恶妖]|雨盘|求雨|雨天|天气|特性.{0,6}(?:提升|回复|提高|降低)|高速|低速|脆皮|必定击倒|一击必杀|OHKO|2HKO|(?:modest.{0,10}(?:温和|谦逊))|(?:(?:温和|谦逊).{0,10}(?:特攻|modest))",
            Pattern.CASE_INSENSITIVE);
    private final ObjectMapper mapper = new ObjectMapper();
    private boolean initialized;
    private boolean active;
    private boolean validRequired = true;
    private Set<String> required = Set.of();
    private TeamReferenceTools.TeamEvidence evidence;

    public void begin(String prompt) {
        initialized = true;
        active = TEAM_REQUEST.matcher(prompt).find();
        var match = REQUIRED.matcher(prompt);
        if (match.find()) {
            var names = Arrays.stream(match.group(1).split("[、,，]")).map(String::trim)
                    .map(name -> name.toLowerCase(Locale.ROOT)).toList();
            validRequired = !names.isEmpty() && names.size() <= 6 && new HashSet<>(names).size() == names.size()
                    && names.stream().allMatch(name -> name.matches("[a-z0-9][a-z0-9-]{0,79}"));
            required = new HashSet<>(names);
            if (match.find()) validRequired = false;
        }
    }
    public boolean initialized() { return initialized; }
    public boolean active() { return active; }
    public String planningInstruction() {
        return active ? "\n队伍分析必须先用 lookupPokemonTeamReference 一次读取完整参考成员清单，不能用单只查询冒充完整队伍证据。参考成员："
                + String.join("、", required) + "。读取失败时说明缺失，不反复搜索补造事实。" : "";
    }
    public void observe(String tool, String output) {
        if (!active || !"lookupPokemonTeamReference".equals(tool)) return;
        evidence = null;
        if (output == null || output.length() > 1_048_576) return;
        try {
            var parsed = mapper.readValue(output, TeamReferenceTools.TeamEvidence.class);
            if (!LocalPokeApiRepository.RELEASE.equals(parsed.dataReleaseId())
                    || !TypeEffectivenessCalculator.CALCULATION_VERSION.equals(parsed.calculationVersion())
                    || parsed.members() == null || parsed.members().isEmpty() || parsed.members().size() > 6) return;
            Set<String> observed = new HashSet<>(); Set<Long> identities = new HashSet<>();
            for (var member : parsed.members()) {
                var fact = member.detail().fact();
                if (!parsed.dataReleaseId().equals(fact.dataReleaseId()) || !observed.add(member.query())
                        || !identities.add(fact.formId())) return;
                // Validate all types independently; never trust a model-supplied matrix.
                new TypeEffectivenessCalculator().calculate("一般", types(fact.type1(), fact.type2()));
            }
            if (!validRequired || !observed.containsAll(required) || (!required.isEmpty() && observed.size() != required.size())) return;
            evidence = parsed;
        } catch (Exception ignored) { /* failed/malformed tool responses are not evidence */ }
    }
    public boolean complete() { return evidence != null; }
    public String missingAnswer() {
        return "暂时没有读齐本次队伍的完整成员资料，不能可靠地给出整队分析。请检查资料连接、数据版本和成员清单后重试；也可以先在队伍配置中选择成员，再点击“让 AI 分析”。";
    }
    public String verifiedSummary() {
        if (!complete()) return missingAnswer();
        StringBuilder text = new StringBuilder("### 图鉴速览（资料事实）\n以下仅针对本次完整读取的参考成员，基础属性倍率由程序计算，不含特性、道具、太晶等效果。\n\n| 成员 | 属性 | 速度种族值 | 受哪些属性攻击效果拔群 |\n| --- | --- | --- | --- |\n");
        var calculator = new TypeEffectivenessCalculator();
        for (var member : evidence.members()) {
            var fact = member.detail().fact();
            List<String> weaknesses = new ArrayList<>();
            for (String attack : calculator.supportedTypes()) {
                double multiplier = calculator.calculate(attack, types(fact.type1(), fact.type2()));
                if (multiplier > 1) weaknesses.add(attack + "×" + (int) multiplier);
            }
            text.append("| ").append(safe(fact.speciesNameZh())).append(" | ")
                    .append(String.join(" / ", types(fact.type1(), fact.type2()))).append(" | ")
                    .append(fact.speed()).append(" | ").append(String.join("、", weaknesses)).append(" |\n");
        }
        text.append("\n来源：PokéAPI 本地参考；基础属性裁判。参考资料不是当前赛制合法性或实时强度证明。\n");
        return text.toString();
    }
    public String finalInstruction() {
        return "\n已验证的资料摘要将由程序展示：\n" + verifiedSummary()
                + "\n你只输出培养方案（策略建议）和队伍搭配（策略建议），使用条件性建议与下一步验证动作。"
                + "不要重述属性关系、天气、特性机制、速度分级或伤害数字；这些事实需要单独的规则/计算证据。"
                + "不要把参考资料存在当作招式合法或热门阵容的证明。不要给出JSON。";
    }
    public String checkedAdvice(String draft) {
        if (!canPublishAdvice(draft)) {
            return "### 训练家提醒\n本次建议包含尚未核对的机制或数值描述，暂未展示。可以先依据上面已核对的资料或计算结果，明确各成员的角色、配置和目标对局，随后用资料与计算工具逐项验证。当前不提供热门程度、胜率或招式合法性结论。";
        }
        return draft;
    }
    public boolean canPublishAdvice(String draft) {
        return draft != null && !draft.isBlank() && !UNVERIFIED_MECHANICS.matcher(draft).find();
    }
    private static String[] types(String first, String second) { return second == null || second.isBlank() ? new String[]{first} : new String[]{first, second}; }
    private static String safe(String value) { return value.replaceAll("[|\\r\\n*`<>]", " "); }
}
