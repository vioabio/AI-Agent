package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class BuildLearningNotesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    static Stream<JsonNode> vectors() throws Exception {
        var rows = JSON.readTree(Files.readString(Path.of("evaluation/campus/learning-review-v1/presentation-cases.json"))).path("cases");
        List<JsonNode> cases = new ArrayList<>(); rows.forEach(cases::add); return cases.stream();
    }
    private static BuildCheckFacts.Move move(List<PokemonLearnsetFacts.Entry> entries) {
        var raw = new PokeApiClient.Snapshot("controlled-public-fixture", PokeApiClient.digest("fixture"), Instant.parse("2026-10-27T00:00:00Z"), "https://pokeapi.co/api/v2/pokemon/7/");
        var learning = PokemonLearnsetFacts.create(7,"squirtle","surf",entries,raw);
        return new BuildCheckFacts.Move(1,"surf","surf","RECORDED",learning);
    }
    @ParameterizedTest @MethodSource("vectors")
    void sharedPresentationBoundaryVectors(JsonNode row) throws Exception {
        var entries = JSON.readerForListOf(PokemonLearnsetFacts.Entry.class).<List<PokemonLearnsetFacts.Entry>>readValue(row.path("entries"));
        Integer level = row.path("level").isNull() ? null : row.path("level").intValue();
        assertEquals(JSON.readerForListOf(String.class).readValue(row.path("expected")), BuildLearningNotes.describe(move(entries),level),row.path("id").asText());
    }
    @Test void boundsAndDeduplicatesDisplayedNotesWithoutDroppingTheUnverifiedRemainder() {
        var entries = new ArrayList<PokemonLearnsetFacts.Entry>();
        for(int i=1;i<=10;i++) entries.add(new PokemonLearnsetFacts.Entry("level-up",i,null));
        entries.add(new PokemonLearnsetFacts.Entry("level-up",1,1));
        var notes = BuildLearningNotes.describe(move(entries),5);
        assertEquals(7,notes.size());assertEquals("另有 4 条记录，详见来源；不据此推断其他路径。",notes.getLast());
    }
    @Test void failedOrMismatchedRowsNeverBecomeLearningAdvice() {
        var m = move(List.of(new PokemonLearnsetFacts.Entry("machine",0,null)));
        assertEquals(List.of(),BuildLearningNotes.describe(new BuildCheckFacts.Move(1,"surf","surf","REFERENCE_UNAVAILABLE",null),5));
        assertThrows(IllegalArgumentException.class,()->BuildLearningNotes.describe(new BuildCheckFacts.Move(1,"tackle","tackle","RECORDED",m.learning()),5));
    }
    @Test void malformedLearningDigestCannotProduceNotes() {
        var m=move(List.of(new PokemonLearnsetFacts.Entry("machine",0,null)));
        var l=m.learning();var tampered=new PokemonLearnsetFacts(l.schemaVersion(),l.pokemonId(),l.pokemonSlug(),l.moveSlug(),l.dataReleaseId(),l.gameReleaseId(),l.versionGroup(),l.status(),List.of(new PokemonLearnsetFacts.Entry("level-up",99,null)),l.sourceUrl(),l.sourceChecksum(),l.retrievedAt(),l.projectionChecksum());
        assertThrows(IllegalArgumentException.class,()->BuildLearningNotes.describe(new BuildCheckFacts.Move(1,"surf","surf","RECORDED",tampered),5));
    }
}
