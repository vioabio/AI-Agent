package com.vio.vioaiagent.pokemon;

import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.domain.PokemonAvailability;
import com.vio.vioaiagent.pokemon.domain.RulesetBinding;
import com.vio.vioaiagent.pokemon.domain.SavedTeam;
import com.vio.vioaiagent.pokemon.domain.VersionedDataRelease;
import com.vio.vioaiagent.pokemon.repository.InMemoryPokemonFactRepository;
import com.vio.vioaiagent.pokemon.repository.InMemorySavedTeamRepository;
import com.vio.vioaiagent.pokemon.service.PokemonResourceService;
import com.vio.vioaiagent.pokemon.service.PokemonResourceNotFoundException;
import com.vio.vioaiagent.pokemon.service.PokemonResourceConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PokemonResourceServiceTest {
    private PokemonResourceService service;

    @BeforeEach
    void setUp() {
        PokemonFact fact = new PokemonFact(101, 1, 25, "pikachu", "皮卡丘", "default", "皮卡丘",
                "sv-data-v1", "pokemon-scarlet-violet", "https://example.invalid/pokemon", "sha256:fixture",
                "REVIEWED", "ELECTRIC", null, 35, 55, 40, 50, 50, 90);
        InMemoryPokemonFactRepository facts = new InMemoryPokemonFactRepository(
                List.of(fact),
                List.of(new VersionedDataRelease("sv-data-v1", "pokemon-scarlet-violet",
                        "https://example.invalid/release", "sha256:fixture", "REVIEWED")),
                List.of(new RulesetBinding("sv-ou-v1", "pokemon-scarlet-violet", "OU", true)));
        service = new PokemonResourceService(facts, new InMemorySavedTeamRepository());
    }

    @Test
    void formLookupIsBoundToVersionAndRejectsMissingOrInvalidIds() {
        assertEquals("pikachu", service.findFactByFormId("sv-data-v1", 101).slug());
        assertThrows(IllegalArgumentException.class, () -> service.findFactByFormId("sv-data-v1", 0));
        assertThrows(PokemonResourceNotFoundException.class, () -> service.findFactByFormId("missing", 101));
        assertThrows(PokemonResourceNotFoundException.class, () -> service.findFactByFormId("sv-data-v1", 999));
    }

    @Test
    void factsCarryReleaseAndSourceLineage() {
        PokemonResourceService.FactQueryResult result = service.searchFacts("sv-data-v1", "pikachu", null, 50);
        assertEquals("pokemon-scarlet-violet", result.gameReleaseId());
        assertEquals("sha256:fixture", result.checksum());
        assertEquals(1, result.total());
        assertEquals(25, result.items().get(0).nationalNo());
    }

    @Test
    void teamIsScopedToOwnerAndUsesVersionedForm() {
        SavedTeam created = service.createTeam("user-a", new PokemonResourceService.CreateTeamCommand(
                "测试队", "sv-ou-v1", "sv-data-v1",
                List.of(new PokemonResourceService.MemberCommand(101, 1))));
        assertEquals(1, service.listTeams("user-a", 50).size());
        assertEquals(0, service.listTeams("user-b", 50).size());
        assertThrows(PokemonResourceNotFoundException.class, () -> service.findTeam("user-b", created.id()));
        service.deleteTeam("user-a", created.id());
        assertEquals(0, service.listTeams("user-a", 50).size());
    }

    @Test
    void rejectsCrossGameRulesetAndUnknownForm() {
        assertThrows(PokemonResourceNotFoundException.class, () -> service.createTeam("user-a",
                new PokemonResourceService.CreateTeamCommand("bad", "missing", "sv-data-v1",
                        List.of(new PokemonResourceService.MemberCommand(101, 1)))));
        assertThrows(PokemonResourceNotFoundException.class, () -> service.createTeam("user-a",
                new PokemonResourceService.CreateTeamCommand("bad", "sv-ou-v1", "sv-data-v1",
                        List.of(new PokemonResourceService.MemberCommand(999, 1)))));
    }

    @Test
    void doesNotExposeUnreviewedCandidateRelease() {
        InMemoryPokemonFactRepository facts = new InMemoryPokemonFactRepository(
                List.of(),
                List.of(new VersionedDataRelease("candidate", "pokemon-scarlet-violet",
                        "https://example.invalid/release", "sha256:candidate", "FIXTURE_CANDIDATE")),
                List.of());
        PokemonResourceService candidateService = new PokemonResourceService(facts,
                new InMemorySavedTeamRepository());
        assertThrows(PokemonResourceConflictException.class,
                () -> candidateService.searchFacts("candidate", null, null, 50));
        assertThrows(PokemonResourceConflictException.class,
                () -> candidateService.findFactByFormId("candidate", 101));
    }

    @Test
    void rejectsUnavailableFormWhenAvailabilityFixtureIsPresent() {
        InMemoryPokemonFactRepository facts = new InMemoryPokemonFactRepository(
                List.of(new PokemonFact(101, 1, 25, "pikachu", "皮卡丘", "default", "皮卡丘",
                        "sv-data-v1", "pokemon-scarlet-violet", "https://example.invalid/pokemon",
                        "sha256:fixture", "REVIEWED", "ELECTRIC", null, 35, 55, 40, 50, 50, 90)),
                List.of(new VersionedDataRelease("sv-data-v1", "pokemon-scarlet-violet",
                        "https://example.invalid/release", "sha256:fixture", "REVIEWED")),
                List.of(new RulesetBinding("sv-ou-v1", "pokemon-scarlet-violet", "OU", true)));
        facts.setAvailability(List.of(new PokemonAvailability(101, "sv-data-v1", "sv-ou-v1", false)));
        PokemonResourceService unavailable = new PokemonResourceService(facts, new InMemorySavedTeamRepository());
        assertThrows(PokemonResourceConflictException.class, () -> unavailable.createTeam("user-a",
                new PokemonResourceService.CreateTeamCommand("bad", "sv-ou-v1", "sv-data-v1",
                        List.of(new PokemonResourceService.MemberCommand(101, 1)))));
    }

    @Test
    void rejectsInvalidTeamSizeAndSlotBeforeRepositoryWrite() {
        assertThrows(IllegalArgumentException.class, () -> service.createTeam("user-a",
                new PokemonResourceService.CreateTeamCommand("empty", "sv-ou-v1", "sv-data-v1", List.of())));

        List<PokemonResourceService.MemberCommand> seven = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(slot -> new PokemonResourceService.MemberCommand(101, slot))
                .toList();
        assertThrows(IllegalArgumentException.class, () -> service.createTeam("user-a",
                new PokemonResourceService.CreateTeamCommand("too-many", "sv-ou-v1", "sv-data-v1", seven)));

        assertThrows(IllegalArgumentException.class, () -> service.createTeam("user-a",
                new PokemonResourceService.CreateTeamCommand("bad-slot", "sv-ou-v1", "sv-data-v1",
                        List.of(new PokemonResourceService.MemberCommand(101, 0)))));
        assertThrows(IllegalArgumentException.class, () -> service.createTeam("user-a",
                new PokemonResourceService.CreateTeamCommand("bad-form", "sv-ou-v1", "sv-data-v1",
                        List.of(new PokemonResourceService.MemberCommand(0, 1)))));
    }

    @Test
    void updatesTeamOnlyForOwnerAndRevalidatesVersionedMembers() {
        SavedTeam created = service.createTeam("user-a", new PokemonResourceService.CreateTeamCommand(
                "旧名称", "sv-ou-v1", "sv-data-v1",
                List.of(new PokemonResourceService.MemberCommand(101, 1))));
        SavedTeam updated = service.updateTeam("user-a", created.id(), new PokemonResourceService.UpdateTeamCommand(
                "新名称", "sv-ou-v1", "sv-data-v1",
                List.of(new PokemonResourceService.MemberCommand(101, 1))));
        assertEquals(created.id(), updated.id());
        assertEquals("新名称", updated.name());
        assertThrows(PokemonResourceNotFoundException.class, () -> service.updateTeam("user-b", created.id(),
                new PokemonResourceService.UpdateTeamCommand("越权", "sv-ou-v1", "sv-data-v1",
                        List.of(new PokemonResourceService.MemberCommand(101, 1)))));
    }
}
