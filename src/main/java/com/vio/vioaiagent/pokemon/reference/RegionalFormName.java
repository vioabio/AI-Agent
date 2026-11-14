package com.vio.vioaiagent.pokemon.reference;

/** Bounded regional-name syntax; existence is checked against the species varieties, not inferred. */
public final class RegionalFormName {
    private RegionalFormName() { }
    public static String alolanSpecies(String query) {
        return query != null && query.startsWith("阿罗拉") && query.length() > 3 ? query.substring(3).strip() : null;
    }
    public static String display(com.vio.vioaiagent.pokemon.domain.PokemonFact fact) {
        if (fact.slug().endsWith("-alola")) return "阿罗拉" + fact.speciesNameZh();
        if (!fact.formNameZh().equals(fact.speciesNameZh())) return fact.formNameZh();
        return fact.formId() == fact.speciesId() ? fact.speciesNameZh() : fact.speciesNameZh() + "（" + fact.slug() + "）";
    }
}
