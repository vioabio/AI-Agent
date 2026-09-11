package com.vio.vioaiagent.pokemon.service;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * N4 版本上下文的入口适配器。
 *
 * <p>客户端可以提交已经登记的兼容别名，但 Run、活动查询和后续血缘只
 * 接收 canonical ID。未知别名一律拒绝，避免把玩家界面文本静默当成另一个
 * 游戏/赛制。该 registry 是与 {@code contracts/version-registry-v1.1.json}
 * 对齐的代码快照；registry 仍处于领域负责人 review 时，本类只做显式的
 * 兼容归一化，不代表真实数据或发布状态已经获批。</p>
 *
 * <p>缺失字段保留给上层澄清流程，不在这里猜测默认游戏、DLC 或赛制。若
 * 选择了 DLC，则必须同时显式提供其所属游戏。</p>
 */
public final class PokemonVersionContextValidator {
    public static final String CANONICAL_GAME_RELEASE = "pokemon-scarlet-violet";
    public static final String CANONICAL_DLC = "the-hidden-treasure-of-area-zero";

    private static final Map<String, String> GAME_ALIASES = Map.ofEntries(
            Map.entry("pokemon-scarlet-violet", CANONICAL_GAME_RELEASE),
            Map.entry("scarlet-violet", CANONICAL_GAME_RELEASE),
            Map.entry("sv", CANONICAL_GAME_RELEASE),
            Map.entry("sv-1", CANONICAL_GAME_RELEASE),
            Map.entry("sv-v1", CANONICAL_GAME_RELEASE));
    private static final Map<String, String> DLC_ALIASES = Map.ofEntries(
            Map.entry("the-hidden-treasure-of-area-zero", CANONICAL_DLC),
            Map.entry("zero-treasure", CANONICAL_DLC),
            Map.entry("zero-secret-treasure", CANONICAL_DLC));
    private static final Map<String, String> LANGUAGE_ALIASES = Map.ofEntries(
            Map.entry("zh", "zh-CN"),
            Map.entry("zh-cn", "zh-CN"),
            Map.entry("zh_cn", "zh-CN"),
            Map.entry("en", "en-US"),
            Map.entry("en-us", "en-US"),
            Map.entry("en_us", "en-US"),
            Map.entry("ja", "ja-JP"),
            Map.entry("ja-jp", "ja-JP"),
            Map.entry("ja_jp", "ja-JP"));
    private static final Map<String, String> BATTLE_FORMAT_ALIASES = Map.ofEntries(
            Map.entry("casual-singles", "casual-singles"),
            Map.entry("singles", "casual-singles"),
            Map.entry("casual-doubles", "casual-doubles"),
            Map.entry("doubles", "casual-doubles"));

    /** 供 Controller/Service 使用的无状态默认 registry。 */
    public PokemonVersionContextValidator() {
    }

    /**
     * 将入口字段归一化为 canonical ID。
     *
     * @throws IllegalArgumentException 发现未知别名、控制字符或跨字段冲突时
     */
    public VersionedContext normalize(String gameReleaseId, String dlcId,
                                      String language, String battleFormatId) {
        String game = resolveOptional(gameReleaseId, GAME_ALIASES, "gameReleaseId");
        String dlc = resolveOptional(dlcId, DLC_ALIASES, "dlcId");
        String normalizedLanguage = resolveOptional(language, LANGUAGE_ALIASES, "language");
        String format = resolveOptional(battleFormatId, BATTLE_FORMAT_ALIASES, "battleFormatId");

        if (dlc != null && game == null) {
            throw invalid("DLC_REQUIRES_GAME_RELEASE");
        }
        if (dlc != null && !CANONICAL_GAME_RELEASE.equals(game)) {
            // 当前 v1.1 registry 只登记朱/紫 DLC；未来新增世代必须先扩展
            // registry/migration，而不能在请求中静默接受。
            throw invalid("DLC_GAME_MISMATCH");
        }
        return new VersionedContext(game, dlc, normalizedLanguage, format);
    }

    /** 要求四个字段都已选择的场景（例如发布/评测入口）。 */
    public VersionedContext requireComplete(String gameReleaseId, String dlcId,
                                            String language, String battleFormatId) {
        VersionedContext result = normalize(gameReleaseId, dlcId, language, battleFormatId);
        if (result.gameReleaseId() == null || result.language() == null
                || result.battleFormatId() == null) {
            throw invalid("VERSION_CONTEXT_INCOMPLETE");
        }
        return result;
    }

    private String resolveOptional(String value, Map<String, String> aliases, String field) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > 128 || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw invalid(field + "_INVALID");
        }
        String canonical = aliases.get(trimmed.toLowerCase(Locale.ROOT));
        if (canonical == null) throw invalid(field + "_UNKNOWN");
        return canonical;
    }

    private IllegalArgumentException invalid(String code) {
        // 固定错误码不回显原始输入，便于 API 日志和客户端契约稳定且不泄露
        // 可能包含内部版本命名的自由文本。
        return new IllegalArgumentException("VERSION_CONTEXT_INVALID:" + code);
    }

    /** 已归一化的、可跨 Run/事件边界传递的版本上下文。 */
    public record VersionedContext(String gameReleaseId, String dlcId,
                                   String language, String battleFormatId) {
        public boolean complete() {
            return gameReleaseId != null && language != null && battleFormatId != null;
        }

        public Set<String> keysPresent() {
            java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
            if (gameReleaseId != null) keys.add("gameReleaseId");
            if (dlcId != null) keys.add("dlcId");
            if (language != null) keys.add("language");
            if (battleFormatId != null) keys.add("battleFormatId");
            return Set.copyOf(keys);
        }
    }
}
