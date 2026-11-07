package com.vio.vioaiagent.pokemon.calculator;

import java.util.List;

/** 队伍结构和版本绑定校验；详细规则由 ruleset_version 继续扩展。 */
public final class TeamLegalityChecker {
    public static final String CALCULATION_VERSION = "team-legality-v1";

    public CheckResult check(String dataReleaseId, String rulesetVersionId, List<Member> members) {
        if (dataReleaseId == null || dataReleaseId.isBlank() || rulesetVersionId == null || rulesetVersionId.isBlank()) {
            throw new IllegalArgumentException("队伍必须绑定 data_release_id 和 ruleset_version_id");
        }
        if (members == null || members.isEmpty() || members.size() > 6) {
            return new CheckResult(false, "队伍成员数量必须为 1～6", CALCULATION_VERSION);
        }
        long distinct = members.stream().map(Member::formKey).distinct().count();
        if (distinct != members.size()) return new CheckResult(false, "队伍存在重复形态", CALCULATION_VERSION);
        for (Member member : members) {
            if (member.moves() == null || member.moves().size() > 4) {
                return new CheckResult(false, "每名成员最多 4 个招式", CALCULATION_VERSION);
            }
        }
        return new CheckResult(true, "合法", CALCULATION_VERSION);
    }

    public record Member(String formKey, List<String> moves) { }
    public record CheckResult(boolean legal, String reason, String calculationVersion) { }
}
