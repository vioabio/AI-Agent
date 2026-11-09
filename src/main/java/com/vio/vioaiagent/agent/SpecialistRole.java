package com.vio.vioaiagent.agent;

import java.util.Set;

/** Capability boundaries, not additional credentials or separate background workers. */
public enum SpecialistRole {
    POKEMON_RESEARCH(Set.of("searchPokemonGuides", "lookupPokemonReference", "lookupPokemonResource",
            "lookupPokemonLearnset", "lookupPokemonMachine", "lookupPokemonMechanics", "getTypeChart",
            "checkTypeEffectiveness", "lookupCompetitiveTeams", "lookupCompetitiveTrends", "searchWeb"),
            "Retrieve facts and sources relevant to the current request. Distinguish versioned facts, dated competitive samples and advice. A usage ranking is not a complete team. Never invent inaccessible sources."),
    TEAM_PLANNING(Set.of("listCurrentTeams", "readCurrentTeam", "lookupPokemonTeamReference",
            "lookupPokemonReference", "lookupPokemonResource", "lookupPokemonLearnset", "lookupPokemonMachine",
            "lookupPokemonMechanics", "checkPokemonBuild", "checkPokemonTeamBuild", "searchPokemonGuides",
            "planGymChallenge", "analyzeSavedTeamForGym", "recommendGymTeamCore", "lookupCompetitiveTeams",
            "lookupCompetitiveTrends", "getTypeChart", "checkTypeEffectiveness"),
            "Plan team evidence collection. Read the selected saved team before analyzing it; if its identity is missing, list teams then stop for user selection. Check the full member list with lookupPokemonTeamReference. Separate theoretical recommendations from owned or obtainable resources and competition legality. Do not save, preview changes, calculate damage or invent a party."),
    DAMAGE_CALCULATION(Set.of("listCurrentTeams", "readCurrentTeam", "lookupPokemonReference",
            "lookupPokemonResource", "lookupPokemonTeamReference", "lookupPokemonLearnset", "lookupPokemonMechanics",
            "calculateBattleDamage", "calculateGymDamage", "planGymChallenge"),
            "Verify numerical battle damage using the actual calculation engine. Do not substitute type effectiveness for damage. Preserve all explicit input assumptions. Missing species, moves, levels or battle conditions require clarification; never silently invent them. NPC nature/EV/IV are assumptions. Tera is inactive unless explicitly activated. Never claim a win probability from a single damage range.");

    private final Set<String> tools;
    private final String instruction;
    SpecialistRole(Set<String> tools, String instruction) { this.tools=tools; this.instruction=instruction; }
    public Set<String> tools() { return tools; }
    public String instruction() { return instruction; }
}
