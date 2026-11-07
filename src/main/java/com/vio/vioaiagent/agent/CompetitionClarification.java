package com.vio.vioaiagent.agent;
import java.util.Optional;
import java.util.regex.Pattern;
/** Explicit missing competition context is clarified without inventing rules. */
public final class CompetitionClarification {
    private CompetitionClarification() { }
    public static Optional<String> reply(String q) {
        if (q == null || q.length() > 800 || q.contains("\n")
                || Pattern.compile("资料|引用|```|保存|修改|预览").matcher(q).find()
                || !Pattern.compile("正式(?:比赛|赛事)").matcher(q).find()
                || !Pattern.compile("不知道|没给|未给|未提供|还没|缺少").matcher(q).find()) return Optional.empty();
        return Optional.of("### 先确认参赛条件\n请补充或确认以下信息；已经提供的条件可以保留：\n"
                + "1. 赛事名称、主办方及单打/双打赛制。\n2. 官方规则版本或赛季，以及对应规则公告。\n"
                + "3. 赛事日期，确认适用的规则与禁限名单。\n4. 目标对手、队伍定位及已有培养配置。\n\n"
                + "当前休闲单打上下文不能代替正式赛事规则。本地图鉴、参数范围通过和通用攻略不能证明参赛合法或培养最优。"
                + "条件未齐前，本次不计算精确对局、不修改队伍；补齐后再根据对应规则和实际配置核对。\n");
    }
}
