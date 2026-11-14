package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.pokemon.reference.TeamReferenceTools;
import com.vio.vioaiagent.pokemon.calculator.TypeEffectivenessCalculator;
import com.vio.vioaiagent.pokemon.engine.DamageResultContract;
import java.util.*;

/** Reference-member portion of a suggestion, not a saved team or a verified battle build. */
public record TeamSuggestionAnswerBlock(String schemaVersion, String blockId, String kind,
        String gameReleaseId, String dataReleaseId, String scope, List<PokemonAnswerBlock> members, List<String> missing) {
    public static final String SCOPE = "REFERENCE_MEMBERS_NOT_SAVED_TEAM";
    private static final List<String> MISSING = List.of("尚未核对招式、特性、道具、性格、等级、EV/IV及太晶配置", "尚未验证资源可获得性、联合合法性、伤害或胜率；本卡片未保存队伍");
    public TeamSuggestionAnswerBlock {
        if (!"answer-block-v1".equals(schemaVersion) || !"teamSuggestion".equals(kind) || !SCOPE.equals(scope)
                || !"pokemon-scarlet-violet".equals(gameReleaseId) || !"pokeapi-reference-v1".equals(dataReleaseId)
                || members == null || members.isEmpty() || members.size() > 6
                || members.stream().anyMatch(m -> m == null || !gameReleaseId.equals(m.gameReleaseId()) || !dataReleaseId.equals(m.dataReleaseId()))
                || members.stream().map(PokemonAnswerBlock::formId).distinct().count() != members.size() || !MISSING.equals(missing))
            throw new IllegalArgumentException("Invalid team reference block");
        members = List.copyOf(members); missing = List.copyOf(missing);
        if (!Objects.equals(blockId, identity(members))) throw new IllegalArgumentException("Unbound team block");
    }
    public static TeamSuggestionAnswerBlock from(List<String> queries, String release, TeamReferenceTools.TeamEvidence output) {
        if (queries == null || queries.isEmpty() || queries.size() > 6 || output == null || !Objects.equals(release, output.dataReleaseId())
                || !TypeEffectivenessCalculator.CALCULATION_VERSION.equals(output.calculationVersion())
                || output.members() == null || output.members().size() != queries.size()) throw new IllegalArgumentException("Incomplete team reference");
        var normalized = queries.stream().map(q -> q == null ? "" : q.trim().toLowerCase(Locale.ROOT)).toList();
        if (new HashSet<>(normalized).size() != normalized.size() || normalized.stream().anyMatch(q -> !q.matches("[a-z0-9][a-z0-9-]{0,79}"))) throw new IllegalArgumentException("Invalid team queries");
        var members = new ArrayList<PokemonAnswerBlock>();
        for (int i = 0; i < normalized.size(); i++) {
            var member = output.members().get(i);
            if (member == null || !normalized.get(i).equals(member.query()) || member.detail() == null) throw new IllegalArgumentException("Unbound team member");
            members.add(PokemonAnswerBlock.from(member.detail().fact()));
        }
        return new TeamSuggestionAnswerBlock("answer-block-v1", identity(members), "teamSuggestion", "pokemon-scarlet-violet", release, SCOPE, members, MISSING);
    }
    private static String identity(List<PokemonAnswerBlock> members) { return "team-" + DamageResultContract.digest(members); }
}
