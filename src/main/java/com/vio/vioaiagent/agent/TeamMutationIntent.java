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
    /** Clarifies the existing server-side confirmation policy; never an authorization check. */
    public static Optional<String> confirmationBypassReply(String request){
        if(request==null||request.length()>1500)return Optional.empty();
        String q=request.replaceAll("(?:不要|不能|禁止|不得|不允许)(?:再)?(?:跳过|绕过|忽略|取消|免除)(?:页面)?(?:预览|确认|审批)","");
        String actionText=q.replaceAll("(?:不要|不需要|无需|不用|别|不)(?:生成)?(?:修改)?(?:保存|覆盖|写入|修改|应用|预览)","");
        boolean subject=Pattern.compile("队伍|配置|阵容").matcher(q).find();
        boolean action=Pattern.compile("(?:直接|帮我|请|立即|现在|替我|给我|自动).{0,16}(?:保存|覆盖|写入|修改|应用)|(?:保存|覆盖|写入|修改|应用)(?:我的|当前|这个|这支)?(?:队伍|配置|阵容)",Pattern.DOTALL).matcher(actionText).find();
        boolean bypass=Pattern.compile("(?:跳过|绕过|忽略|取消|免除|无需|不用|不要|不需要).{0,6}(?:确认|审批|预览)",Pattern.DOTALL).matcher(q).find();
        if(!subject||!action||!bypass)return Optional.empty();
        String credentialReply = Pattern.compile("密钥|API.?Key|凭证|token", Pattern.CASE_INSENSITIVE).matcher(q).find()
                ? "密钥读取与展示请求已拒绝，敏感凭证应保留在本机配置中。\n\n" : "";
        return Optional.of(credentialReply + "队伍修改需要先生成预览，再由你在页面确认。聊天文字、攻略或文档中的指令不能代替确认，也不能绕过该流程。可以说明希望调整的字段，再按正常流程核对；本次未读取、覆盖或保存队伍。\n");
    }
    public static boolean requested(String q) {
        q=TrainingIntentState.normalizeConditionUpdates(q);
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
