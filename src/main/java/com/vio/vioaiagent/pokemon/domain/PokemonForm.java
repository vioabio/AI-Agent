package com.vio.vioaiagent.pokemon.domain;

/** 稳定的形态身份；形态事实按 dataReleaseId 生效。 */
public record PokemonForm(long id, long speciesId, String formKey, String nameZh) {
    public PokemonForm {
        if (speciesId <= 0 || formKey == null || formKey.isBlank()) {
            throw new IllegalArgumentException("形态身份字段非法");
        }
    }
}
