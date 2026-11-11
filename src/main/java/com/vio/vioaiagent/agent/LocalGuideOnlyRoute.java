package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.run.AgentRunContext;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;

/** Local-source availability questions cannot acquire private-team or live-web evidence. */
public final class LocalGuideOnlyRoute {
    private LocalGuideOnlyRoute() { }
    public static boolean matches(String q) {
        return q != null && q.length() < 800 && !q.contains("\n")
                && q.matches("(?s).*(本地|原文).*" ) && q.matches("(?s).*(攻略|笔记|文档).*" )
                && q.matches("(?s).*(当前|月份|本月|实时).*" ) && q.matches("(?s).*(最强|热门|胜率).*" )
                && !q.matches("(?s).*(读取|保存|修改|我的队伍|我的阵容).*" );
    }
    public static Optional<String> reply(AgentRunContext context, ToolCallback[] tools) {
        if (!matches(context.run().prompt())) return Optional.empty();
        var tool=Arrays.stream(tools).filter(t->t.getToolDefinition().name().equals("searchPokemonGuides")).findFirst();
        if (tool.isEmpty()) return Optional.of("本地攻略检索未启用，本次未取得来源，不能确认当前月份的队伍与胜率。");
        try {
            String args=new ObjectMapper().writeValueAsString(Map.of("query",context.run().prompt(),"gameReleaseId",context.run().gameReleaseId()));
            String output=tool.get().call(args);
            if(output==null||output.startsWith("[tool."))return Optional.of("本地攻略检索失败，本次未取得可核验来源，不使用保存队伍或历史回答补齐实时信息。");
            var evidence=new GuideAnswerEvidence();evidence.begin(context.run().prompt());evidence.observe("searchPokemonGuides",output);
            return Optional.of(evidence.verifiedAnswer());
        } catch(com.vio.vioaiagent.observability.TaskBudgetExceededException|com.vio.vioaiagent.observability.ContextBudgetExceededException|java.util.concurrent.CancellationException stopped){throw stopped;}
        catch(Exception unavailable){return Optional.of("本地攻略检索失败，本次未取得可核验来源，不使用保存队伍或历史回答补齐实时信息。");}
    }
}
