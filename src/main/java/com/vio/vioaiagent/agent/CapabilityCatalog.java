package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.run.ConversationConstraints.ResponseStyle;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;
import java.util.regex.Pattern;

/** Local capability description, not a model answer or evidence that a tool has executed. */
public final class CapabilityCatalog {
    private CapabilityCatalog() { }
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
