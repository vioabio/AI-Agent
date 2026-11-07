package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.reference.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeamAnswerEvidenceTest {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static LocalPokeApiRepository.Detail detail(int id, String slug, String name, String type, int speed) {
        return new LocalPokeApiRepository.Detail(new PokemonFact(id,id,id,slug,name,slug,name,"pokeapi-reference-v1",
                "pokemon-scarlet-violet","https://pokeapi.co/api/v2/pokemon/"+id+"/","sha256:fixture","LOCAL_REFERENCE",
                type,null,44,48,65,50,64,speed),List.of(),List.of(),"2026-10-23", "参考资料");
    }
    static TeamReferenceTools.TeamEvidence evidence() {
        var repository = mock(LocalPokeApiRepository.class);
        when(repository.detail("squirtle","pokeapi-reference-v1")).thenReturn(detail(7,"squirtle","杰尼龟","水",43));
        when(repository.detail("charmander","pokeapi-reference-v1")).thenReturn(detail(4,"charmander","小火龙","火",65));
        return new TeamReferenceTools(repository,null).lookupPokemonTeamReference(List.of("squirtle","charmander"),"pokeapi-reference-v1");
    }
    static Stream<Arguments> regressions() throws Exception {
        var root = MAPPER.readTree(Path.of("evaluation/campus/s01/team-advice-guard.json").toFile());
        List<Arguments> values = new ArrayList<>();
        root.path("cases").forEach(row -> values.add(Arguments.of(row.path("id").asText(),row.path("draft").asText(),row.path("expected").asText())));
        return values.stream();
    }
    @ParameterizedTest(name="{0}") @MethodSource("regressions")
    void checkedAdviceDoesNotPublishUnverifiedMechanics(String id, String draft, String expected) {
        var policy = new TeamAnswerEvidence();
        if (expected.equals("hold")) assertNotEquals(draft,policy.checkedAdvice(draft));
        else assertEquals(draft,policy.checkedAdvice(draft));
    }
    @Test void requiresCompleteActualToolEvidenceAndComputesFactsIndependently() throws Exception {
        var policy = new TeamAnswerEvidence();
        policy.begin("请分析队伍。\n参考成员：squirtle、charmander");
        String bundle = MAPPER.writeValueAsString(evidence());
        policy.observe("lookupPokemonReference",bundle);
        assertFalse(policy.complete(),"a single lookup cannot claim whole-team coverage");
        policy.observe("lookupPokemonTeamReference",bundle);
        assertTrue(policy.complete());
        assertTrue(policy.verifiedSummary().contains("杰尼龟 | 水 | 43 | 电×2、草×2"));
        assertFalse(policy.verifiedSummary().contains("杰尼龟 | 水 | 43 | 地面"));
        policy.observe("lookupPokemonTeamReference","[tool.error] 查询失败");
        assertFalse(policy.complete(),"a later failed aggregate must invalidate previous completeness");
    }
    @Test void missingOrDuplicateMemberEvidenceDoesNotPermitFullTeamAdvice() throws Exception {
        var full = evidence();
        var partial = new TeamReferenceTools.TeamEvidence(full.dataReleaseId(),full.calculationVersion(),full.members().subList(0,1),full.limitations());
        var policy = new TeamAnswerEvidence();
        policy.begin("分析队伍\n参考成员：squirtle、charmander");
        policy.observe("lookupPokemonTeamReference",MAPPER.writeValueAsString(partial));
        assertFalse(policy.complete());
        var duplicated = new TeamReferenceTools.TeamEvidence(full.dataReleaseId(),full.calculationVersion(),List.of(full.members().getFirst(),full.members().getFirst()),full.limitations());
        policy.observe("lookupPokemonTeamReference",MAPPER.writeValueAsString(duplicated));
        assertFalse(policy.complete());
        assertTrue(policy.missingAnswer().contains("没有读齐"));
    }
    @Test void aggregateFailsRatherThanReturningPartialMemberSuccess() {
        var repository = mock(LocalPokeApiRepository.class);
        when(repository.detail("squirtle","pokeapi-reference-v1")).thenReturn(detail(7,"squirtle","杰尼龟","水",43));
        when(repository.detail("charmander","pokeapi-reference-v1")).thenThrow(new IllegalStateException("资料不可用"));
        assertThrows(IllegalStateException.class, () -> new TeamReferenceTools(repository,null)
                .lookupPokemonTeamReference(List.of("squirtle","charmander"),"pokeapi-reference-v1"));
    }
}
