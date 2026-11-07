package com.vio.vioaiagent.pokemon.domain;

/**
 * 可审计的形态事实快照。所有数值都与 dataReleaseId 绑定，并携带来源摘要。
 */
public record PokemonFact(
        long formId,
        long speciesId,
        int nationalNo,
        String slug,
        String speciesNameZh,
        String formKey,
        String formNameZh,
        String dataReleaseId,
        String gameReleaseId,
        String sourceUrl,
        String checksum,
        String releaseStatus,
        String type1,
        String type2,
        int hp,
        int attack,
        int defense,
        int spAttack,
        int spDefense,
        int speed) {

    public PokemonFact {
        if (formId <= 0 || speciesId <= 0 || nationalNo <= 0) {
            throw new IllegalArgumentException("宝可梦身份字段非法");
        }
        require(slug, "slug");
        require(speciesNameZh, "speciesNameZh");
        require(formKey, "formKey");
        require(formNameZh, "formNameZh");
        require(dataReleaseId, "dataReleaseId");
        require(gameReleaseId, "gameReleaseId");
        require(sourceUrl, "sourceUrl");
        require(checksum, "checksum");
        require(releaseStatus, "releaseStatus");
        require(type1, "type1");
        if (type2 != null && type2.isBlank()) type2 = null;
        if (!validStat(hp) || !validStat(attack) || !validStat(defense)
                || !validStat(spAttack) || !validStat(spDefense) || !validStat(speed)) {
            throw new IllegalArgumentException("种族值必须在 0..255 范围内");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
    }

    private static boolean validStat(int value) {
        return value >= 0 && value <= 255;
    }
}
