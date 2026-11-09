package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.run.AgentRunContext;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;

/** Program-selected, governed reads: resolve the user's own saved team before public catalog routing. */
public final class SavedGymQuestionRoute {
    private static final ObjectMapper JSON=new ObjectMapper();
    private SavedGymQuestionRoute() { }
    public static Optional<String> reply(AgentRunContext context,ToolCallback[] tools){
        String question=context.run().prompt();
        if(!"pokemon-scarlet-violet".equals(context.run().gameReleaseId())||!GymConversationContext.savedTeamRequest(question))return Optional.empty();
        if(question.matches("(?s).*(帮我保存|保存修改|直接保存|确认修改|应用修改|替换成员).*"))return Optional.empty();
        var target=GymConversationContext.resolve(question,context.history());
        if(!GymQuestionPolicy.matches(question)&&!(question.matches("(?s).*(应对|对付|挑战|这个对手).*")&&target.isPresent()))return Optional.empty();
        if(target.isEmpty())return Optional.of("你是在请求分析已保存队伍。当前没有唯一确认的馆主，请告诉我是阿枫、奇树等哪一位，以及初战还是视察重赛；无需重新手填已经保存的成员。");
        try{
            var listed=call(tools,"listCurrentTeams","{}");if(listed.startsWith("[tool."))return Optional.of("未能读取当前账号的已保存队伍，请检查登录状态后重试；本次未用聊天内容猜测队伍。");
            JsonNode teams=JSON.readTree(listed);if(!teams.isArray())throw new IllegalArgumentException("Invalid team list");
            var matches=new ArrayList<JsonNode>();
            var id=java.util.regex.Pattern.compile("(?i)(?:teamId\\s*[=:：]\\s*|队伍(?:编号|ID|#)\\s*[:：=]?\\s*)([0-9]+)").matcher(question);
            Long explicit=id.find()?Long.parseLong(id.group(1)):null;
            for(var team:teams){String name=team.path("name").asText();boolean selected=explicit!=null?team.path("id").asLong()==explicit:!name.isBlank()&&java.util.regex.Pattern.compile("(?i)(?<![a-z0-9_-])"+java.util.regex.Pattern.quote(name)+"(?![a-z0-9_-])").matcher(question).find();if(selected)matches.add(team);}
            if(matches.size()!=1){var names=new ArrayList<String>();teams.forEach(t->names.add(safe(t.path("name").asText())+"（编号"+t.path("id").asLong()+"）"));return Optional.of(matches.isEmpty()?"当前账号检索到的队伍中没有唯一匹配你指定的队伍。请确认队伍名称或编号。"+(names.isEmpty()?" 当前还没有已保存队伍。":" 可选："+String.join("、",names)):"有多支匹配的队伍，请在队伍页选择具体编号后再分析，避免读取错误配置。");}
            var args=JSON.createObjectNode().put("teamId",matches.getFirst().path("id").asLong()).put("gymId",target.get().gymId()).put("phase",target.get().phase());
            String result=call(tools,"analyzeSavedTeamForGym",args.toString());
            if(result.startsWith("[tool."))return Optional.of("已找到你指定的已保存队伍，但本次未能完成只读评估。请查看执行记录中的版本或权限提示；不会退回馆主总览，也不会改动队伍。");
            var analysis=JSON.readTree(result);var report=analysis.path("report");
            var evidence=new GymAnswerEvidence();evidence.observe("planGymChallenge",report.toString());if(!evidence.complete())throw new IllegalArgumentException("Invalid report");
            var text=new StringBuilder("### 已读取队伍：").append(safe(analysis.path("teamName").asText())).append("\n目标：**").append(target.get().name()).append(target.get().phase().equals("initial")?"初战":"视察重赛").append("**").append(target.get().defaultStage()?"（未另指定阶段，先按初战评估）":"（承接本轮或上文已选目标）").append("。以下使用已保存配置，没有重新猜测或保存修改。\n\n| 槽位 | 成员 | 等级 | 特性 / 道具 | 当前招式 |\n| --- | --- | --- | --- | --- |\n");
            for(var member:analysis.path("members")){var moves=new ArrayList<String>();member.path("moveLabels").forEach(m->moves.add(safe(m.asText())));text.append("| ").append(member.path("slot").asInt()).append(" | ").append(safe(member.path("name").asText())).append(" | ").append(member.path("build").path("level").asInt()).append(" | ").append(safe(member.path("abilityLabel").asText())).append(" / ").append(safe(member.path("itemLabel").asText())).append(" | ").append(moves.isEmpty()?"待配置":String.join(" / ",moves)).append(" |\n");}
            if(!analysis.path("savedDataReleaseId").asText().equals(context.run().dataReleaseId()))text.append("\n队伍与当前会话资料版本不同：本次只读队伍自身的保存配置，并核对馆主的招式属性覆盖；未混用其他版本的种族值或计算伤害，也未切换队伍规则。\n");
            boolean covered=true,strong=true;for(var foe:report.path("gym").path("opponents")){double best=0;for(var match:report.path("matchups"))if(match.path("opponent").asText().equals(foe.path("name").asText()))best=Math.max(best,match.path("afterTera").asDouble());if(best<=0)covered=false;if(best<2)strong=false;}
            text.append("\n**能否应对：** ").append(strong?"已保存招式对每位对手都有克制候选，具备属性覆盖条件。":covered?"已保存招式有可命中的覆盖候选，但部分对手缺少克制招式。":"当前保存配置还有无法评估或无法命中的对手，请先补齐下面的缺口。").append("这不是必胜或一击击倒保证。\n");
            String advice=evidence.adviceOnly();for(var foe:report.path("gym").path("opponents"))advice=advice.replace(foe.path("name").asText(),foe.path("nameZh").asText(foe.path("name").asText()));
            text.append("\n").append(advice);
            text.append("\n出招使用建议：先从上面覆盖候选中选择首发与招式，遇到免疫或不利效果时再换候选。只建议已保存的招式，不自行补造招式机、捕获目标或新持物。当前徽章、获得等级和剧情进度未确认，不能据此保证服从、已解锁重赛或必胜；需要精确伤害时再明确状态与NPC数值假设。");
            return Optional.of(text.toString());
        }catch(Exception failure){return Optional.of("本次未能读取并核对指定队伍与馆主，未生成出招结论或保存修改。请确认登录账号、队伍名称及所选游戏版本后重试。");}
    }
    private static String call(ToolCallback[] tools,String name,String arguments){return Arrays.stream(tools).filter(t->t.getToolDefinition().name().equals(name)).findFirst().orElseThrow(()->new IllegalStateException("Missing governed tool")).call(arguments);}
    private static String safe(String text){return text.replaceAll("[|\\r\\n<>`*\\[\\]()]"," ");}
}
