package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.domain.RulesetBinding;
import com.vio.vioaiagent.pokemon.domain.VersionedDataRelease;
import com.vio.vioaiagent.pokemon.repository.InMemoryPokemonFactRepository;
import com.vio.vioaiagent.pokemon.repository.InMemorySavedTeamRepository;
import com.vio.vioaiagent.pokemon.service.PokemonResourceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PokemonResourceControllerTest {
    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void authenticatedPrincipalOverridesForgedUserHeader() {
        PokemonFact fact = new PokemonFact(101, 1, 25, "pikachu", "皮卡丘", "default", "皮卡丘",
                "sv-data-v1", "pokemon-scarlet-violet", "https://example.invalid/pokemon", "sha256:fixture",
                "REVIEWED", "ELECTRIC", null, 35, 55, 40, 50, 50, 90);
        PokemonResourceService service = new PokemonResourceService(
                new InMemoryPokemonFactRepository(List.of(fact),
                        List.of(new VersionedDataRelease("sv-data-v1", "pokemon-scarlet-violet",
                                "https://example.invalid/release", "sha256:fixture", "REVIEWED")),
                        List.of(new RulesetBinding("sv-ou-v1", "pokemon-scarlet-violet", "OU", true))),
                new InMemorySavedTeamRepository());
        PokemonResourceController controller = new PokemonResourceController(service);
        RequestContext.setPrincipalId("principal-a");
        PokemonResourceController.CreateTeamBody body = new PokemonResourceController.CreateTeamBody();
        body.name = "队伍"; body.rulesetId = "sv-ou-v1"; body.dataReleaseId = "sv-data-v1";
        PokemonResourceController.MemberBody member = new PokemonResourceController.MemberBody();
        member.formId = 101; member.slot = 1; body.members = List.of(member);

        controller.createTeam(body, "principal-b");
        assertEquals(1, controller.teams(50, "principal-b").getBody().getData().size());
    }

    @Test
    void securityEnabledRequiresContextIdentity() {
        PokemonResourceController controller = new PokemonResourceController(null,
                new com.vio.vioaiagent.config.SecurityProperties(true, java.util.Map.of(), List.of(), List.of()));
        assertThrows(RuntimeException.class, () -> controller.teams(50, "forged"));
    }
}
