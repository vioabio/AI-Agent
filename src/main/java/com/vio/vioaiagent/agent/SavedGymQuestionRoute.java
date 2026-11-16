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
        return reply(context,tools,false);
    }
    static Optional<String> reply(AgentRunContext context,ToolCallback[] tools,boolean contextual){
        String question=context.run().prompt();
        if(!"pokemon-scarlet-violet".equals(context.run().gameReleaseId())||!contextual&&!GymConversationContext.savedTeamRequest(question))return Optional.empty();
        if(question.matches("(?s).*(帮我保存|保存修改|直接保存|确认修改|应用修改|替换成员).*"))return Optional.empty();
        var target=GymConversationContext.resolve(question,context.dialogueHistory());
        if(!contextual&&!GymQuestionPolicy.matches(question)&&!(question.matches("(?s).*(应对|对付|挑战|这个对手).*")&&target.isPresent()))return Optional.empty();
        if(target.isEmpty())return Optional.of("你是在请求分析已保存队伍。当前没有唯一确认的馆主，请告诉我是阿枫、奇树等哪一位，以及初战还是视察重赛；无需重新手填已经保存的成员。");
        try{
            var listed=call(tools,"listCurrentTeams","{}");if(listed.startsWith("[tool."))return Optional.of("未能读取当前账号的已保存队伍，请检查登录状态后重试；本次未用聊天内容猜测队伍。");
            JsonNode teams=JSON.readTree(listed);if(!teams.isArray())throw new IllegalArgumentException("Invalid team list");
            var matches=new ArrayList<JsonNode>();
            var id=java.util.regex.Pattern.compile("(?i)(?:teamId\\s*[=:：]\\s*|队伍(?:编号|ID|#)\\s*[:：=]?\\s*)([0-9]+)").matcher(question);
            Long explicit=id.find()?Long.parseLong(id.group(1)):null;
            for(var team:teams){String name=team.path("name").asText();boolean selected=explicit!=null?team.path("id").asLong()==explicit:!name.isBlank()&&java.util.regex.Pattern.compile("(?i)(?<![a-z0-9_-])"+java.util.regex.Pattern.quote(name)+"(?![a-z0-9_-])").matcher(question).find();if(selected)matches.add(team);}
            if(matches.isEmpty()&&explicit==null&&contextual&&!GymConversationContext.savedTeamRequest(question))OwnedTeamReadRoute.select(teams,question,context.dialogueHistory(),true).ifPresent(matches::add);
            if(matches.size()!=1){var names=new ArrayList<String>();teams.forEach(t->names.add(safe(t.path("name").asText())+"（编号"+t.path("id").asLong()+"）"));return Optional.of(matches.isEmpty()?"当前账号检索到的队伍中没有唯一匹配你指定的队伍。请确认队伍名称或编号。"+(names.isEmpty()?" 当前还没有已保存队伍。":" 可选："+String.join("、",names)):"有多支匹配的队伍，请在队伍页选择具体编号后再分析，避免读取错误配置。");}
            var args=JSON.createObjectNode().put("teamId",matches.getFirst().path("id").asLong()).put("gymId",target.get().gymId()).put("phase",target.get().phase());
            String result=call(tools,"analyzeSavedTeamForGym",args.toString());
            if(result.startsWith("[tool."))return Optional.of("已找到你指定的已保存队伍，但本次未能完成只读评估。请查看执行记录中的版本或权限提示；不会退回馆主总览，也不会改动队伍。");
            var analysis=JSON.readTree(result);var report=analysis.path("report");
            var evidence=new GymAnswerEvidence();evidence.observe("planGymChallenge",report.toString());if(!evidence.complete())throw new IllegalArgumentException("Invalid report");
            boolean perMemberRequested = question.matches("(?s).*(逐个|逐一|每个|每位).*") && question.contains("成员");
            if(!perMemberRequested && question.matches("(?s).*(实际保存.{0,10}招式|现有.{0,8}(招|成员)|怎么出招|如何出招|建议怎么|调整哪些成员|需要调整).*"))
                return Optional.of(focused(analysis,report,target.get().name(),target.get().phase(),context.run().dataReleaseId(),question.matches("(?s).*(调整哪些成员|需要调整).*")));
            var text=new StringBuilder("### 已读取队伍：").append(safe(analysis.path("teamName").asText())).append("\n目标：**").append(target.get().name()).append(target.get().phase().equals("initial")?"初战":"视察重赛").append("**").append(target.get().defaultStage()?"（未另指定阶段，先按初战评估）":"（承接本轮或上文已选目标）").append("。以下使用已保存配置，没有重新猜测或保存修改。\n\n| 槽位 | 成员 | 等级 | 特性 / 道具 | 当前招式 |\n| --- | --- | --- | --- | --- |\n");
            for(var member:analysis.path("members")){var moves=new ArrayList<String>();member.path("moveLabels").forEach(m->moves.add(safe(m.asText())));text.append("| ").append(member.path("slot").asInt()).append(" | ").append(safe(member.path("name").asText())).append(" | ").append(member.path("build").path("level").asInt()).append(" | ").append(safe(member.path("abilityLabel").asText())).append(" / ").append(safe(member.path("itemLabel").asText())).append(" | ").append(moves.isEmpty()?"待配置":String.join(" / ",moves)).append(" |\n");}
            if(!analysis.path("savedDataReleaseId").asText().equals(context.run().dataReleaseId()))text.append("\n队伍与当前会话资料版本不同：本次只读队伍自身的保存配置，并核对馆主的招式属性覆盖；未混用其他版本的种族值或计算伤害，也未切换队伍规则。\n");
            boolean covered=true,strong=true;for(var foe:report.path("gym").path("opponents")){double best=0;for(var match:report.path("matchups"))if(com.vio.vioaiagent.pokemon.gym.GymChallengeService.suggestedAttack(match.path("move").asText())&&match.path("opponent").asText().equals(foe.path("name").asText()))best=Math.max(best,match.path("afterTera").asDouble());if(best<=0)covered=false;if(best<2)strong=false;}
            text.append("\n**能否应对：** ").append(strong?"已保存招式对每位对手都有克制候选，具备属性覆盖条件。":covered?"已保存招式有可命中的覆盖候选，但部分对手缺少克制招式。":"当前保存配置仍缺少可用于击倒的已核对攻击候选，请先补齐下面的缺口。").append("这不是必胜或一击击倒保证。\n");
            String advice=evidence.adviceOnly();for(var foe:report.path("gym").path("opponents"))advice=advice.replace(foe.path("name").asText(),foe.path("nameZh").asText(foe.path("name").asText()));
            if (perMemberRequested)
                text.append(memberMoveCandidates(analysis, report));
            text.append("\n").append(advice);
            if(report.path("gym").path("battleMode").asText().equals("Doubles")&&analysis.path("members").size()<2)text.append("\n当前保存队伍不足两名成员，无法组成双打的两位首发。调整时优先补足第二位搭档，再核对两位成员的角色与招式；没有候选资料和资源条件时不编造具体新成员。\n");
            text.append("\n出招使用建议：先从上面覆盖候选中选择首发与招式，遇到免疫或不利效果时再换候选。只建议已保存的招式，不自行补造招式机、捕获目标或新持物。当前徽章、获得等级和剧情进度未确认，不能据此保证服从、已解锁重赛或必胜；需要精确伤害时再明确状态与NPC数值假设。");
            return Optional.of(text.toString());
        }catch(com.vio.vioaiagent.observability.TaskBudgetExceededException|com.vio.vioaiagent.observability.ContextBudgetExceededException|java.util.concurrent.CancellationException stop){throw stop;}
        catch(Exception failure){return Optional.of("本次未能读取并核对指定队伍与馆主，未生成出招结论或保存修改。请确认登录账号、队伍名称及所选游戏版本后重试。");}
    }
    private static String memberMoveCandidates(JsonNode analysis, JsonNode report) {
        var text = new StringBuilder("\n### 逐个成员的出招候选\n依据已保存攻击招式的属性倍率列出候选，实际伤害与行动顺序还需计算。\n");
        for (var member : analysis.path("members")) {
            int slot = member.path("slot").asInt();
            text.append("\n- 槽位").append(slot).append(" ").append(safe(member.path("name").asText())).append("。 ");
            var opponents = new ArrayList<String>();
            for (var foe : report.path("gym").path("opponents")) {
                var matches = new ArrayList<JsonNode>();
                for (var match : report.path("matchups"))
                    if (match.path("slot").asInt() == slot && match.path("opponent").asText().equals(foe.path("name").asText())
                            && com.vio.vioaiagent.pokemon.gym.GymChallengeService.suggestedAttack(match.path("move").asText())) matches.add(match);
                double best = matches.stream().mapToDouble(match -> match.path("afterTera").asDouble()).max().orElse(-1);
                String foeName = safe(foe.path("nameZh").asText(foe.path("name").asText()));
                if (best <= 0) {
                    opponents.add(foeName + (best == 0 ? "暂无可命中的已核对攻击招式" : "缺少可用于击倒的已核对攻击候选"));
                    continue;
                }
                var moves = matches.stream().filter(match -> match.path("afterTera").asDouble() == best)
                        .map(match -> safe(match.path("move").asText())).distinct().limit(2).toList();
                opponents.add("对" + foeName + "可考虑" + String.join("／", moves) + "（" + best + "×）");
            }
            text.append(String.join("。", opponents)).append("。\n");
        }
        return text.toString();
    }

    /** Re-read the current snapshot, but answer only this follow-up rather than repeating the full report. */
    private static String focused(JsonNode analysis,JsonNode report,String name,String phase,String runRelease,boolean adjustment){
        var text=new StringBuilder("### 已读取队伍：").append(safe(analysis.path("teamName").asText())).append("\n目标：**").append(name).append(phase.equals("initial")?"初战":"视察重赛").append("**\n");
        for(var member:analysis.path("members")){
            var moves=new ArrayList<String>();member.path("moveLabels").forEach(m->moves.add(safe(m.asText())));
            text.append("- 槽位").append(member.path("slot").asInt()).append(" ").append(safe(member.path("name").asText())).append("，等级").append(member.path("build").path("level").asInt())
                    .append("：").append(moves.isEmpty()?"招式待配置":String.join(" / ",moves)).append("；道具").append(member.path("build").path("item").asText("").isBlank()?"未明确":safe(member.path("itemLabel").asText())).append("。\n");
        }
        if(!Objects.equals(analysis.path("savedDataReleaseId").asText(),runRelease))text.append("队伍按自身保存版本读取；当前会话版本不同，本次仅核对属性覆盖，未混用种族值或计算伤害。\n");
        text.append(adjustment?"\n**本次调整重点：**\n":"\n**按已保存招式选择出招：**\n");
        if(report.path("gym").path("battleMode").asText().equals("Doubles")&&analysis.path("members").size()<2)
            text.append("- 当前保存队伍不足两名成员，无法组成双打的两位首发；优先补第二位搭档，再核对角色与招式。没有候选资料和资源条件，不编造具体新成员。\n");
        for(var foe:report.path("gym").path("opponents")){
            var matches=new ArrayList<JsonNode>();for(var m:report.path("matchups"))if(com.vio.vioaiagent.pokemon.gym.GymChallengeService.suggestedAttack(m.path("move").asText())&&m.path("opponent").asText().equals(foe.path("name").asText()))matches.add(m);
            double best=matches.stream().mapToDouble(m->m.path("afterTera").asDouble()).max().orElse(-1);
            text.append("- ").append(safe(foe.path("nameZh").asText(foe.path("name").asText()))).append("：");
            if(best<0)text.append("缺少可用于击倒的已核对攻击候选，请先核对招式及其机制。\n");
            else if(adjustment)text.append("现有攻击招式最高属性覆盖为").append(best).append("×；").append(best<2?"仍缺克制打点，先验证替换招式或搭档。":"已有克制打点，继续核对实际伤害与可得条件。").append('\n');
            else if(best==0)text.append("本次已核对的攻击招式均无法命中，需换招或换人。\n");
            else {
                var choices=matches.stream().filter(m->m.path("afterTera").asDouble()==best).limit(3).map(m->"槽位"+m.path("slot").asInt()+" "+safe(m.path("move").asText())+"（"+(m.path("beforeTera").asDouble()!=best?"太晶前"+m.path("beforeTera").asDouble()+"× → 太晶后":"")+best+"×）").toList();
                text.append(String.join("、",choices)).append("。\n");
            }
        }
        var missing=new LinkedHashSet<String>();report.path("missing").forEach(m->missing.add(m.asText()));
        if(!missing.isEmpty()){text.append("\n仍需确认：\n");missing.forEach(m->text.append("- ").append(safe(m)).append('\n'));}
        text.append("\n这里只比较属性覆盖，最多列3个同倍率候选，不代表伤害最高、先手或必胜；本次未修改队伍。\n来源：[Serebii馆主资料](https://www.serebii.net/scarletviolet/").append(phase.equals("rematch")?"gymrematches":"gyms").append(".shtml)。");
        return text.toString();
    }
    private static String call(ToolCallback[] tools,String name,String arguments){return Arrays.stream(tools).filter(t->t.getToolDefinition().name().equals(name)).findFirst().orElseThrow(()->new IllegalStateException("Missing governed tool")).call(arguments);}
    private static String safe(String text){return text.replaceAll("[|\\r\\n<>`*\\[\\]()]"," ");}
}
