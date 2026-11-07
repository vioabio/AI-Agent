package com.vio.vioaiagent.pokemon.reference;

import com.vio.vioaiagent.pokemon.calculator.TypeEffectivenessCalculator;
import org.springframework.ai.tool.annotation.*;
import java.util.*;

/** Read-only aggregate: any member lookup failure rejects the whole evidence bundle. */
public final class TeamReferenceTools {
    private final LocalPokeApiRepository repository;
    private final RunToolScope scope;
    public TeamReferenceTools(LocalPokeApiRepository repository, RunToolScope scope) {
        this.repository = repository; this.scope = scope;
    }
    @Tool(description="完整读取队伍所有参考成员（1至6只）的真实图鉴，并由程序计算防御属性倍率。分析队伍前必须一次传入完整成员清单；任一读取失败不返回完整队伍证据。不读取其他账号队伍，不保存修改。倍率仅为属性关系，不含特性、道具、太晶等效果。")
    public TeamEvidence lookupPokemonTeamReference(
            @ToolParam(description="完整成员的英文slug或全国编号，不重复，1至6个") List<String> queries,
            @ToolParam(description="当前Run的数据版本，不能自行修改") String dataReleaseId) {
        if (scope != null) scope.check(dataReleaseId, null);
        if (queries == null || queries.isEmpty() || queries.size() > 6) throw new IllegalArgumentException("请提供1至6名完整参考成员");
        var normalized = queries.stream().map(query -> query == null ? "" : query.trim().toLowerCase(Locale.ROOT)).toList();
        if (new HashSet<>(normalized).size() != normalized.size()
                || normalized.stream().anyMatch(query -> !query.matches("[a-z0-9][a-z0-9-]{0,79}")))
            throw new IllegalArgumentException("参考成员必须为不重复的英文slug或全国编号");
        var calculator = new TypeEffectivenessCalculator();
        List<MemberEvidence> members = new ArrayList<>();
        Set<Long> identities = new HashSet<>();
        for (String query : normalized) {
            var detail = repository.detail(query, dataReleaseId);
            var fact = detail.fact();
            if (!identities.add(fact.formId())) throw new IllegalArgumentException("不能用不同名称重复选择同一形态");
            String[] defendTypes = fact.type2() == null || fact.type2().isBlank()
                    ? new String[]{fact.type1()} : new String[]{fact.type1(), fact.type2()};
            Map<String, Double> defense = new LinkedHashMap<>();
            for (String attack : calculator.supportedTypes()) defense.put(attack, calculator.calculate(attack, defendTypes));
            members.add(new MemberEvidence(query, detail, Collections.unmodifiableMap(defense)));
        }
        return new TeamEvidence(dataReleaseId, TypeEffectivenessCalculator.CALCULATION_VERSION, List.copyOf(members),
                List.of("仅含基础属性倍率，不含特性、道具、天气、太晶或状态修正", "资料存在不证明当前赛制招式合法性、实时强度或胜率"));
    }
    public record MemberEvidence(String query, LocalPokeApiRepository.Detail detail, Map<String, Double> defensiveMultipliers) { }
    public record TeamEvidence(String dataReleaseId, String calculationVersion, List<MemberEvidence> members, List<String> limitations) { }
}
