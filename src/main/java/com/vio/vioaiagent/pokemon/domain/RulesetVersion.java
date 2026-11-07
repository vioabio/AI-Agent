package com.vio.vioaiagent.pokemon.domain;

public record RulesetVersion(String id, String gameReleaseId, String name, boolean enabled) {
    public RulesetVersion {
        if (id == null || id.isBlank() || gameReleaseId == null || gameReleaseId.isBlank()) {
            throw new IllegalArgumentException("规则版本标识不能为空");
        }
    }
}
