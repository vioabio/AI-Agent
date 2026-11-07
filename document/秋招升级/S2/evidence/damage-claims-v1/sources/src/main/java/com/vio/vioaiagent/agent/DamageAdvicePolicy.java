package com.vio.vioaiagent.agent;

import java.util.List;
import java.util.regex.Pattern;

/** Finite assertion gate for advice based on one calculation, not a semantic correctness judge. */
public final class DamageAdvicePolicy {
    public static final String VERSION = "bounded-damage-conditional-claims-v1";
    private static final int MAX_LENGTH = 32_768;
    private static final List<Pattern> ASSERTIONS = List.of(
            pattern("(?:伤害|区间|数值|输出|百分比|结果).{0,12}(?:一定|必然|必定|肯定|就|会|将|能).{0,12}(?:变|增|减|高|低|提升|降低|翻倍|减半|不变)"),
            pattern("(?:会|将|能|必然|一定).{0,6}(?:提升|提高|降低|改变|增加|减少|增强|削弱|翻倍|减半).{0,8}(?:伤害|区间|输出|百分比)"),
            pattern("(?:后|时|下).{0,8}(?:伤害|区间|输出).{0,8}(?:更高|更低|提高|降低|增大|缩小|翻倍|减半|不变)"),
            pattern("(?:damage|range|output|percentage)\\s+(?:(?:will|would|can|could|may|must|always|necessarily)\\s+){1,3}(?:be\\s+)?(?:change|increase|decrease|improve|rise|fall|remain|double|halve|become|higher|lower)"),
            pattern("(?:increase[sd]?|decrease[sd]?|reduce[sd]?|lower[sd]?|raise[sd]?|change[sd]?|double[sd]?|halve[sd]?)\\s+(?:the\\s+)?(?:damage|range|output)")
    );
    private static final Pattern QUESTION = pattern("是否|会不会|能否|有无|\\bwhether\\b");
    private static final Pattern NEGATED_PREFIX = pattern("(?:不能|无法|不应|不要|尚不能|尚无法)(?:保证|断言|认定|证明|确定|推断)?\\s*$|(?:do not|don't|cannot|can't|must not)\\s+(?:assume|guarantee|claim|conclude|infer)\\s*$|\\bwhether\\s*$");
    private DamageAdvicePolicy() { }

    public static boolean canPublish(String draft) {
        if (draft == null || draft.isBlank() || draft.length() > MAX_LENGTH) return false;
        // Negation or a question in one clause cannot authorize an assertion in the next.
        for (String clause : draft.split("[，,。.!！？?；;\\r\\n]+")) {
            for (Pattern assertion : ASSERTIONS) {
                var matches = assertion.matcher(clause);
                int from = 0;
                while (matches.find(from)) {
                    String matched = matches.group();
                    String prefix = clause.substring(Math.max(0, matches.start() - 48), matches.start());
                    if (!QUESTION.matcher(matched).find() && !NEGATED_PREFIX.matcher(prefix).find()) return false;
                    // Check overlaps so an earlier question cannot swallow a later assertion.
                    from = matches.start() + 1;
                }
            }
        }
        return true;
    }

    private static Pattern pattern(String value) { return Pattern.compile(value, Pattern.CASE_INSENSITIVE); }
}
