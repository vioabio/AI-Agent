package com.vio.vioaiagent.pokemon.domain;

import java.util.List;

/** 用户私有的版本化队伍资源；ownerId 永远来自认证上下文。 */
public record SavedTeam(long id, String ownerId, String name, String rulesetId,
                        String dataReleaseId, List<TeamMember> members) {
    public SavedTeam {
        if (id < 0) throw new IllegalArgumentException("队伍 ID 不能为负数");
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId 不能为空");
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new IllegalArgumentException("队伍名称长度必须为 1..128");
        }
        if (rulesetId == null || rulesetId.isBlank() || dataReleaseId == null || dataReleaseId.isBlank()) {
            throw new IllegalArgumentException("队伍必须绑定规则版本和数据版本");
        }
        if (members == null || members.isEmpty() || members.size() > 6) {
            throw new IllegalArgumentException("队伍成员数必须为 1..6");
        }
        members = List.copyOf(members);
    }
}
