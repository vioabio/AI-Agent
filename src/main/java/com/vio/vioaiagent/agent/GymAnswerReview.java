package com.vio.vioaiagent.agent;

import java.util.ArrayList;
import java.util.List;

/** Intent alignment gate before publishing any deterministic gym response/delta. */
public final class GymAnswerReview {
    private GymAnswerReview() { }
    public record Verdict(boolean accepted, List<String> issues) { }

    public static Verdict check(GymTurnPlan plan, String answer) {
        var issues = new ArrayList<String>();
        if (answer == null || answer.isBlank()) issues.add("empty_answer");
        else {
            boolean catalog = answer.contains("| 馆主 |") || answer.contains("| 擅长属性 |");
            if (plan.intent() != GymTurnPlan.Intent.CATALOG && catalog) issues.add("catalog_instead_of_task");
            if (plan.target().isPresent() && answer.startsWith("###") && !answer.contains(plan.target().get().name())) issues.add("target_not_addressed");
            if (plan.intent() == GymTurnPlan.Intent.RECOMMEND_TEAM
                    && !answer.contains("队伍") && !answer.contains("成员")) issues.add("recommendation_not_addressed");
        }
        return new Verdict(issues.isEmpty(), List.copyOf(issues));
    }
}
