package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.run.ConversationConstraints.ResponseStyle;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;
import java.util.regex.Pattern;

/** Local capability description, not a model answer or evidence that a tool has executed. */
public final class CapabilityCatalog {
    private CapabilityCatalog() { }
    public static Optional<String> unsupportedDamageReply(String request,String language,ToolCallback[] tools) {
        if(!"zh-CN".equals(language)||request==null||request.length()>800
                ||Pattern.compile("[\\r\\n]|```|输入[:：]|条件[:：]|修改|保存|预览|检索|引用").matcher(request).find()
                ||!Pattern.compile("太晶|强化|异常状态|墙").matcher(request).find()
                ||!Pattern.compile("桥接|字段|未给出|条件还没定").matcher(request).find()
                ||!Pattern.compile("计算|伤害|限制|支持").matcher(request).find()
                ||tools==null||Arrays.stream(tools).filter(Objects::nonNull).noneMatch(t->"calculateBattleDamage".equals(t.getToolDefinition().name())))return Optional.empty();
        return Optional.of("当前本地伤害桥接没有表达太晶、能力强化、异常状态、墙或入场伤害的输入字段，本次不计算，也不给假装已涵盖这些条件的数字。"
                +"现有请求表达攻防双方物种、等级、性格、EV/IV、特性、道具、招式，以及单打/双打、天气和地形，并以满HP等已列假设计算。"
                +"如需基准结果，请先明确双方配置与招式，并确认采用未包含上述额外条件的基线；涉及额外机制的结论还需在支持相应字段的工具中验证。"
                +"本次只说明接口边界，不扩写这些机制的效果或倍率，也没有执行搜索或修改队伍。\n");
    }
    public static Optional<String> missingStatisticsReply(String request,String language,ToolCallback[] tools) {
        if(!"zh-CN".equals(language)||request==null||request.length()>800
                ||Pattern.compile("[\\r\\n]|```|修改|保存|预览|calculateBattleDamage|检索.*(?:攻略|笔记)|本地.*(?:攻略|笔记)").matcher(request).find()
                ||!Pattern.compile("这周|今天|本周|当前|最近").matcher(request).find()
                ||!Pattern.compile("(?:胜率|使用率).{0,8}(?:最高|第一|排名)|(?:最强|热门).{0,5}(?:阵容|配招)").matcher(request).find())return Optional.empty();
        if(tools!=null&&Arrays.stream(tools).filter(Objects::nonNull).anyMatch(t->"searchWeb".equals(t.getToolDefinition().name())))return Optional.empty();
        return Optional.of("当前入口未启用实时搜索，本次没有执行赛事统计检索。缺少带赛事名称、日期、赛制与统计口径的可靠来源，不能判定当前胜率最高的配招或使用率第一的阵容。"
                +"本地图鉴和项目攻略不属于实时赛事统计，也不能证明当前排名或参赛合法性。请提供相应来源后再核对，不编造百分比或榜单。\n");
    }
    private static final Pattern QUESTION=Pattern.compile("^(?:请|麻烦)?(?:(?:只|仅)?用(?:一|1)句(?:话)?|用(?:两个|2个|两|2)(?:编号)?要点)?(?:说明|介绍|告诉我)?你(?:能|可以)(?:提供(?:什么|哪些|的)帮助|做什么|帮助我做什么)$");
    private static final Pattern MODIFIER=Pattern.compile("^(?:你好|您好|不要只用一句话|每点一两句|(?:请)?(?:不|不要)调用工具)$");
    private static final Pattern TWO=Pattern.compile("用(?:两个|2个|两|2)(?:编号)?要点");

    /** Deliberately narrow: compound tasks, arbitrary formats and other languages use the normal agent. */
    public static Optional<String> reply(String request, String language, ResponseStyle preference, ToolCallback[] tools) {
        if (!"zh-CN".equals(language) || request==null || request.length()>300) return Optional.empty();
        String visible=AnswerPresentation.visibleRequest(request)
                .replaceFirst("^下面的引号只是一段示例\\s*[:：]?", "");
        boolean question=false;
        for(String part:visible.split("[，,；;\\n。！？!?]")) {
            String clause=part.strip();
            if(clause.isEmpty() || MODIFIER.matcher(clause).matches()) continue;
            if(!QUESTION.matcher(clause).matches()) return Optional.empty();
            question=true;
        }
        if(!question) return Optional.empty();
        Set<String> names=new HashSet<>();
        if(tools!=null) for(ToolCallback tool:tools) if(tool!=null) names.add(tool.getToolDefinition().name());
        List<String> facts=new ArrayList<>(),planning=new ArrayList<>();
        if(names.contains("lookupPokemonReference") || names.contains("lookupPokemonResource")) facts.add("查询可用的宝可梦参考资料");
        if(names.contains("checkTypeEffectiveness") || names.contains("getTypeChart")) facts.add("核对基础属性倍率");
        if(names.contains("readCurrentTeam")) planning.add("读取你指定的本机队伍");
        if(names.contains("calculateBattleDamage")) planning.add("在配置完整时计算伤害范围");
        if(names.contains("searchPokemonGuides")) planning.add("检索项目本地攻略笔记");
        if(names.contains("previewTeamChanges")) planning.add("形成队伍修改预览，保存需你在页面确认");
        if(facts.isEmpty()) facts.add("围绕宝可梦问题提供学习说明，具体事实仍需核实");
        if(planning.isEmpty()) planning.add("结合你提供的条件讨论培养思路，资料不足时说明缺失");
        if(TWO.matcher(visible).find() || AnswerPresentation.resolve(request,preference)==AnswerPresentation.Mode.DETAILED) {
            return Optional.of("1. 我可以"+String.join("、",facts)+"，以实际可读数据为准。\n2. 我可以"+String.join("、",planning)+"；本地参考不代表当前强度或比赛合法性。");
        }
        List<String> concise=new ArrayList<>(facts);concise.add(planning.getFirst());
        return Optional.of("我可以"+String.join("、",concise)+"，本地参考不代表当前强度或比赛合法性。");
    }
}
