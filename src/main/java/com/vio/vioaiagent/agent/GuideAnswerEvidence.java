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
    public static final String REFERENCE_RETENTION_VERSION = "verified-reference-retention-v1";
    public static final String OVERVIEW_READY_VERSION = "bounded-reference-overview-ready-v1";
    public static final String SUBJECT_SOURCE_ORIGIN = "PROGRAM_SPECIES_SOURCE";
    public static final String SUBJECT_SOURCE_VERSION = "bounded-species-source-query-v1";
    private static final Pattern GUIDE_REQUEST = Pattern.compile("searchPokemonGuides|(?:检索|查找|引用|原文).{0,25}(?:攻略|笔记)|(?:攻略|笔记).{0,25}(?:检索|来源|作者)", Pattern.CASE_INSENSITIVE);
    private final ObjectMapper mapper = new ObjectMapper();
    private boolean initialized, requested, attempted, projectExplanation, partialReferenceFailure, resourceAttempted;
    private String question="";
    private List<LocalGuideSearch.Hit> hits = List.of();
    private final Map<Long,PokemonFact> references = new LinkedHashMap<>();
    private final ResourceAnswerEvidence resources=new ResourceAnswerEvidence();
    private final OverviewResourceCoverage resourceCoverage=new OverviewResourceCoverage();
    private final LearnsetAnswerEvidence learnsets=new LearnsetAnswerEvidence();
    private final MechanicAnswerEvidence mechanics=new MechanicAnswerEvidence();
    private final BuildCheckAnswerEvidence buildChecks=new BuildCheckAnswerEvidence();
    private final TeamBuildAnswerEvidence teamBuildChecks=new TeamBuildAnswerEvidence();
    private final MachineAnswerEvidence machines=new MachineAnswerEvidence();
    public void begin(String prompt) { question=prompt;teamBuildChecks.begin(prompt); initialized = true; projectExplanation = ProjectGuideRequest.requiresSource(prompt); requested = GUIDE_REQUEST.matcher(prompt).find() || projectExplanation || (!TeamMutationIntent.requested(prompt) && java.util.regex.Pattern.compile("培养|搭配|对战策略").matcher(prompt).find()); }
    public boolean initialized() { return initialized; }
    public boolean needsReferenceTopic() {
        if(question==null||question.length()>800||question.contains("\n")||TeamMutationIntent.requested(question)
                ||Pattern.compile("```|输入：|读取|保存|修改|变更|计算|组队|联防|属性覆盖").matcher(question).find()
                ||!Pattern.compile("图鉴|资料快照|资料版本|本地资料|PokéAPI",Pattern.CASE_INSENSITIVE).matcher(question).find()
                ||!Pattern.compile("规则|赛事|排位|来源日期").matcher(question).find()
                ||!Pattern.compile("检索|攻略|区分|解释").matcher(question).find())return false;
        return hits.stream().noneMatch(h->"reference-vs-rules-v1".equals(h.document().id()));
    }
    public boolean needsTeamTopic() {
        if(question==null||question.length()>800||question.contains("\n")||TeamMutationIntent.requested(question)
                ||ProjectGuideRequest.requiresSource(question)
                ||Pattern.compile("```|输入：|以下资料|读取|保存|修改|变更|计算").matcher(question).find()
                ||!Pattern.compile("组队|队伍搭配|联防|属性覆盖").matcher(question).find()
                ||!Pattern.compile("检索|攻略|笔记|为什么|为何").matcher(question).find())return false;
        return hits.stream().noneMatch(h->h.document().applicability()!=null
                &&!h.document().applicability().subjects().isEmpty()&&h.document().applicability().matchesSubject(question))
                &&references.values().stream().noneMatch(f->question.contains(f.speciesNameZh())||question.toLowerCase(Locale.ROOT).contains(f.slug().toLowerCase(Locale.ROOT)));
    }
    public boolean active() { if (TeamMutationIntent.requested(question)) return false; return teamBuildChecks.attempted() || TeamBuildAnswerEvidence.requested(question) || machines.attempted() || MachineAnswerEvidence.requested(question) || requested || attempted || resourceAttempted || learnsets.attempted() || LearnsetAnswerEvidence.requested(question) || mechanics.attempted() || MechanicAnswerEvidence.requested(question) || buildChecks.attempted() || BuildCheckAnswerEvidence.requested(question)
            ||!OverviewResourceCoverage.requiredKinds(question).isEmpty()&&overviewTarget(question).isPresent(); }
    public boolean overviewReady(String rawQuestion) {
        return !TeamBuildAnswerEvidence.requested(rawQuestion)&&!MachineAnswerEvidence.requested(rawQuestion)&&!BuildCheckAnswerEvidence.requested(rawQuestion)&&!MechanicAnswerEvidence.requested(rawQuestion)&&!LearnsetAnswerEvidence.requested(rawQuestion)&&!hits.isEmpty()&&overviewReferenceReady(rawQuestion)
                &&resourceCoverage.settled(rawQuestion,overviewTarget(rawQuestion).orElseThrow(),resources);
    }
    private boolean overviewReferenceReady(String rawQuestion) {
        if(rawQuestion==null||rawQuestion.length()>1500||references.isEmpty()
                ||rawQuestion.contains("```")||rawQuestion.contains("\n{"))return false;
        String head=rawQuestion.strip().split("\\R",2)[0];
        if(head.length()>180||!head.matches("^(?:请帮我|请|帮我|我想要|我想|想)?(?:介绍|了解).*")
                ||!Pattern.compile("培养|搭配|对战策略|强势阵容").matcher(head).find()
                ||Pattern.compile("计算|伤害|胜率|保存|修改|删除|写入|预览|对比|比较|相比|区别|差异|分别|两只|完整配招|六只|6只").matcher(rawQuestion).find())return false;
        String lower=head.toLowerCase(Locale.ROOT);
        return references.values().stream().filter(f->f.speciesNameZh()!=null&&!f.speciesNameZh().isBlank()&&head.contains(f.speciesNameZh())
                ||f.slug()!=null&&!f.slug().isBlank()&&Pattern.compile("(?<![a-z0-9-])"+Pattern.quote(f.slug().toLowerCase(Locale.ROOT))+"(?![a-z0-9-])").matcher(lower).find()).count()==1;
    }
    public String overviewAnswer() {
        return verifiedAnswer()+"\n### 本次范围\n本次提供已核验的图鉴事实和通用培养参考。现有资料不足以确认物种专属的完整配招、实时强势阵容或赛事合法性；这些部分仍需可靠来源和具体对局条件。\n";
    }
    private Optional<PokemonFact> overviewTarget(String rawQuestion) {
        if(!overviewReferenceReady(rawQuestion))return Optional.empty();
        String head=rawQuestion.strip().split("\\R",2)[0],lower=head.toLowerCase(Locale.ROOT);
        return references.values().stream().filter(f->head.contains(f.speciesNameZh())||Pattern.compile("(?<![a-z0-9-])"+Pattern.quote(f.slug().toLowerCase(Locale.ROOT))+"(?![a-z0-9-])").matcher(lower).find()).findFirst();
    }
    public List<OverviewResourceCoverage.Request> missingOverviewResources(String rawQuestion) {
        return overviewTarget(rawQuestion).map(f->resourceCoverage.pending(rawQuestion,f,resources)).orElse(List.of());
    }
    public void overviewResourceUnavailable(String kind){resourceCoverage.unavailable(kind);}
    public Optional<String> missingSpeciesSourceQuery(String rawQuestion) {
        if(!overviewReferenceReady(rawQuestion))return Optional.empty();
        String head=rawQuestion.strip().split("\\R",2)[0];
        if(hits.stream().anyMatch(h->h.document().applicability()!=null
                &&!h.document().applicability().subjects().isEmpty()
                &&h.document().applicability().matchesSubject(head)))return Optional.empty();
        return Optional.of(head);
    }
    public String planningInstruction() {
        if(TeamBuildAnswerEvidence.requested(question))return TeamBuildAnswerEvidence.instruction(question);
        String mechanicsInstruction=MechanicAnswerEvidence.instruction(question)+(BuildCheckAnswerEvidence.requested(question)?"\n用户要核对配招。工具开放时将用户原配置传给checkPokemonBuild，保留未提供字段为null，不自行补齐四招或特性。已保存队伍先读取实际记录，再按成员核对；逐项匹配不证明组合合法。":"");
        mechanicsInstruction+=MachineAnswerEvidence.instruction(question);
        if(BuildCheckAnswerEvidence.requested(question))return mechanicsInstruction+"\n本次优先调用checkPokemonBuild核对原配置；配招报告已包含逐招学习记录和记录等级，不需为同一配置逐招另查。单独学习查询不能代替整份配招检查。";
        if(LearnsetAnswerEvidence.requested(question))return mechanicsInstruction+"\n用户询问招式学习途径，候选列表不足以证明学习方式。工具开放时用真实成员formId、明确招式英文slug及当前Run版本调用lookupPokemonLearnset；不要代选未指定的招式。未取得资料时说明缺失，不能凭记忆补造TM编号、等级或遗传操作。";
        if (!requested) return mechanicsInstruction;
        if (attempted) return mechanicsInstruction+(hits.isEmpty()
                ? "\n本次已尝试本地攻略检索，但尚未取得可核对原文。必要时更换关键词重试；仍无来源时说明缺失，不凭记忆补造笔记或引用。"
                : "\n已有本次实际检索的本地攻略原文；足以覆盖问题时无需重复查询。引用只支持返回的原文，不凭记忆补造笔记或扩展机制。");
        return mechanicsInstruction+"\n用户需要本地攻略来源，先调用 searchPokemonGuides 按其主题检索；通用主题无需指定物种。不能凭记忆补造笔记或引用。";
    }
    public void observe(String tool, String output) {
        observe(tool,output,null);
    }
    public void observe(String tool, String output, String arguments) {
        if ("lookupPokemonMachine".equals(tool)) { machines.observe(output,arguments); return; }
        if ("checkPokemonBuild".equals(tool)) { buildChecks.observe(output,arguments); return; }
        if ("checkPokemonTeamBuild".equals(tool)) { teamBuildChecks.observe(output,arguments); return; }
        if ("lookupPokemonMechanics".equals(tool)) { mechanics.observe(output,arguments); return; }
        if ("lookupPokemonLearnset".equals(tool)) { learnsets.observe(output,arguments); return; }
        if ("lookupPokemonResource".equals(tool)) {
            resourceAttempted=true;
            resourceCoverage.observe(arguments);
            try {
                if (output == null || output.length() > 120_000) throw new IllegalArgumentException();
                var resource = mapper.readTree(output);
                if(com.vio.vioaiagent.pokemon.reference.ResourceReferenceFacts.SCHEMA.equals(resource.path("schemaVersion").asText())) {
                    if(!resources.observe(resource,arguments))throw new IllegalArgumentException();
                    return;
                }
                resources.failed(arguments);
                if (!resource.isObject() || !Set.of("move","ability","item","nature").contains(resource.path("kind").asText())
                        || !"pokeapi-reference-v1".equals(resource.path("dataReleaseId").asText())) throw new IllegalArgumentException();
            } catch (Exception ignored) { resources.failed(arguments);partialReferenceFailure = true; }
            return;
        }
        if ("lookupPokemonReference".equals(tool)) {
            if (output == null || output.length() > 120_000) { failedReference(arguments); return; }
            try {
                var root = mapper.readTree(output);
                for (String stat : List.of("hp","attack","defense","spAttack","spDefense","speed"))
                    if (!root.path("fact").path(stat).isIntegralNumber()) throw new IllegalArgumentException();
                var detail=mapper.treeToValue(root, LocalPokeApiRepository.Detail.class);var fact=detail.fact();
                if (!"pokeapi-reference-v1".equals(fact.dataReleaseId()) || !"pokemon-scarlet-violet".equals(fact.gameReleaseId())) throw new IllegalArgumentException();
                if (references.containsKey(fact.formId()) || references.size() < 6) {references.put(fact.formId(),fact);resources.member(detail);}
            } catch(Exception ignored) {
                failedReference(arguments);
            }
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
                if(d.applicability()!=null)d.applicability().validate();
                if (d.validUntil()!=null && LocalDate.parse(d.validUntil()).isBefore(LocalDate.now())) return;
                if (hit.citation()!=null && (!"zh-CN".equals(d.language()) || !"casual-singles".equals(d.battleFormatId())
                        || !"casual-v1".equals(d.rulesetVersionId()) || !GuideChunks.validCitation(d,hit.citation()))) return;
                checked.add(hit);
            }
            hits = List.copyOf(checked);
        } catch (Exception ignored) { /* failed, malformed or truncated output is not a source */ }
    }
    public boolean hasGuideSources(){return !hits.isEmpty();}
    private void failedReference(String arguments) {
        partialReferenceFailure=true;
        var target=GuideReferenceIdentity.target(arguments);
        if(target.isEmpty())references.clear();
        else references.values().removeIf(target.get()::matches);
        resources.retainMembers(references.keySet());
    }
    public String verifiedAnswer() {
        return verifiedAnswer(true);
    }
    /** Bounded reference lookup, not an interpretation of arbitrary strategy questions. */
    public Optional<String> basicFactAnswer() {
        if (question == null || question.length() > 600 || references.isEmpty() || partialReferenceFailure
                || active() || question.contains("\n") || question.contains("```")
                || !Pattern.compile("种族值|基础数值|图鉴属性").matcher(question).find()
                || Pattern.compile("培养|建议|搭配|攻略|配招|努力值|个体值|计算|伤害|对比|比较|克制|弱点|特性|招式|保存|修改|队伍").matcher(question).find())
            return Optional.empty();
        String lower = question.toLowerCase(Locale.ROOT);
        if (references.values().stream().anyMatch(f -> !question.contains(f.speciesNameZh())
                && !Pattern.compile("(?<![a-z0-9-])" + Pattern.quote(f.slug().toLowerCase(Locale.ROOT)) + "(?![a-z0-9-])")
                .matcher(lower).find())) return Optional.empty();
        StringBuilder text = new StringBuilder(referenceSummary());
        for (var f : references.values()) text.append(EvidenceText.inline(f.speciesNameZh()))
                .append("的种族值合计：")
                .append(f.hp() + f.attack() + f.defense() + f.spAttack() + f.spDefense() + f.speed()).append("。\n");
        return Optional.of(text.append("\n以上为本次已读取成员的本地参考快照，不是历史补丁或实时赛事规则记录；未读取的成员不作确认。")
                .append("种族值不是任意等级的实际能力值，后者还取决于等级、个体值、努力值和性格。")
                .append("仅凭这些数据不能证明当前排位合法、热门程度或最优培养。确认合法性还需目标赛制的官方规则与具体配置。\n").toString());
    }
    public Optional<String> speedComparisonAnswer() {
        if(question==null)return Optional.empty();
        // A declined topic is not a request for that topic. Only remove a closed
        // list of explicit negative clauses; positive/mixed requests remain guarded.
        String declined="(?:实际出手顺序|额外特性介绍|特性(?:介绍|效果|内容)?|机制(?:效果|说明)?)";
        String requested=question.replaceAll("(?:不要|无需|不必)(?:给|提供|补充|展开|介绍|讨论)?"+declined+"(?:[或和与、]"+declined+")*","");
        if(question.length()>600||references.size()!=2||partialReferenceFailure||active()
                ||question.contains("\n")||!question.contains("速度种族值")
                ||!Pattern.compile("对比|比较").matcher(question).find()
                ||Pattern.compile("培养|搭配|配招|克制|特性|道具|伤害|攻击|防御|特攻|特防|HP|生命").matcher(requested).find())return Optional.empty();
        var values=new ArrayList<>(references.values());var a=values.get(0);var b=values.get(1);
        String first="(?:"+Pattern.quote(a.speciesNameZh())+"|"+Pattern.quote(a.slug())+")";
        String second="(?:"+Pattern.quote(b.speciesNameZh())+"|"+Pattern.quote(b.slug())+")";
        String head=question.strip().split("[，,；;。]",2)[0];
        String pair="(?:"+first+"(?:和|与|、)"+second+"|"+second+"(?:和|与|、)"+first+")";
        if(!Pattern.compile("^(?:请)?(?:查|查询|读取)?"+pair+"的(?:资料|图鉴)$",Pattern.CASE_INSENSITIVE).matcher(head).matches())return Optional.empty();
        int delta=a.speed()-b.speed();
        return Optional.of(referenceSummary()+"### 速度种族值比较（程序核对）\n"
                +EvidenceText.inline(a.speciesNameZh())+"："+a.speed()+"；"+EvidenceText.inline(b.speciesNameZh())+"："+b.speed()+"。\n"
                +(delta==0?"两者速度种族值相同。":EvidenceText.inline(a.speciesNameZh())+"比"+EvidenceText.inline(b.speciesNameZh())+(delta>0?"高":"低")+Math.abs(delta)+"点。")
                +"\n这些不是同等级的实际速度，不能据此保证实战先手；本次未核对特性、招式或机制，不补充相关断言。\n");
    }
    public String verifiedAnswer(boolean includeReferences) {
        if(teamBuildChecks.attempted()||TeamBuildAnswerEvidence.requested(question))return teamBuildChecks.summary(question);
        String prefix = includeReferences ? referenceSummary() : "";
        if (partialReferenceFailure) prefix += "### 资料读取提醒\n本次有资料调用失败或未通过校验；以下仅展示已核对的结果，未取得的机制信息不作确认。\n\n";
        prefix+=resources.summary();
        prefix+=learnsets.summary(question,references.values());
        prefix+=mechanics.summary(question);
        prefix+=buildChecks.summary(question);
        prefix+=machines.summary(question);
        prefix+=overviewTarget(question).map(f->resourceCoverage.summary(question,f,resources)).orElse("");
        if(hits.isEmpty()&&(machines.attempted()||MachineAnswerEvidence.requested(question)))return prefix;
        if(hits.isEmpty()&&(buildChecks.attempted()||BuildCheckAnswerEvidence.requested(question)))return prefix;
        if(hits.isEmpty()&&(mechanics.attempted()||MechanicAnswerEvidence.requested(question)))return prefix
                +"### 资料范围\n以上只支持实际返回的参考说明；未收录的机制、学习操作和策略仍需进一步来源。\n";
        if(hits.isEmpty()&&(learnsets.attempted()||LearnsetAnswerEvidence.requested(question)))return prefix
                +"### 资料范围\n以上仅表示本次实际核验的学习记录；未取得的机制、操作与比赛规则仍需对应来源。\n";
        if(hits.isEmpty()&&resourceAttempted&&!requested)return prefix+(resources.hasFacts()?"":"本次未取得可校验的机制字段，请明确资源英文标识后重试。\n")
                +"### 资料范围\n以上仅说明实际取得的参考资料；完整机制、培养取舍和比赛规则仍需对应证据。\n";
        if (hits.isEmpty()) return prefix + "### 训练家提醒\n本次没有取得可核对的本地攻略原文，暂不提供带引用的攻略结论。可以换一个养成、组队或伤害解读主题重试；本地资料也不能证明当前热门阵容。";
        var selected = projectExplanation ? List.of(hits.getFirst()) : hits;
        if (projectExplanation && selected.getFirst().document().content().codePointCount(0, selected.getFirst().document().content().length()) > ProjectGuideRequest.MAX_SOURCE_CODE_POINTS)
            return prefix + "### 训练家提醒\n本次匹配的项目原文过长，暂不能展示完整说明。请缩小问题范围；不把截断内容当作完整流程或默认配置答案。";
        StringBuilder answer = new StringBuilder(prefix + (projectExplanation
                ? "### 项目说明（原文参考）\n以下展示本次检索排名首位的项目笔记完整原文。它只支持原文中的项目行为；若未覆盖所问内容，仍需进一步核对。\n\n"
                : "### 攻略参考（原文节选）\n以下是本次检索到的项目原创笔记；引文仅支持原文中的内容，不据此扩展具体配招或机制。\n\n"));
        for (var hit : selected) {
            var d = hit.document();
            boolean speciesNote=!projectExplanation&&d.applicability()!=null&&!d.applicability().subjects().isEmpty()
                    && d.applicability().matchesSubject(question)
                    && references.values().stream().anyMatch(f->d.applicability().matchesSubject(f.slug())
                        || d.applicability().matchesSubject(f.speciesNameZh()))
                    && d.content().codePointCount(0,d.content().length())<=2000;
            answer.append("**").append(EvidenceText.inline(d.title())).append("**\n\n作者：")
                    .append(EvidenceText.inline(d.author())).append(" · 更新：").append(d.updatedAt()).append("\n\n");
            if(projectExplanation||speciesNote)answer.append("原文范围：完整文档\n\n");
            else if(hit.citation()!=null)answer.append("原文位置：第 ").append(hit.citation().chunkNo()+1).append(" 个片段\n\n");
            if(d.provenance()!=null)answer.append(speciesNote
                    ?"资料说明：项目原创的条件建议；事实依据与策略推断分列，待人工复核。\n\n"
                    :"资料说明：项目原创；外部文档仅作接口参考，内容待人工复核。\n\n");
            answer.append(EvidenceText.quote(projectExplanation||speciesNote ? d.content() : excerpt(hit.citation()==null?d.content():hit.citation().text()))).append("\n\n");
        }
        return answer.append(projectExplanation
                ? "### 训练家提醒\n以上是项目实现说明，不代表最优培养、赛事合法性或重启恢复保证；原文和适用范围仍待人工复核。\n"
                : "### 训练家提醒\n以上是通用资料参考，不是当前最强阵容或正式比赛合法性证明。具体成员搭配需要另查成员资料，并在条件齐全时计算目标对局。\n").toString();
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
