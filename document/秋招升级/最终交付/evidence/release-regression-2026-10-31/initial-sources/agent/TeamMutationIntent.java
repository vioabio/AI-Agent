package com.vio.vioaiagent.agent;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;
import java.util.regex.Pattern;
/** Preserve explicit editing intent through the existing governed read/preview workflow. */
public final class TeamMutationIntent {
    private TeamMutationIntent() { }
    public static boolean requested(String q) {
        return q != null && q.length() <= 1500 && !q.contains("\n")
                && !Pattern.compile("资料|引用|解释|为什么|```|不要修改|不修改队伍").matcher(q).find()
                && Pattern.compile("队伍.{0,50}(?:招式|性格|等级).{0,20}(?:改成|改为|换|只保留|调整)").matcher(q).find();
    }
    public static ToolCallback[] planningTools(String q, ToolCallback[] tools) {
        return !requested(q) ? tools : Arrays.stream(tools).filter(t -> Set.of("listCurrentTeams", "readCurrentTeam", "previewTeamChanges")
                .contains(t.getToolDefinition().name())).toArray(ToolCallback[]::new);
    }
    public static String instruction(String q) {
        return requested(q) ? "\n用户明确修改队伍：先readCurrentTeam，再仅按指定字段调用previewTeamChanges，等待页面确认。学习途径不能代替变更预览，不额外补齐配置，不宣称已保存。" : "";
    }
}
