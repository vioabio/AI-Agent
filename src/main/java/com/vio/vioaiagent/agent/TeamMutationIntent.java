package com.vio.vioaiagent.agent;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;
import java.util.regex.Pattern;
/** Preserve explicit editing intent through the existing governed read/preview workflow. */
public final class TeamMutationIntent {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private TeamMutationIntent() { }
    public static boolean requested(String q) {
        return q != null && q.length() <= 1500 && !q.contains("\n")
                && !Pattern.compile("资料|引用|解释|为什么|```|不要修改|不修改队伍").matcher(q).find()
                && Pattern.compile("队伍.{0,50}(?:招式|性格|等级|道具).{0,20}(?:改成|改为|换|只保留|调整)").matcher(q).find();
    }
    static Optional<String> rationaleArguments(String q,String tool,String arguments) {
        if(!requested(q)||!"previewTeamChanges".equals(tool)||arguments==null||arguments.length()>16000)return Optional.empty();
        try {
            var input=JSON.readTree(arguments);
            if(!input.isObject()||!input.path("changes").isArray())return Optional.empty();
            ((com.fasterxml.jackson.databind.node.ObjectNode)input).put("rationale","按用户明确指定修改所列配置，其他字段保持原值，仅生成预览并等待页面确认；不据此推断机制效果或最优性。");
            return Optional.of(JSON.writeValueAsString(input));
        }catch(Exception invalid){return Optional.empty();}
    }
    public static ToolCallback[] planningTools(String q, ToolCallback[] tools) {
        return !requested(q) ? tools : Arrays.stream(tools).filter(t -> Set.of("listCurrentTeams", "readCurrentTeam", "previewTeamChanges")
                .contains(t.getToolDefinition().name())).toArray(ToolCallback[]::new);
    }
    public static String instruction(String q) {
        return requested(q) ? "\n用户明确修改队伍：先readCurrentTeam，再仅按指定字段调用previewTeamChanges，等待页面确认。学习途径不能代替变更预览，不额外补齐配置，不宣称已保存。" : "";
    }
}
