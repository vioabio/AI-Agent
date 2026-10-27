package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LearnsetAnswerEvidenceTest {
    final ObjectMapper json = new ObjectMapper();
    static final String INPUT = "{\"pokemonId\":7,\"moveSlug\":\"water-pulse\",\"dataReleaseId\":\"pokeapi-reference-v1\",\"gameReleaseId\":\"pokemon-scarlet-violet\"}";
    String output(long id, String slug, String move, List<PokemonLearnsetFacts.Entry> rows) throws Exception {
        return json.writeValueAsString(PokemonLearnsetFacts.create(id, slug, move, rows,
                new PokeApiClient.Snapshot("public-fixture", PokeApiClient.digest("public-fixture"), Instant.parse("2026-10-26T00:00:00Z"), "https://pokeapi.co/api/v2/pokemon/" + id + "/")));
    }
    @Test void rendersOnlyRecordedMethodsAndLevelsWithoutHashesTmNumbersOrRawJson() throws Exception {
        var e = new LearnsetAnswerEvidence(); e.observe(output(7, "squirtle", "water-pulse", List.of(new PokemonLearnsetFacts.Entry("level-up", 15, null), new PokemonLearnsetFacts.Entry("machine", 0, null))), INPUT);
        String text = e.summary("", List.of()); assertTrue(text.contains("记录等级：15")); assertTrue(text.contains("招式学习器（machine）"));
        assertFalse(text.contains("sha256:")); assertFalse(text.contains("projectionChecksum")); assertFalse(text.contains("TM011")); assertFalse(text.contains("记录等级：0"));
    }
    @Test void absenceAndLevelZeroDoNotBecomeCannotLearnOrAnInventedRecallOperation() throws Exception {
        var e = new LearnsetAnswerEvidence(); e.observe(output(7, "squirtle", "water-pulse", List.of()), INPUT);
        assertTrue(e.summary("", List.of()).contains("不能据此断言无法学习"));
        e.observe(output(7, "squirtle", "water-pulse", List.of(new PokemonLearnsetFacts.Entry("level-up", 0, 0))), INPUT);
        assertTrue(e.summary("", List.of()).contains("未提供具体学习操作")); assertFalse(e.summary("", List.of()).contains("0级学会"));
    }
    @Test void anotherMemberMoveOrUnboundOutputCannotBeAttributedToTheRequestedPair() throws Exception {
        for (String result : List.of(output(478, "froslass", "water-pulse", List.of(new PokemonLearnsetFacts.Entry("machine", 0, null))),
                output(7, "squirtle", "ice-beam", List.of(new PokemonLearnsetFacts.Entry("machine", 0, null))))) {
            var e = new LearnsetAnswerEvidence(); e.observe(result, INPUT); assertTrue(e.summary("", List.of()).contains("未取得可核验")); assertFalse(e.summary("", List.of()).contains("招式学习器（machine）"));
        }
        var unbound = new LearnsetAnswerEvidence(); unbound.observe(output(7, "squirtle", "water-pulse", List.of()), null); assertTrue(unbound.summary("", List.of()).contains("未取得可核验"));
    }
    @Test void failedPairIsInvalidatedWhileAnotherVerifiedPairIsPreservedAndPrivateErrorsAreOmitted() throws Exception {
        var e = new LearnsetAnswerEvidence(); e.observe(output(7, "squirtle", "water-pulse", List.of(new PokemonLearnsetFacts.Entry("level-up", 15, null))), INPUT);
        String iceInput = INPUT.replace("water-pulse", "ice-beam"); e.observe(output(7, "squirtle", "ice-beam", List.of(new PokemonLearnsetFacts.Entry("machine", 0, null))), iceInput);
        e.observe("[tool.error] private-upstream-error", iceInput); String text = e.summary("", List.of());
        assertTrue(text.contains("记录等级：15")); assertFalse(text.contains(" · ice-beam")); assertFalse(text.contains("private-upstream-error"));
        e.observe("[tool.error] private-upstream-error", INPUT); assertTrue(e.summary("", List.of()).contains("未取得可核验"));
    }
    @Test void explicitLearningRequestRequiresEvidenceEvenIfModelSkipsTheTool() {
        var e = new GuideAnswerEvidence(); e.begin("请查询杰尼龟水之波动的学习途径"); assertTrue(e.active());
        assertTrue(e.planningInstruction().contains("lookupPokemonLearnset")); assertTrue(e.verifiedAnswer().contains("未取得可核验的学习途径")); assertFalse(e.overviewReady("请介绍杰尼龟培养搭配以及学习途径"));
    }
}
