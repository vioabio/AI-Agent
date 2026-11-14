package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.domain.*;
import com.vio.vioaiagent.pokemon.team.TeamWorkflowService;
import java.util.*;
import java.util.regex.Pattern;

/** A missing-condition answer is a projection of a real saved snapshot. The model
 * does not translate stored nature values or relabel base stats with a level. */
public final class SavedTeamAnswerEvidence {
    private static final Pattern RESERVED_FACT = Pattern.compile("性格|种族值|等级|勤奋|内敛|胆小|固执|爽朗|大胆|\\d+\\s*级|\\b(?:hardy|lonely|brave|adamant|naughty|bold|docile|relaxed|impish|lax|timid|hasty|serious|jolly|naive|modest|mild|quiet|bashful|rash|calm|gentle|sassy|careful|quirky)\\b",Pattern.CASE_INSENSITIVE);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    private boolean initialized, attempted;
    private TeamWorkflowService.ReadTeam read;
    public void begin(String prompt) { initialized = true; }
    public boolean initialized() { return initialized; }
    public boolean complete() { return read != null; }
    /** Same strict projection used by prose and typed configuration cards. */
    public Optional<TeamWorkflowService.ReadTeam> verifiedRead() { return Optional.ofNullable(read); }
    public boolean ownsClarification() { return attempted && (read == null || !missing().isEmpty()); }
    public void observe(String tool, String output) {
        if (!"readCurrentTeam".equals(tool)) return;
        attempted = true; read = null;
        if (output == null || output.length() > 120_000) return;
        try {
            var root = mapper.readTree(output);
            var members = root.path("snapshot").path("team").path("members");
            if (!members.isArray() || members.isEmpty() || members.size() > 6 || !root.path("facts").isArray()
                    || !root.path("snapshot").path("revision").isIntegralNumber() || !root.hasNonNull("expiresAt")) return;
            for (var member : members) {
                var build = member.path("build");
                // Do not let record constructor defaults turn a truncated tool result
                // into apparently verified configuration.
                for (String field : List.of("moves","ability","item","nature","level","evs","ivs")) if (!build.hasNonNull(field)) return;
                if (!build.path("moves").isArray() || !build.path("level").isIntegralNumber() || !build.path("nature").isTextual()
                        || build.path("nature").asText().isBlank() || !build.path("ability").isTextual() || !build.path("item").isTextual()) return;
                for (var move : build.path("moves")) if (!move.isTextual()) return;
                for (String kind : List.of("evs","ivs")) for (String stat : List.of("hp","atk","def","spa","spd","spe"))
                    if (!build.path(kind).path(stat).isIntegralNumber()) return;
            }
            for (var fact : root.path("facts")) for (String stat : List.of("hp","attack","defense","spAttack","spDefense","speed"))
                if (!fact.path(stat).isIntegralNumber()) return;
            var value = mapper.treeToValue(root, TeamWorkflowService.ReadTeam.class);
            var team = value.snapshot().team();
            if (!"pokeapi-reference-v1".equals(team.dataReleaseId()) || team.members().size() != value.facts().size()) return;
            Set<Integer> slots = new HashSet<>();
            for (int n = 0; n < team.members().size(); n++) {
                var member = team.members().get(n); var fact = value.facts().get(n);
                if (!slots.add(member.slot()) || member.formId() != fact.formId()
                        || !team.dataReleaseId().equals(fact.dataReleaseId()) || !"pokemon-scarlet-violet".equals(fact.gameReleaseId())) return;
            }
            read = value;
        } catch (Exception ignored) { /* actual failed reads must not leave an older snapshot active */ }
    }
    public String verifiedClarification() {
        if (read == null) return "暂未读取到可核对的队伍配置，不能说明该队伍的性格、数值或缺失条件。请检查队伍选择和资料连接后重试。";
        StringBuilder text = new StringBuilder(verifiedSummary());
        text.append("\n### 还需明确\n");
        missing().forEach(line -> text.append("- ").append(line).append("\n"));
        return text.append("\n以上配置按原保存记录展示；空道具不代表已确认不携带。若要进一步讨论搭配，请明确目标对手和成员角色；只有请求精确伤害时才需要补充计算条件。本次未修改队伍。\n").toString();
    }
    public String verifiedSummary() {
        if (read == null) return "";
        StringBuilder text = new StringBuilder("### 已保存配置（记录事实）\n以下来自本次读取的队伍，不替你补填未明确的条件。\n\n| 成员 | 等级 | 性格 | 招式 | 特性 | 道具 |\n| --- | --- | --- | --- | --- | --- |\n");
        var members = read.snapshot().team().members();
        for (int n = 0; n < members.size(); n++) {
            var b = members.get(n).build();
            text.append("| ").append(EvidenceText.inline(read.facts().get(n).speciesNameZh())).append(" | ").append(b.level())
                    .append(" | ").append(natureName(b.nature())).append(" | ").append(shown(String.join(" / ", b.moves().stream().map(m->PokemonDisplayNames.label("move",m)).toList())))
                    .append(" | ").append(PokemonDisplayNames.label("ability",b.ability())).append(" | ").append(PokemonDisplayNames.label("item",b.item())).append(" |\n");
        }
        text.append("\n### 图鉴种族值（资料事实）\n种族值与等级无关，以下不是当前等级的实际能力值。\n\n| 成员 | HP | 攻击 | 防御 | 特攻 | 特防 | 速度 |\n| --- | --- | --- | --- | --- | --- | --- |\n");
        for (var f : read.facts()) text.append("| ").append(EvidenceText.inline(f.speciesNameZh())).append(" | ").append(f.hp()).append(" | ")
                .append(f.attack()).append(" | ").append(f.defense()).append(" | ").append(f.spAttack()).append(" | ").append(f.spDefense()).append(" | ").append(f.speed()).append(" |\n");
        return text.append("\n来源：已保存队伍；PokéAPI 本地参考。资料不代表赛事合法性。\n").toString();
    }
    public String finalInstruction() {
        return "\n已保存配置由程序展示，不要在建议中重述、翻译或改写性格、等级、种族值。只给条件性策略与下一步验证动作；需要修改时走预览确认流程。";
    }
    public String checkedAdvice(String draft) {
        if (draft == null || draft.isBlank() || RESERVED_FACT.matcher(draft).find())
            return "### 训练家提醒\n配置事实已在上方展示。本次建议仍重述了需要逐项核对的配置，暂未展示；可以先选定目标对局，再依据真实资料和计算结果讨论改动。";
        return draft;
    }
    private List<String> missing() {
        if (read == null) return List.of();
        List<String> lines = new ArrayList<>();
        for (int n = 0; n < read.snapshot().team().members().size(); n++) {
            var b = read.snapshot().team().members().get(n).build(); List<String> fields = new ArrayList<>();
            if (b.moves().isEmpty()) fields.add("招式");
            if (b.ability().isBlank()) fields.add("特性");
            if (b.item().isBlank()) fields.add("道具");
            if (!fields.isEmpty()) lines.add(EvidenceText.inline(read.facts().get(n).speciesNameZh()) + "：" + String.join("、", fields) + "未明确。");
        }
        return lines;
    }
    static String natureName(String slug) { return PokemonDisplayNames.nature(slug); }
    private static String shown(String value) { return value.isBlank() ? "未明确" : EvidenceText.inline(value); }
}
