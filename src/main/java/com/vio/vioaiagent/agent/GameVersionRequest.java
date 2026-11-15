package com.vio.vioaiagent.agent;

import java.util.Optional;
import java.util.regex.Pattern;

/** Clarify an unspecified replacement game before reading current-version facts. */
public final class GameVersionRequest {
    private static final Pattern SWITCH=Pattern.compile("^(?:请|现在|那就|那么|麻烦)?(?:改按|改用|切换到|切换为|换成|换到|按|使用)(?:其他|别的|另一(?:个|款)?)(?:游戏)?版本(?=[的，,。！？!?；;\\s]|$)");
    private static final Pattern DATA=Pattern.compile("数据|资料|图鉴|回答|种族值|属性");
    private static final Pattern NAMED_OTHER_GAME=Pattern.compile("剑盾|剑[／/]盾|绿宝石|火红|叶绿|究极之日|究极之月");
    private GameVersionRequest() { }
    public static Optional<String> clarification(String question,String gameReleaseId) {
        if (!"pokemon-scarlet-violet".equals(gameReleaseId) || question==null || question.length()>600
                || !SWITCH.matcher(question.strip()).find() || !DATA.matcher(question).find()
                || NAMED_OTHER_GAME.matcher(question).find()) return Optional.empty();
        return Optional.of("当前会话绑定朱／紫。请补充要切换到的具体游戏版本名称，随后核对该版本的可用资料范围。");
    }
}
