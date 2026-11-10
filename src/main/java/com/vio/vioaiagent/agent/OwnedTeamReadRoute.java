package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.run.AgentRunContext;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;
import java.util.regex.Pattern;

/** Selection and rendering of owned records are not free-form model reconstruction. */
public final class OwnedTeamReadRoute {
    private static final ObjectMapper JSON=new ObjectMapper();
    private OwnedTeamReadRoute() { }
    /** A question about a save is not a read request or permission to perform a write. */
    public static Optional<String> saveStatusReply(String question) {
        if (question == null || question.length() > 1500 || question.contains("\n")) return Optional.empty();
        if (!question.matches("(?s).*(队伍|配置|阵容|调整|修改|建议).*")) return Optional.empty();
        if (question.matches("(?s).*(?:请|麻烦|现在|直接|立即|确认|继续).{0,16}(?:保存|应用|写入)(?:一下|修改|变更|调整|队伍|配置|阵容).*")) return Optional.empty();
        boolean status = question.matches("(?s).*(?:是否|有没有|有无).{0,24}(?:保存|写入|应用).*" )
                || question.matches("(?s).*(?:保存|写入|应用)[^。！？?\\n]{0,36}(?:了吗|了么|了没|了没有|吗|么)[？?。\\s]*");
        if (!status) return Optional.empty();
        return Optional.of("队伍分析和调整建议不会自动保存；未在页面确认的建议尚未应用。只有完成修改预览并确认，才会写入配置。\n\n"
                + "本次没有执行保存或修改。若此前已在页面确认过其他变更，请以队伍页实际配置和保存回执为准，不能仅凭聊天文字认定已保存。");
    }
    static Optional<JsonNode> select(JsonNode teams,String question,List<org.springframework.ai.chat.messages.Message> history,boolean contextual) {
        var matches=matches(teams,question);
        if(matches.size()==1)return Optional.of(matches.getFirst());
        if(!matches.isEmpty()||!contextual)return Optional.empty();
        for(int i=history.size()-1;i>=0;i--)if(history.get(i) instanceof UserMessage) {
            matches=matches(teams,history.get(i).getText());if(matches.size()==1)return Optional.of(matches.getFirst());if(matches.size()>1)return Optional.empty();
        }
        return Optional.empty();
    }
    private static List<JsonNode> matches(JsonNode teams,String question) {
        var result=new ArrayList<JsonNode>();
        var id=Pattern.compile("(?i)(?:teamId\\s*[=:：]\\s*|队伍(?:编号|ID|#)\\s*[:：=]?\\s*)([0-9]+)").matcher(question);
        Long explicit=id.find()?Long.parseLong(id.group(1)):null;
        for(var team:teams) {
            String name=team.path("name").asText();
            if(explicit!=null?team.path("id").asLong()==explicit:!name.isBlank()&&Pattern.compile("(?i)(?<![a-z0-9_-])"+Pattern.quote(name)+"(?![a-z0-9_-])").matcher(question).find())result.add(team);
        }return result;
    }
    public static Optional<String> reply(AgentRunContext context,ToolCallback[] tools) {
        String question=context.run().prompt();
        var status = saveStatusReply(question);
        if (status.isPresent()) return status;
        if(ProjectGuideRequest.requiresSource(question)||TeamMutationIntent.requested(question)||question.matches("(?s).*(确认修改|保存修改|改成|改为|生成预览).*"))return Optional.empty();
        boolean contextual=question.matches("(?s).*(这个队伍|刚才.{0,8}队伍|队伍的等级|现有两招).*" );
        if(!contextual&&!(question.matches("(?s).*(读取|我的|已保存|保存的|实际保存).*" )&&question.matches("(?s).*(队伍|阵容|(?:我保存的|已保存).{0,80}配置).*")))return Optional.empty();
        var list=Arrays.stream(tools).filter(t->t.getToolDefinition().name().equals("listCurrentTeams")).findFirst();
        var read=Arrays.stream(tools).filter(t->t.getToolDefinition().name().equals("readCurrentTeam")).findFirst();
        if(list.isEmpty()||read.isEmpty())return Optional.empty();
        if(question.matches("(?s).*(其他|另一个|他人).{0,12}(账号|用户).{0,12}队伍.*"))return Optional.of("不能读取其他账号的私有队伍，请选择当前账号的队伍。");
        try {
            var teams=JSON.readTree(list.get().call("{}"));if(!teams.isArray())return Optional.of("当前账号队伍列表未能读取，本次未分析或修改队伍。");
            var chosen=select(teams,question,context.dialogueHistory(),contextual);
            if(chosen.isEmpty())return Optional.of("当前账号没有唯一匹配本次名称的队伍。请确认队伍名称，或在队伍页选择后重试；本次不读取其他队伍的配置作为替代。");
            String output=read.get().call(JSON.createObjectNode().put("teamId",chosen.get().path("id").asLong()).toString());
            var evidence=new SavedTeamAnswerEvidence();evidence.begin(question);evidence.observe("readCurrentTeam",output);
            if(!evidence.complete())return Optional.of("本次未读取到可校验的保存配置，请检查登录、队伍选择及资料连接。");
            return Optional.of(evidence.verifiedSummary()+(question.matches("(?s).*(只用中文|仅列出|只列出).*" )?"":evidence.verifiedClarification().substring(evidence.verifiedSummary().length())));
        }catch(com.vio.vioaiagent.observability.TaskBudgetExceededException|java.util.concurrent.CancellationException stop){throw stop;}
        catch(Exception unavailable){return Optional.of("当前保存队伍读取失败，未分析或修改任何配置。");}
    }
}
