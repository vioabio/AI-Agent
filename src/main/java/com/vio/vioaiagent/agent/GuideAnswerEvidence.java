package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.guides.LocalGuideSearch;
import com.vio.vioaiagent.pokemon.guides.GuideChunks;
import com.vio.vioaiagent.pokemon.reference.PokeApiClient;
import com.vio.vioaiagent.pokemon.reference.LocalPokeApiRepository;
import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Extractive guide answers. Actual returned text is quoted; no generated mechanism
 * or species example can acquire a citation merely because a search succeeded. */
public final class GuideAnswerEvidence {
    private static final Pattern GUIDE_REQUEST = Pattern.compile("searchPokemonGuides|(?:检索|查找|引用|原文).{0,25}(?:攻略|笔记)|(?:攻略|笔记).{0,25}(?:检索|来源|作者)", Pattern.CASE_INSENSITIVE);
    private final ObjectMapper mapper = new ObjectMapper();
    private boolean initialized, requested, attempted;
    private List<LocalGuideSearch.Hit> hits = List.of();
    private final Map<Long,PokemonFact> references = new LinkedHashMap<>();
    public void begin(String prompt) { initialized = true; requested = GUIDE_REQUEST.matcher(prompt).find(); }
    public boolean initialized() { return initialized; }
    public boolean active() { return requested || attempted; }
    public String planningInstruction() {
        return requested ? "\n用户需要本地攻略来源，先调用 searchPokemonGuides 按其主题检索；通用主题无需指定物种。不能凭记忆补造笔记或引用。" : "";
    }
    public void observe(String tool, String output) {
        if ("lookupPokemonReference".equals(tool)) {
            if (output == null || output.length() > 120_000) { references.clear(); return; }
            try {
                var root = mapper.readTree(output);
                for (String stat : List.of("hp","attack","defense","spAttack","spDefense","speed"))
                    if (!root.path("fact").path(stat).isIntegralNumber()) throw new IllegalArgumentException();
                var fact = mapper.treeToValue(root, LocalPokeApiRepository.Detail.class).fact();
                if (!"pokeapi-reference-v1".equals(fact.dataReleaseId()) || !"pokemon-scarlet-violet".equals(fact.gameReleaseId())) throw new IllegalArgumentException();
                if (references.containsKey(fact.formId()) || references.size() < 6) references.put(fact.formId(),fact);
            } catch(Exception ignored) { references.clear(); }
            return;
        }
        if (!"searchPokemonGuides".equals(tool)) return;
        attempted = true; hits = List.of();
        if (output == null || output.length() > 120_000) return;
        try {
            var root = mapper.readTree(output);
            if (!root.isArray() || root.size() > 4) return;
            List<LocalGuideSearch.Hit> checked = new ArrayList<>(); Set<String> identities = new HashSet<>();
            for (var row : root) {
                var hit = mapper.treeToValue(row, LocalGuideSearch.Hit.class); var d = hit.document();
                if (d == null || d.id() == null || !identities.add(d.id()) || hit.score() < 2
                        || !"pokemon-scarlet-violet".equals(d.gameReleaseId()) || blank(d.title(), 200) || blank(d.author(), 200)
                        || blank(d.content(), 20000) || d.updatedAt() == null
                        || !PokeApiClient.digest(d.id() + d.gameReleaseId() + d.updatedAt() + d.content()).equals(hit.checksum())) return;
                LocalDate.parse(d.updatedAt());
                if(d.provenance()!=null) {
                    if(!"VIO project".equals(d.author()))return;
                    d.provenance().validate(d.updatedAt());
                }
                if (d.validUntil()!=null && LocalDate.parse(d.validUntil()).isBefore(LocalDate.now())) return;
                if (hit.citation()!=null && (!"zh-CN".equals(d.language()) || !"casual-singles".equals(d.battleFormatId())
                        || !"casual-v1".equals(d.rulesetVersionId()) || !GuideChunks.validCitation(d,hit.citation()))) return;
                checked.add(hit);
            }
            hits = List.copyOf(checked);
        } catch (Exception ignored) { /* failed, malformed or truncated output is not a source */ }
    }
    public String verifiedAnswer() {
        return verifiedAnswer(true);
    }
    public String verifiedAnswer(boolean includeReferences) {
        String prefix = includeReferences ? referenceSummary() : "";
        if (hits.isEmpty()) return prefix + "### 训练家提醒\n本次没有取得可核对的本地攻略原文，暂不提供带引用的攻略结论。可以换一个养成、组队或伤害解读主题重试；本地资料也不能证明当前热门阵容。";
        StringBuilder answer = new StringBuilder(prefix + "### 攻略参考（原文节选）\n以下是本次检索到的项目原创笔记；引文仅支持原文中的内容，不据此扩展具体配招或机制。\n\n");
        for (var hit : hits) {
            var d = hit.document();
            answer.append("**").append(EvidenceText.inline(d.title())).append("**\n\n作者：")
                    .append(EvidenceText.inline(d.author())).append(" · 更新：").append(d.updatedAt()).append("\n\n");
            if(hit.citation()!=null)answer.append("原文位置：第 ").append(hit.citation().chunkNo()+1).append(" 个片段\n\n");
            if(d.provenance()!=null)answer.append("资料说明：项目原创；外部文档仅作接口参考，内容待人工复核。\n\n");
            answer.append(EvidenceText.quote(excerpt(hit.citation()==null?d.content():hit.citation().text()))).append("\n\n");
        }
        return answer.append("### 训练家提醒\n以上是通用资料参考，不是当前最强阵容或正式比赛合法性证明。具体成员搭配需要另查成员资料，并在条件齐全时计算目标对局。\n").toString();
    }
    private String referenceSummary() {
        if (references.isEmpty()) return "";
        StringBuilder text = new StringBuilder("### 图鉴速览（资料事实）\n以下为本次实际读取的成员资料；种族值与等级无关。\n\n| 成员 | 属性 | HP | 攻击 | 防御 | 特攻 | 特防 | 速度 |\n| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (var f : references.values()) text.append("| ").append(EvidenceText.inline(f.speciesNameZh())).append(" | ")
                .append(EvidenceText.inline(f.type1()+(f.type2()==null?"":" / "+f.type2()))).append(" | ")
                .append(f.hp()).append(" | ").append(f.attack()).append(" | ").append(f.defense()).append(" | ")
                .append(f.spAttack()).append(" | ").append(f.spDefense()).append(" | ").append(f.speed()).append(" |\n");
        return text.append("\n来源：PokéAPI 本地参考；不代表排位合法性或当前热门程度。\n\n").toString();
    }
    private static boolean blank(String text, int max) { return text == null || text.isBlank() || text.length() > max; }
    private static String excerpt(String text) {
        if (text.codePointCount(0, text.length()) <= 700) return text;
        String part = text.substring(0, text.offsetByCodePoints(0, 700));
        int sentence = part.lastIndexOf('。');
        return sentence >= 200 ? part.substring(0, sentence + 1) : part;
    }
}
