package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.run.AgentRunContext;
import org.springframework.ai.tool.ToolCallback;
import java.util.Optional;

/** One coordinator: plan -> existing specialist tools/routes -> review -> publish at the Run boundary. */
public final class GymDialogueRouter {
    private GymDialogueRouter() { }
    public static Optional<String> reply(AgentRunContext context, ToolCallback[] tools) {
        var plan = GymTurnPlan.from(context);
        if (!"pokemon-scarlet-violet".equals(plan.gameReleaseId())) return Optional.empty();
        if(context.run().prompt().matches("(?s).*(剑盾|剑／盾|剑/盾|绿宝石|火红|叶绿|究极之日|究极之月).*")&&!context.run().prompt().contains("不要混用"))return GymQuestionPolicy.clarification(context.run().prompt());
        Optional<String> response;
        if (plan.intent() == GymTurnPlan.Intent.CATALOG) {
            var focused=GymFocusedFactReply.reply(plan,context.run().prompt());
            if(focused.isPresent())response=focused;
            else {
            String question=GymConversationContext.requestedActions(context.run().prompt());
            if(plan.target().isPresent()){
                var target=plan.target().get();question+="\n目标馆主："+target.name()+"；"+(target.phase().equals("rematch")?"只看重赛":"只看初战");
            }
            response=GymCatalogAnswer.reply(question,plan.gameReleaseId(),GymOrdinalReference.displayedOrder(context.dialogueHistory()));
            }
        } else if (plan.intent() == GymTurnPlan.Intent.SAVED_TEAM_ANALYSIS) {
            response = SavedGymQuestionRoute.reply(context, tools,true);
        } else if (plan.intent() == GymTurnPlan.Intent.RECOMMEND_TEAM || plan.intent() == GymTurnPlan.Intent.GENERAL_ADVICE) {
            if (plan.target().isEmpty()) return Optional.of("你想为哪位馆主推荐队伍或制定应对策略？请告诉我馆主名字；已经保存的队伍只需提供名称，不必重填配置。");
            response = plan.intent()==GymTurnPlan.Intent.RECOMMEND_TEAM
                    ? GymTeamRecommendationRoute.reply(context,tools,plan.target().get()).or(()->GymGeneralAdviceRoute.reply(context))
                    : GymGeneralAdviceRoute.reply(context);
        } else return Optional.empty();
        if (response.isEmpty()) return Optional.empty();
        // Tool failure/selection clarifications remain explicit; no model prose is promoted to tool evidence.
        var verdict = GymAnswerReview.check(plan, response.get());
        if (!verdict.accepted()) return Optional.of("本次回答未通过问题匹配校验，已停止展示不对应的资料。请明确目标馆主和要推荐新队伍还是分析已保存队伍。");
        return response;
    }
}
