package com.vio.vioaiagent.agent;

import org.springframework.ai.chat.messages.*;
import java.util.*;
import java.util.regex.Pattern;

/** Narrow current-meta requests; never derives authorization or a format from reference documents. */
public final class CompetitiveTrendsRequest {
    private CompetitiveTrendsRequest() { }
    public record Plan(String format, boolean webSearch, String query) {
        public boolean completeTeams() {
            return !webSearch && has(query,"队伍|阵容|配队|teams?")
                    && (!has(query,"使用率|胜率|排名|榜单") || has(query,"详细配置|完整配置|六人"));
        }
    }
    private static boolean has(String q, String expression) { return Pattern.compile(expression, Pattern.CASE_INSENSITIVE).matcher(q).find(); }
    public static Optional<Plan> select(String request, List<Message> history) {
        if (request == null || request.length() > 800) return Optional.empty();
        String intent=request.replaceAll("(?:读取不了|读取失败|访问不了|访问失败|获取失败|抓取失败|抓不到)[^。；;]*", "");
        if (has(intent, "[\\r\\n]|```|本地|文档|笔记|不要联网|不用联网|禁止联网|不联网|不需要联网|不必联网|不要搜索|不搜索|不用搜索|不必搜索|不要检索|不用查|不要查|别搜|别联网|离线|解释|定义|是什么意思|为什么|保存|修改|删除|预览|读取|计算|伤害|以下资料|引用文本|示例")) return Optional.empty();
        String positive=intent.replaceAll("(?:不要|不必|无需)(?:把.{0,30}(?:说成|当作)|介绍|讨论|查询)?(?:当前)?(?:热门阵容|热门队伍|使用率|胜率|排行榜)","");
        boolean current = has(positive, "热门|强势|流行|主流|使用率|胜率|meta")
                && has(positive, "队伍|阵容|配队|搭配|使用率|胜率|meta");
        current=current || has(positive,"当前|最近")&&has(positive,"完整样例队伍|完整队伍|六人完整配置");
        current=current || has(positive,"完整.{0,8}(样例|队伍|阵容)|(?:样例|队伍|阵容).{0,8}完整")&&has(positive,"gen9ou|OU|VGC");
        if (!current && history != null && request.length() < 80 && has(request, "OU|VGC|单打|双打|同属性")) {
            // Only a direct answer to our immediately preceding clarification qualifies.
            int lastIndex = history.size() - 1;
            if (lastIndex >= 0 && history.get(lastIndex) instanceof UserMessage) lastIndex--;
            Message last = lastIndex < 0 ? null : history.get(lastIndex);
            current = last instanceof AssistantMessage && last.getText() != null && last.getText().contains("先选一个统计范围");
        }
        if (!current) return Optional.empty();
        if (has(request, "gen[1-8]|第[一二三四五六七八]世代|剑盾|剑／盾|剑/盾|Champions|冠军")) return Optional.of(new Plan("", false, request));
        boolean vgc = has(request, "VGC|官方|排位|对战竞技场|battle.?stadium|regulation");
        boolean ou = has(request, "(?<![a-z])(?:gen9)?(?:doubles)?ou(?![a-z])|Showdown.*(?:单打|双打)");
        boolean mono = has(request, "monotype|同属性");
        if (vgc && (ou || mono)) return Optional.of(new Plan("", false, request));
        if (vgc) {
            boolean scope = has(request,"朱紫|朱／紫|朱/紫|scarlet|violet")
                    && has(request,"regulation\\s*[a-z]|规则\\s*[A-Za-z]|[A-Za-z]规")
                    && has(request,"20\\d{2}[-年/]|今天|本周|当前|最近");
            return Optional.of(new Plan(scope ? "vgc" : "", scope, request));
        }
        if (mono) return Optional.of(new Plan("gen9monotype", false, request));
        if (ou) return Optional.of(new Plan(has(request,"双打|doubles") ? "gen9doublesou" : "gen9ou", false, request));
        return Optional.of(new Plan("", false, request));
    }
    public static String clarification() {
        return "### 先选一个统计范围\n热门队伍会随游戏、赛制和月份变化。你想看哪一种？\n\n"
                + "- **朱／紫 Showdown OU 单打**：可查看公开样例及六人完整配置；使用率另查月度统计。\n"
                + "- **朱／紫 Showdown Doubles OU 双打**：可查看独立的双打统计。\n"
                + "- **官方 VGC／排位**：请补充游戏、规则（Regulation）、赛季或赛事日期，随后检索对应赛事队伍来源。\n\n"
                + "例如直接回复：**当前朱紫 OU 单打有哪些热门队伍？**\n"
                + "当前选择的休闲单打没有统一官方热门榜；不能直接套用 OU 或 VGC。本次尚未执行联网检索。";
    }
}
