package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.run.AgentRunContext;
import org.springframework.ai.chat.messages.UserMessage;
import java.util.Optional;
import java.util.regex.Pattern;

/** Reads the owner-scoped program history, without treating assistant text as user intent. */
public final class ConversationRecallReply {
    private ConversationRecallReply() { }
    private static final Pattern BACKGROUND=Pattern.compile("^(?:这是)?背景(?:备注|说明)(?:第[0-9一二三四五六七八九十]{1,4}条)?[：:]\\s*(?:只|仅)回复[“\"]?收到[”\"]?[，,]?(?:不新增(?:目标|任务))?[。.!！]?$" );
    private static final Pattern TOPIC_BACKGROUND=Pattern.compile("^先记录背景[，,](?:我)?(?:目前|现在|当前)?(?:只|仅)?讨论[^，,。！？\\n]{1,40}[，,]本轮(?:只|仅)回复[“\"]?收到[”\"]?[。.!！]?$" );
    public static Optional<String> reply(AgentRunContext context) {
        String question=context.run().prompt();
        if(question==null||question.length()>400||question.contains("\n")||question.contains("```"))return Optional.empty();
        if (question.strip().matches("(?:这|本)(?:一)?轮(?:只|仅)回复[“\"]?收到[”\"]?[，,](?:这是)?历史填充第[0-9]{1,3}轮[。.!！]?")) return Optional.of("收到");
        if (question.matches("(?:先)?(?:记住|记录)(?:我)?的?目标是[^。！？\\n]{1,40}[。]?") ) {
            var target = GymConversationContext.resolve(question, java.util.List.of());
            if (target.isPresent()) return Optional.of("已记录目标为" + target.get().name() + (target.get().phase().equals("rematch") ? "视察重赛。" : "初战。"));
        }
        if(BACKGROUND.matcher(question.strip()).matches() || TOPIC_BACKGROUND.matcher(question.strip()).matches())return Optional.of("收到");
        if(!Pattern.compile("之前|刚才|此前|上文").matcher(question).find()
                ||!Pattern.compile("(?:问的是|问过|讨论的是|话题是)").matcher(question).find()
                ||Pattern.compile("读取|修改|保存|计算|以下资料|[{}]").matcher(question).find())return Optional.empty();
        var history=context.dialogueHistory();
        for(int i=history.size()-1;i>=0;i--) {
            if(!(history.get(i) instanceof UserMessage))continue;
            String prior=history.get(i).getText();
            if(prior==null||BACKGROUND.matcher(prior.strip()).matches()||prior.contains("问的是")||prior.contains("讨论的是"))continue;
            if(!Pattern.compile("问|查询|想|讨论|建议|介绍|看看|解释|看下").matcher(prior).find())continue;
            String bounded=prior.codePointCount(0,prior.length())<=200?prior:prior.substring(0,prior.offsetByCodePoints(0,200))+"（原问题较长，此处为开头）";
            return Optional.of("此前可核对的用户问题是\n\n"+EvidenceText.quote(bounded)+"\n\n依据为当前账号、当前会话保留的用户消息。");
        }
        return Optional.of("当前保留的会话历史中无法定位此前的具体问题，请重述希望继续讨论的主题。");
    }
}
