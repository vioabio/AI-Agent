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
    private final Map<String,List<LocalGuideSearch.Hit>> guideReads = new LinkedHashMap<>();
    private final Map<String,Boolean> referenceFailures = new LinkedHashMap<>();
    private final Map<Long,PokemonFact> references = new LinkedHashMap<>();
    private final ResourceAnswerEvidence resources=new ResourceAnswerEvidence();
    private final OverviewResourceCoverage resourceCoverage=new OverviewResourceCoverage();
    private final LearnsetAnswerEvidence learnsets=new LearnsetAnswerEvidence();
    private final MechanicAnswerEvidence mechanics=new MechanicAnswerEvidence();
    private final BuildCheckAnswerEvidence buildChecks=new BuildCheckAnswerEvidence();
    private final TeamBuildAnswerEvidence teamBuildChecks=new TeamBuildAnswerEvidence();
    private final MachineAnswerEvidence machines=new MachineAnswerEvidence();
    public void begin(String prompt) { guideReads.clear();hits=List.of();attempted=false;referenceFailures.clear();question=prompt;teamBuildChecks.begin(prompt); initialized = true; projectExplanation = ProjectGuideRequest.requiresSource(prompt); requested = GUIDE_REQUEST.matcher(prompt).find() || projectExplanation || statMeaningQuestion(prompt) || (!TeamMutationIntent.requested(prompt) && (java.util.regex.Pattern.compile("培养|搭配|对战策略").matcher(prompt).find() || genericTeamRoles(prompt))); }
    /** Conceptual interpretation does not require a live species read or a fabricated calculation. */
    public static boolean statMeaningQuestion(String prompt) {
        return prompt != null && prompt.length() <= 600 && !prompt.contains("\n")
                && !prompt.contains("```") && !prompt.contains("{")
                && Pattern.compile("种族值|基础数值").matcher(prompt).find()
                && Pattern.compile("实际(?:的)?(?:速度|能力|数值)|能力值|实战先手|出手顺序").matcher(prompt).find()
                && Pattern.compile("能否|是否|直接|等于|代表|区别|说明|为什么|为何").matcher(prompt).find()
                && !Pattern.compile("查询|读取|列出|计算|伤害|配招|推荐|搭配|组队|队伍|保存|修改|预览|对比|比较|[0-9]").matcher(prompt).find();
    }
    public static org.springframework.ai.tool.ToolCallback[] statMeaningTools(String prompt, org.springframework.ai.tool.ToolCallback[] tools) {
        if (!statMeaningQuestion(prompt)) return tools;
        return Arrays.stream(tools).filter(tool -> "searchPokemonGuides".equals(tool.getToolDefinition().name()))
                .toArray(org.springframework.ai.tool.ToolCallback[]::new);
    }
    private static boolean genericTeamRoles(String prompt) {
        return prompt != null && prompt.length() < 800 && !prompt.contains("\n")
                && Pattern.compile("组队|构建队伍").matcher(prompt).find()
                && Pattern.compile("功能角色|角色重叠|职责|分工").matcher(prompt).find()
                && !Pattern.compile("我的|已保存|读取|修改|保存|这支|这套").matcher(prompt).find();
    }
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
                ||(!genericTeamRoles(question)&&!Pattern.compile("检索|攻略|笔记|为什么|为何").matcher(question).find()))return false;
        return hits.stream().noneMatch(h->h.document().applicability()!=null
                &&!h.document().applicability().subjects().isEmpty()&&h.document().applicability().matchesSubject(question))
                &&references.values().stream().noneMatch(f->question.contains(f.speciesNameZh())||question.toLowerCase(Locale.ROOT).contains(f.slug().toLowerCase(Locale.ROOT)));
    }
    public boolean active() { if (TeamMutationIntent.requested(question)) return false; return !referenceFailures.isEmpty() || teamBuildChecks.attempted() || TeamBuildAnswerEvidence.requested(question) || machines.attempted() || MachineAnswerEvidence.requested(question) || requested || attempted || resourceAttempted || learnsets.attempted() || LearnsetAnswerEvidence.requested(question) || mechanics.attempted() || MechanicAnswerEvidence.requested(question) || buildChecks.attempted() || BuildCheckAnswerEvidence.requested(question)
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
        if (statMeaningQuestion(question)) return "\n用户询问种族值与实际能力的关系。只检索种族值、实际配置与填写默认值的攻略原文，解释等级、性格、努力值和个体值的作用。无需查询任何物种，不能代入任意成员或计算缺失的实际数值。";
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
            if (output == null || output.length() > 120_000) { recordReferenceFailure(arguments,output);failedReference(arguments); return; }
            try {
                var root = mapper.readTree(output);
                for (String stat : List.of("hp","attack","defense","spAttack","spDefense","speed"))
                    if (!root.path("fact").path(stat).isIntegralNumber()) throw new IllegalArgumentException();
                var detail=mapper.treeToValue(root, LocalPokeApiRepository.Detail.class);var fact=detail.fact();
                if (!"pokeapi-reference-v1".equals(fact.dataReleaseId()) || !"pokemon-scarlet-violet".equals(fact.gameReleaseId())) throw new IllegalArgumentException();
                if(GuideReferenceIdentity.target(arguments).map(target->target.matches(fact)).orElse(false)) {
                    referenceFailures.keySet().removeIf(query->GuideReferenceIdentity.target(
                        mapper.createObjectNode().put("query",query).put("dataReleaseId","pokeapi-reference-v1").toString())
                        .map(target->target.matches(fact)).orElse(false));
                }
                if (references.containsKey(fact.formId()) || references.size() < 6) {references.put(fact.formId(),fact);resources.member(detail);}
            } catch(Exception ignored) {
                recordReferenceFailure(arguments,output);
                failedReference(arguments);
            }
            return;
        }
        if (!"searchPokemonGuides".equals(tool)) return;
        attempted = true;
        String readKey = guideReadKey(arguments);
        if (readKey == null) guideReads.clear();
        else guideReads.remove(readKey);
        refreshGuideHits();
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
                if(com.vio.vioaiagent.pokemon.guides.GuideRevisionPolicy.usable(d,question))checked.add(hit);
            }
            if (guideReads.size() >= 8) guideReads.remove(guideReads.keySet().iterator().next());
            guideReads.put(readKey == null ? "UNBOUND" : readKey, List.copyOf(checked));
            refreshGuideHits();
        } catch (Exception ignored) { /* failed, malformed or truncated output is not a source */ }
    }
    private String guideReadKey(String arguments) {
        if (arguments == null || arguments.length() > 4000) return null;
        try {
            var input = mapper.readTree(arguments);
            if (!input.isObject() || !input.path("query").isTextual()
                    || !"pokemon-scarlet-violet".equals(input.path("gameReleaseId").asText())) return null;
            String query = input.path("query").asText().strip();
            if (query.isEmpty() || query.length() > 2000) return null;
            return PokeApiClient.digest(query);
        } catch (Exception invalid) { return null; }
    }
    private void refreshGuideHits() {
        var documents = new LinkedHashMap<String,LocalGuideSearch.Hit>();
        for (var read : guideReads.values())
            for (var hit : read) documents.put(hit.document().id(), hit);
        hits = documents.values().stream().sorted(Comparator.comparingInt(hit ->
                hit.document().applicability() != null && !hit.document().applicability().subjects().isEmpty()
                        && hit.document().applicability().matchesSubject(question) ? 0 : 1)).limit(4).toList();
    }
    public boolean hasGuideSources(){return !hits.isEmpty();}
    public Optional<String> documentAttribution(String documentId) {
        return hits.stream().filter(hit -> documentId.equals(hit.document().id()))
                .findFirst().map(hit -> "来源：项目攻略《" + EvidenceText.inline(hit.document().title())
                        + "》，作者 " + EvidenceText.inline(hit.document().author())
                        + "，更新 " + hit.document().updatedAt() + "。");
    }
    /** Only an actual, validated matching source can be expanded to its complete bounded text. */
    public Optional<String> fullDocumentAnswer(String documentId) {
        return hits.stream().filter(hit->documentId.equals(hit.document().id()))
                .map(LocalGuideSearch.Hit::document)
                .filter(d->d.content().codePointCount(0,d.content().length())<=ProjectGuideRequest.MAX_SOURCE_CODE_POINTS)
                .findFirst().map(d->"### 本地说明原文\n\n**"+EvidenceText.inline(d.title())+"**\n\n作者 "+EvidenceText.inline(d.author())
                        +" · 更新 "+d.updatedAt()+"\n\n"+EvidenceText.quote(d.content())
                        +"\n\n以上为项目说明完整原文，内容与适用范围待人工复核。\n");
    }
    private Optional<String> boundReferenceQuery(String arguments) {
        if(arguments==null||arguments.length()>2000)return Optional.empty();
        try {
            var input=mapper.readTree(arguments);
            if(!input.isObject()||input.size()!=2||!"pokeapi-reference-v1".equals(input.path("dataReleaseId").asText())||!input.path("query").isTextual())return Optional.empty();
            String query=input.path("query").asText().strip().toLowerCase(Locale.ROOT);
            if(query.isEmpty()||query.length()>80||question==null)return Optional.empty();
            var mention=Pattern.compile("(?<![a-z0-9-])"+Pattern.quote(query)+"(?![a-z0-9-])",Pattern.CASE_INSENSITIVE);
            return mention.matcher(question).find()?Optional.of(query):Optional.empty();
        } catch(Exception invalid){return Optional.empty();}
    }
    private void recordReferenceFailure(String arguments,String output) {
        // Only this tool's explicit not-found marker establishes absence. Other
        // failures describe availability and must never become invented absence.
        boundReferenceQuery(arguments).ifPresent(query->{
            if(referenceFailures.containsKey(query)||referenceFailures.size()<6)
                referenceFailures.put(query,output!=null&&output.length()<=120_000&&output.startsWith("[tool.error]")&&output.contains("POKEMON_REFERENCE_NOT_FOUND"));
        });
    }
    private String referenceFailureSummary() {
        var text=new StringBuilder();
        referenceFailures.forEach((query,missing)->text.append("### 宝可梦查询结果\n")
            .append(missing?"当前资料源未找到宝可梦“":"本次未能读取宝可梦“").append(EvidenceText.inline(query)).append("”的资料。")
            .append(missing?"请核对名称或全国图鉴编号后重试。":"请稍后重试，或检查资料连接与输入版本。")
            .append("当前无法据此提供该物种的属性、种族值、配招或资料引用。\n"));
        return text.toString();
    }
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
    public Optional<String> formComparisonAnswer() {
        if (question == null || question.length() > 600 || question.contains("\n")
                || !Pattern.compile("(?:区分|区别|比较|对比).{0,12}形态|形态.{0,12}(?:区分|区别|比较|对比)").matcher(question).find()
                || Pattern.compile("培养|搭配|配招|招式|特性|道具|伤害|队伍|建议|计算").matcher(question).find()) return Optional.empty();
        if (references.isEmpty()) return Optional.of("本次未取得可核验的形态资料，不能使用模型记忆补写属性、种族值或招式。请核对形态名称后重试。");
        if (references.values().stream().map(PokemonFact::speciesId).distinct().count() != 1)
            return Optional.of("本次读取的资料对应不同物种，不能当作同一物种的两种形态比较。请明确形态名称后重试；不使用模型记忆补写。");
        String answer = referenceSummary() + referenceFailureSummary();
        if (references.size() < 2) answer += "本次尚未核对到两种形态，不能补写未读取形态的属性或数值。\n\n";
        return Optional.of(answer + "以上只比较实际读取的形态身份、属性与种族值；物种全国编号相同不代表形态相同。"
                + "本次不从候选招式推导配招、特性机制或获取条件，也不据此证明朱紫存档可获得性和赛事合法性。\n");
    }

    /** Bounded reference lookup, not an interpretation of arbitrary strategy questions. */
    public Optional<String> basicFactAnswer() {
        // Only an explicit simple lookup may ignore unsolicited extra tool reads.
        // A declared moves/mechanics/strategy task is excluded below, not discarded.
        boolean simpleLookup=question!=null&&Pattern.compile("^(?:请)?(?:查|查询|读取).{1,30}的(?:(?:朱[／/]?紫|朱|紫)(?:版本)?的?)?(?:资料|图鉴(?:资料)?)(?:[，,。；;]|$)").matcher(question).find();
        boolean explicitStats=question!=null && Pattern.compile("^(?:请)?(?:查询|读取).{1,80}的属性[与和及、]种族值[，,](?:只|仅)(?:列出|列|展示|显示)(?:图鉴)?已(?:核验|确认|验证)的?(?:字段|事实|数据)[。]?$" ).matcher(question).matches();
        boolean confirmedOnly=question!=null && Pattern.compile("^(?:请)?(?:只|仅)(?:介绍|展示|列出).{1,30}(?:的)?图鉴(?:中)?(?:已确认|已核验|已验证)的?(?:数据|字段|资料)(?:[，,。；;]|$)").matcher(question).find();
        boolean statsTotal=question!=null&&question.contains("种族值")&&Pattern.compile("总和|合计|总计").matcher(question).find();
        simpleLookup=simpleLookup||explicitStats||confirmedOnly||statsTotal;
        if((explicitStats||confirmedOnly) && references.isEmpty())return Optional.of(referenceFailures.isEmpty()
                ? "本次未取得该成员的已核验图鉴资料，无法确认属性和种族值。请重试资料查询。"
                : referenceFailureSummary());
        // Remove only explicit declined recommendation clauses. A positive or mixed
        // follow-up still needs its own evidence and must not disappear into this route.
        String requested=confirmedOnly?question.replaceAll("(?:不要|无需|不必|别)(?:给出|提供|补充|推荐|介绍|讨论)?(?:队伍|阵容|培养建议|搭配建议)(?=[，,。；;！!？?]|$)",""):question;
        if (question == null || question.length() > 600 || references.isEmpty() || partialReferenceFailure
                || (active()&&!simpleLookup) || question.contains("\n") || question.contains("```")
                || (!simpleLookup&&!Pattern.compile("种族值|基础数值|图鉴属性|特点").matcher(question).find())
                || Pattern.compile("培养|建议|搭配|攻略|配招|努力值|个体值|计算|伤害|对比|比较|克制|弱点|特性|招式|保存|修改|队伍").matcher(requested).find())
            return Optional.empty();
        String lower = question.toLowerCase(Locale.ROOT);
        if (references.values().stream().anyMatch(f -> !question.contains(f.speciesNameZh())
                && !Pattern.compile("(?<![a-z0-9-])" + Pattern.quote(f.slug().toLowerCase(Locale.ROOT)) + "(?![a-z0-9-])")
                .matcher(lower).find())) return Optional.empty();
        StringBuilder text = new StringBuilder(referenceSummary());
        for (var f : references.values()) text.append(EvidenceText.inline(f.speciesNameZh()))
                .append("的种族值合计：")
                .append(f.hp() + f.attack() + f.defense() + f.spAttack() + f.spDefense() + f.speed()).append("。\n");
        return Optional.of(text.append("\n实际能力值取决于等级、性格、努力值和个体值。")
                .append("赛事适用性、资料时效与环境强度需另行核对。\n").toString());
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
        if(LocalGuideOnlyRoute.matches(question))
            return "本次检索到的本地攻略没有提供可核验的当前月份完整队伍与胜率数据，不能据此列出实时最强阵容。通用培养或伤害说明不支持这种结论。需要带日期、赛制和完整配置的赛事或样例来源；本次不把无关原文当作答案。";
        if(teamBuildChecks.attempted()||TeamBuildAnswerEvidence.requested(question))return teamBuildChecks.summary(question);
        String prefix = includeReferences ? referenceSummary() : "";
        if (partialReferenceFailure) prefix += "### 资料读取提醒\n本次有资料调用失败或未通过校验；以下仅展示已核对的结果，未取得的资料不作确认。\n\n";
        prefix+=referenceFailureSummary();
        prefix+=resources.summary();
        prefix+=learnsets.summary(question,references.values());
        prefix+=mechanics.summary(question);
        prefix+=buildChecks.summary(question);
        prefix+=machines.summary(question);
        prefix+=overviewTarget(question).map(f->resourceCoverage.summary(question,f,resources)).orElse("");
        if(!referenceFailures.isEmpty()&&references.isEmpty()&&hits.isEmpty())return prefix;
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
        if (statMeaningQuestion(question)) {
            selected = hits.stream().filter(hit -> "build-values-and-defaults-v1".equals(hit.document().id())
                    && hit.document().content().codePointCount(0, hit.document().content().length()) <= 2000).toList();
            if (selected.isEmpty()) return prefix + "本次没有取得解释种族值与实际能力区别的匹配原文，请重试攻略检索。";
        }
        if (projectExplanation && selected.getFirst().document().content().codePointCount(0, selected.getFirst().document().content().length()) > ProjectGuideRequest.MAX_SOURCE_CODE_POINTS)
            return prefix + "### 训练家提醒\n本次匹配的项目原文过长，暂不能展示完整说明。请缩小问题范围；不把截断内容当作完整流程或默认配置答案。";
        StringBuilder answer = new StringBuilder(prefix + (projectExplanation
                ? "### 项目说明（原文参考）\n\n"
                : "### 攻略参考（原文节选）\n\n"));
        for (var hit : selected) {
            var d = hit.document();
            boolean statNote = statMeaningQuestion(question);
            boolean speciesNote=!projectExplanation&&d.applicability()!=null&&!d.applicability().subjects().isEmpty()
                    && d.applicability().matchesSubject(question)
                    && references.values().stream().anyMatch(f->d.applicability().matchesSubject(f.slug())
                        || d.applicability().matchesSubject(f.speciesNameZh()))
                    && d.content().codePointCount(0,d.content().length())<=2000;
            answer.append("**").append(EvidenceText.inline(d.title())).append("**\n\n作者：")
                    .append(EvidenceText.inline(d.author())).append(" · 更新：").append(d.updatedAt()).append("\n\n");
            if(projectExplanation||speciesNote||statNote)answer.append("原文范围：完整文档\n\n");
            else if(hit.citation()!=null)answer.append("原文位置：第 ").append(hit.citation().chunkNo()+1).append(" 个片段\n\n");
            answer.append(EvidenceText.quote(projectExplanation||speciesNote||statNote ? d.content() : excerpt(hit.citation()==null?d.content():hit.citation().text()))).append("\n\n");
        }
        if(selected.stream().anyMatch(hit->hit.document().provenance()!=null))
            answer.append("项目原创登记内容及适用范围待人工复核。\n\n");
        return answer.append(projectExplanation
                ? "具体项目行为还需结合当前配置与实际执行结果核对。\n"
                : "引用以所列原文为范围。培养与搭配需结合实际配置，伤害结论需计算核对，赛事适用性需另行确认。\n").toString();
    }
    private String referenceSummary() {
        if (references.isEmpty()) return "";
        StringBuilder text = new StringBuilder("### 图鉴速览（资料事实）\n以下为本次实际读取的成员资料；种族值与等级无关。\n\n| 成员 | 属性 | HP | 攻击 | 防御 | 特攻 | 特防 | 速度 |\n| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (var f : references.values()) text.append("| ").append(EvidenceText.inline(com.vio.vioaiagent.pokemon.reference.RegionalFormName.display(f))).append(" | ")
                .append(EvidenceText.inline(f.type1()+(f.type2()==null?"":" / "+f.type2()))).append(" | ")
                .append(f.hp()).append(" | ").append(f.attack()).append(" | ").append(f.defense()).append(" | ")
                .append(f.spAttack()).append(" | ").append(f.spDefense()).append(" | ").append(f.speed()).append(" |\n");
        return text.append("\n来源 PokéAPI 本地参考\n\n").toString();
    }
    private static boolean blank(String text, int max) { return text == null || text.isBlank() || text.length() > max; }
    private static String excerpt(String text) {
        if (text.codePointCount(0, text.length()) <= 700) return text;
        String part = text.substring(0, text.offsetByCodePoints(0, 700));
        int sentence = part.lastIndexOf('。');
        return sentence >= 200 ? part.substring(0, sentence + 1) : part;
    }
}
