package com.vio.vioaiagent.pokemon.domain;

/** 形态在某一数据发布和规则集下的可用性。 */
public record PokemonAvailability(long formId, String dataReleaseId, String rulesetId, boolean available) {
    public PokemonAvailability {
        if (formId <= 0 || dataReleaseId == null || dataReleaseId.isBlank()
                || rulesetId == null || rulesetId.isBlank()) {
            throw new IllegalArgumentException("可用性记录的版本键不能为空");
        }
    }
}
