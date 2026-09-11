package com.vio.vioaiagent.pokemon.domain;

/** 规则集与游戏版本的绑定；保存队伍时用于阻止跨版本引用。 */
public record RulesetBinding(String id, String gameReleaseId, String name, boolean enabled) {
    public RulesetBinding {
        if (id == null || id.isBlank() || gameReleaseId == null || gameReleaseId.isBlank()) {
            throw new IllegalArgumentException("规则版本标识不能为空");
        }
    }
}
