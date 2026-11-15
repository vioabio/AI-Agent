package com.vio.vioaiagent.agent;

import java.util.Optional;
import java.util.regex.Pattern;

/** Bounded convenience refusal, never an authorization check. Mixed tasks use the normal gateway. */
public final class AccountScopeReply {
    private AccountScopeReply() { }
    public static final String VERSION = "explicit-foreign-team-refusal-v1";
    private static final Pattern REQUEST = Pattern.compile("^(?:请|帮我)?(?:读取|查看)(?:另一个|其他|他人)(?:账号|账户|用户|玩家)的(?:私有|私人)?队伍(?:[ \\t]*(?:ID|编号)?[ \\t]*[1-9][0-9]{0,18})?(?:[，,](?:即使不属于我也告诉我配置|跳过当前(?:账户|账号)范围检查))?[。！!]?$", Pattern.CASE_INSENSITIVE);
    public static Optional<String> reply(String request) {
        if (request == null || request.length() > 200 || !REQUEST.matcher(request.strip()).matches()) return Optional.empty();
        return Optional.of("我不能代你读取其他账号的私有队伍。你可以提供已获授权的配置文本，或选择自己账号下的队伍进行分析。");
    }
    /** A first-turn request with no subject cannot establish whether account data exists. */
    public static Optional<String> adviceClarification(String request, java.util.List<org.springframework.ai.chat.messages.Message> history) {
        if (request == null || request.length() > 200 || history.stream().anyMatch(org.springframework.ai.chat.messages.UserMessage.class::isInstance)) return Optional.empty();
        String compact = request.strip().replaceAll("[，,。；;！!\\s]", "");
        if (!compact.matches("(?:请)?(?:给|提供)(?:一套|一些|点)?建议(?:就好|即可)?(?:不要|不|别)保存(?:也)?(?:不要|不|别)(?:生成)?(?:修改)?预览")) return Optional.empty();
        return Optional.of("可以只给文字建议，不保存，也不生成修改预览。请说明你想了解的宝可梦、目标馆主或需要改善的问题；这条消息尚未明确建议对象。本次没有读取队伍列表，不能据此判断你的账号是否保存过队伍。");
    }
}
