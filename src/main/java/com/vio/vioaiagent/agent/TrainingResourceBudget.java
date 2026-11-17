package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.*;
import java.util.*;
import java.util.regex.Pattern;

/** Hypothetical one-copy-per-recorded-slot accounting, never inventory or an execution plan. */
public final class TrainingResourceBudget {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LP_CAP = Pattern.compile("制作LP上限(0|[1-9][0-9]{0,8}|1000000000)");
    private TrainingResourceBudget() { }

    public static String describe(TeamBuildCheckFacts.Report supplied, TrainingIntentState intent) {
        var out = new StringBuilder("\n**整队学习器资源核对**\n\n");
        if (intent.conflicted()) return out.append("声明条件有冲突，暂不计算制作预算，请先明确更新条件。\n\n").toString();
        String policy = intent.declared("资源").orElse("");
        boolean blocked = Set.of("不制作学习器", "仅使用已有学习器").contains(policy);
        var cap = LP_CAP.matcher(policy);
        Long limit = cap.matches() ? Long.valueOf(cap.group(1)) : null;
        if (blocked) out.append("已声明").append(policy).append("，本次不安排制作；以下仅保留条件式参考，已有学习器和已学招式仍需确认。\n\n");
        else if (limit != null) out.append("声明的制作LP上限：").append(limit).append("；不是已核验的余额。\n\n");
        else if ("不使用道具".equals(policy)) out.append("“不使用道具”尚未区分战斗用道具与培养学习器，需明确是否允许制作；本次不把它理解为制作许可。\n\n");
        else out.append("制作许可和预算尚未明确；即使声明材料齐全，也不能据此认定背包与解锁已核验。\n\n");
        if (supplied == null) return out.append("未取得绑定的整队学习记录，本次不计算份数、LP或材料。\n\n").toString();
        var checked = TeamBuildCheckFacts.validate(JSON.valueToTree(supplied));
        long lp = 0; int copies = 0, unknown = 0;
        var labels = new TreeMap<String,Integer>();
        var materials = new TreeMap<String,Long>();
        for (var member : checked.members()) for (var move : member.report().moves()) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            if (!"RECORDED".equals(move.status()) || move.learning() == null
                    || move.learning().entries().stream().noneMatch(e -> "machine".equals(e.methodKey()))) continue;
            copies++;
            var recipe = MachineRecipeCatalog.read(move.normalized(), checked.request().gameReleaseId());
            if (!"CONSISTENT_REFERENCE".equals(recipe.status())) { unknown++; continue; }
            lp = Math.addExact(lp, recipe.leaguePoints().longValue());
            labels.merge(recipe.label(), 1, Integer::sum);
            for (var material : recipe.materials()) materials.merge(material.name(), (long) material.quantity(), Math::addExact);
        }
        if (copies == 0) return out.append("本次没有已验证的学习器途径记录，不计算制作需求，也不据此断言无法学习。\n\n").toString();
        out.append("估算情景：若每个具有学习器记录的已保存招式槽位都各需制作1份，共 ").append(copies)
                .append(" 个槽位；同一学习器跨成员不合并为1份，已学招式及已有库存尚未扣除。这不是实际缺口。\n\n");
        if (labels.isEmpty()) out.append("没有来源一致的配方，无法给出已知配方小计。\n\n");
        else {
            out.append("来源一致的已知配方小计：").append(copies - unknown).append(" 份，").append(lp).append(" LP。\n");
            labels.forEach((label, count) -> out.append("- ").append(label).append(" × ").append(count).append(" 份。\n"));
            materials.forEach((name, count) -> out.append("- ").append(EvidenceText.inline(name)).append(" × ").append(count).append("。\n"));
            if (limit != null) out.append(lp > limit
                    ? "该情景的已知配方小计已超过声明LP上限；不能据此安排整批制作。\n"
                    : "已知小计未超过声明上限；仍不能据此认定整体预算充足或当前可制作。\n");
        }
        if (unknown > 0) out.append("另有 ").append(unknown).append(" 个槽位的配方未收录或来源存在差异，未计入LP与材料；不提供完整总价或资源齐全结论。\n");
        return out.append("\n依据为本次学习记录与随包社区配方（待人工及游戏内核验，来源见下方逐招参考）。实际需求、库存、剧情解锁和获取路线仍待核对；本次不消耗资源、不修改队伍。\n\n").toString();
    }
}
