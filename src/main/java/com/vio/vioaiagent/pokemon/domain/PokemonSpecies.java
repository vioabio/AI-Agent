package com.vio.vioaiagent.pokemon.domain;

/** 稳定的宝可梦物种身份；属性和数值事实由 dataReleaseId 另行版本化。 */
public record PokemonSpecies(long id, int nationalNo, String nameZh, String slug) {
    public PokemonSpecies {
        if (nationalNo <= 0 || nameZh == null || nameZh.isBlank() || slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("物种身份字段非法");
        }
    }
}
