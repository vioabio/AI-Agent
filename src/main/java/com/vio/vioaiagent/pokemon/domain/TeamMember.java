package com.vio.vioaiagent.pokemon.domain;

/** 已保存队伍中的一个形态槽位。slot 由数据库和领域层双重约束为 1..6。 */
public record TeamMember(long id, long formId, int slot) {
    public TeamMember {
        if (formId <= 0 || slot < 1 || slot > 6) {
            throw new IllegalArgumentException("队伍成员必须包含合法 formId 和 1..6 槽位");
        }
    }
}
