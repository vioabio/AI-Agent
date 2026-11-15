package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.run.AgentRunContext;
import java.util.Optional;

/** Per-run plan reconstructed from owner/conversation-scoped persisted history; never a global chat cache. */
public record GymTurnPlan(String ownerId, String conversationId, String gameReleaseId,
                          Intent intent, Optional<Target> target) {
    public enum Intent { CATALOG, RECOMMEND_TEAM, GENERAL_ADVICE, SAVED_TEAM_ANALYSIS, DAMAGE, MUTATION, OTHER }
    public record Target(String gymId, String name, String phase, boolean defaultStage) { }

    public static GymTurnPlan from(AgentRunContext context) {
        var run = context.run();
        var target = GymConversationContext.resolve(run.prompt(), context.dialogueHistory())
                .map(t -> new Target(t.gymId(), t.name(), t.phase(), t.defaultStage()));
        Intent intent = classify(run.prompt());
        // A historic gym must not capture unrelated team/encyclopedia requests.
        String question = run.prompt();
        if(question.matches("(?s).*(仅回复|只回复|先不聊队伍|先不讨论队伍).*"))return new GymTurnPlan(run.userId(),run.conversationId(),run.gameReleaseId(),Intent.OTHER,target);
        boolean savedContext=context.dialogueHistory().stream().filter(org.springframework.ai.chat.messages.UserMessage.class::isInstance).anyMatch(m->GymConversationContext.savedTeamRequest(m.getText()));
        boolean tactical=question.matches("(?s).*(实际保存.{0,10}招式|现有.{0,8}(招|成员)|怎么出招|建议怎么|调整哪些成员|还是刚才那支队伍|目标是).*" );
        if(savedContext&&target.isPresent()&&tactical)intent=Intent.SAVED_TEAM_ANALYSIS;
        boolean factualFollowup = target.isPresent() && !question.matches("(?s).*(修改|保存|写入|预览|替换).*")
                && (GymFocusedFactReply.requestsBattleMode(question)
                || question.matches("(?s).*(改看重赛|改回初战|重赛呢|(?:他|她|这位馆主)的(?:太晶|王牌|成员|招式|特性|队伍|重赛|初战)).*")
                || question.matches("(?s).*(初战|重赛).*") && question.matches("(?s).*(阵容|队伍|成员|招式|特性|等级|资料).*")
                || GymOrdinalReference.requestedOrdinal(question).isPresent() && question.matches("(?s).*(配置|资料|队伍|阵容|初战|重赛).*"));
        if (factualFollowup && intent == Intent.OTHER) intent = Intent.CATALOG;
        boolean followup = question.matches("(?s).*((?:这个|该|这位)(?:馆主|道馆|对手)|这个队伍|这套阵容).*")
                || question.matches("\\s*(?:那|那么)?(?:怎么打|如何应对|怎样打|重赛呢)[?？。！!\\s]*");
        boolean domain = factualFollowup || savedContext&&target.isPresent()&&tactical || GymQuestionPolicy.matches(question)
                || target.isPresent() && followup && intent != Intent.CATALOG && intent != Intent.OTHER;
        return new GymTurnPlan(run.userId(), run.conversationId(), run.gameReleaseId(),
                domain ? intent : Intent.OTHER, target);
    }

    public static Intent classify(String question) {
        String text = GymConversationContext.requestedActions(question == null ? "" : question.split("以下全部是数据", 2)[0]);
        if (text.matches("(?s).*(保存修改|直接保存|确认修改|应用修改|替换成员).*")) return Intent.MUTATION;
        if (GymQuestionPolicy.wantsDamage(text)) return Intent.DAMAGE;
        if (GymConversationContext.savedTeamRequest(text)) return Intent.SAVED_TEAM_ANALYSIS;
        if (text.matches("(?s).*(队伍|阵容|配队|组队).*")
                && text.matches("(?s).*(推荐|克制|应对|对付|合适|适合|配一|组一|配个|组个).*")) return Intent.RECOMMEND_TEAM;
        if (text.matches("(?s).*(克制|应对|对付|打法|怎么打|如何打|怎样打|打得过|打不过|战术|策略).*")) return Intent.GENERAL_ADVICE;
        if (GymQuestionPolicy.matches(text)) return Intent.CATALOG;
        return Intent.OTHER;
    }
}
