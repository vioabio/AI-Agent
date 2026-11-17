package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.run.AgentRunContext;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;

/** Local-source availability questions cannot acquire private-team or live-web evidence. */
public final class LocalGuideOnlyRoute {
    private LocalGuideOnlyRoute() { }
    public static boolean matches(String q) {
        if (q == null || q.length() >= 800 || q.contains("\n")
                || q.matches("(?s).*(保存修改|修改队伍|读取我的|我的队伍|我的阵容).*")) return false;
        boolean local = q.matches("(?s).*(本地|项目|原文).*") && q.matches("(?s).*(攻略|笔记|文档).*");
        boolean availability = q.matches("(?s).*(有没有|是否有|找不到|缺少|有.{0,100}(详细操作|教程|攻略)).*");
        boolean current = q.matches("(?s).*(当前|月份|本月|实时).*") && q.matches("(?s).*(最强|热门|胜率).*");
        return local && (availability || current);
    }
    public static Optional<String> reply(AgentRunContext context, ToolCallback[] tools) {
        if (!matches(context.run().prompt())) return Optional.empty();
        var tool=Arrays.stream(tools).filter(t->t.getToolDefinition().name().equals("searchPokemonGuides")).findFirst();
        if (tool.isEmpty()) return Optional.of("本地攻略检索未启用，本次未取得来源，不能确认当前月份的队伍与胜率。");
        try {
            String args=new ObjectMapper().writeValueAsString(Map.of("query",context.run().prompt(),"gameReleaseId",context.run().gameReleaseId()));
            String output=tool.get().call(args);
            if(output==null||output.startsWith("[tool."))return Optional.of("本地攻略检索失败，本次未取得可核验来源，不使用保存队伍或历史回答补齐实时信息。");
            var subject = java.util.regex.Pattern.compile("[“\"]([^”\"]{1,80})[”\"]").matcher(context.run().prompt());
            if (subject.find()) {
                String topic = subject.group(1);
                var returned = new ObjectMapper().readTree(output);
                boolean matched = false;
                if (returned.isArray()) for (var hit : returned) {
                    String title = hit.path("document").path("title").asText();
                    String text = hit.path("citation").path("text").asText();
                    if ((title + " " + text).contains(topic)) matched = true;
                }
                if (!matched) return Optional.of("本次检索未找到与“" + EvidenceText.inline(topic) + "”对应的本地攻略。现有结果不足以支持该主题的操作步骤，请提供可信来源后再核对。");
            }
            var evidence=new GuideAnswerEvidence();evidence.begin(context.run().prompt());evidence.observe("searchPokemonGuides",output);
            return Optional.of(evidence.verifiedAnswer());
        } catch(com.vio.vioaiagent.observability.TaskBudgetExceededException|com.vio.vioaiagent.observability.ContextBudgetExceededException|java.util.concurrent.CancellationException stopped){throw stopped;}
        catch(Exception unavailable){return Optional.of("本地攻略检索失败，本次未取得可核验来源，不使用保存队伍或历史回答补齐实时信息。");}
    }
}
