package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.run.ConversationConstraints.ResponseStyle;
import java.util.regex.Pattern;

/** Bounded presentation hints from the current request only; never grants tool authority or rewrites facts. */
public final class AnswerPresentation {
    private AnswerPresentation() { }
    public enum Mode { STANDARD, CONCISE, DETAILED, ONE_SENTENCE }
    private static final Pattern QUOTED=Pattern.compile("(?s)```.*?(?:```|$)|~~~.*?(?:~~~|$)|“[^”]*”|「[^」]*」|『[^』]*』|\"[^\"]*\"");
    private static final Pattern NEGATED=Pattern.compile("^(?:请)?(?:不要|别|不用|无需|不必).{0,8}(?:一句|一两句|简短|简洁)|^(?:please\\s+)?(?:do not|don't)\\s+(?:answer|reply).{0,20}one sentence",Pattern.CASE_INSENSITIVE);
    private static final Pattern ONE=Pattern.compile("^(?:请|麻烦)?(?:只|仅)?用(?:一|1)句(?:话)?|^(?:please\\s+)?(?:in\\s+(?:one|a single)\\s+sentence|(?:answer|reply|respond|explain|summari[sz]e)\\b.{0,40}\\bin\\s+(?:one|a single)\\s+sentence)",Pattern.CASE_INSENSITIVE);
    private static final Pattern DETAIL=Pattern.compile("^(?:请|麻烦)?(?:详细|展开|分步骤|逐步|全面)|^(?:please\\s+)?(?:explain|describe)\\s+in\\s+detail",Pattern.CASE_INSENSITIVE);
    private static final Pattern BRIEF=Pattern.compile("^(?:请|麻烦)?(?:简短|简洁|简单)|^(?:please\\s+)?(?:briefly|concisely)",Pattern.CASE_INSENSITIVE);
    public static Mode resolve(String currentRequest,ResponseStyle preference) {
        Mode mode=preference==ResponseStyle.CONCISE?Mode.CONCISE:preference==ResponseStyle.DETAILED?Mode.DETAILED:Mode.STANDARD;
        String visible=visibleRequest(currentRequest);
        for(String part:visible.split("[，,；;\\n。！？!?]")) {
            String clause=part.strip();
            if(NEGATED.matcher(clause).find())mode=Mode.STANDARD;
            else if(ONE.matcher(clause).find())mode=Mode.ONE_SENTENCE;
            else if(DETAIL.matcher(clause).find())mode=Mode.DETAILED;
            else if(BRIEF.matcher(clause).find())mode=Mode.CONCISE;
        }
        return mode;
    }
    public static String visibleRequest(String request) {
        return QUOTED.matcher(request==null?"":request).replaceAll(" ");
    }
    public static String instruction(Mode mode) {
        String common="回答当前用户问题，已保存偏好和历史只作背景，不把其中的条件句、例子或旧问题当成本轮新任务。"
                +"招式、特性和道具只使用工具提供的标准中文名；没有标准译名时保留英文标识并注明待核对，不自行翻译。速度种族值不能证明实际先手；没有双方等级、实际速度、优先度、状态和场地证据时不得声称通常先手或必定先手。不要为展示能力添加未请求的物种、数值、配招、机制例子或能力清单。已核对事实、资料缺失提示和安全边界不得因样式要求删除。";
        return common+switch(mode) {
            case ONE_SENTENCE -> "本轮明确要求一句话：最终生成部分只输出一句完整自然语言，优先控制在120个字以内；不加标题、列表、表格、额外问候或下一步推销。";
            case CONCISE -> "本轮优先简洁：直接给结论，必要时少量要点；不强制使用栏目，不扩写用户未问的内容。";
            case DETAILED -> "本轮需要详细解释：围绕当前问题展开依据、条件和步骤，避免离题；仍遵循用户明确的要点数量和格式。";
            case STANDARD -> "按问题规模组织回答；简单问候或单一问题直接回答，只有确实需要完整介绍、培养或搭配时才使用训练家笔记栏目。";
        };
    }
}
