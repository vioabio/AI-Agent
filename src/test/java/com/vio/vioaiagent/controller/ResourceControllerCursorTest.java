package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.domain.RulesetBinding;
import com.vio.vioaiagent.pokemon.domain.VersionedDataRelease;
import com.vio.vioaiagent.pokemon.repository.InMemoryPokemonFactRepository;
import com.vio.vioaiagent.pokemon.repository.InMemorySavedTeamRepository;
import com.vio.vioaiagent.pokemon.service.PokemonResourceService;
import com.vio.vioaiagent.run.Conversation;
import com.vio.vioaiagent.run.InMemoryConversationRepository;
import com.vio.vioaiagent.run.InMemoryFavoriteRepository;
import com.vio.vioaiagent.run.InvalidResourceCursorException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceControllerCursorTest {
    @Test
    void cursorPagesAreBoundToOwnerAndDoNotRepeatRows() {
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        Instant now = Instant.now();
        conversations.save(new Conversation("c1", "user-a", "one", now, now));
        conversations.save(new Conversation("c2", "user-a", "two", now.plusSeconds(1), now.plusSeconds(1)));
        conversations.save(new Conversation("c3", "user-a", "three", now.plusSeconds(2), now.plusSeconds(2)));
        ResourceController controller = new ResourceController(conversations, pokemonService(),
                new InMemoryFavoriteRepository(), SecurityProperties.defaults());

        ResourceController.ResourcePage<Conversation> first = controller
                .listConversations(2, null, "user-a").getBody().getData();
        assertEquals(2, first.items().size());
        assertNotNull(first.nextCursor());

        ResourceController.ResourcePage<Conversation> second = controller
                .listConversations(2, first.nextCursor(), "user-a").getBody().getData();
        assertEquals(List.of("c1"), second.items().stream().map(Conversation::id).toList());
        assertNull(second.nextCursor());
        assertThrows(InvalidResourceCursorException.class,
                () -> controller.listConversations(2, first.nextCursor(), "user-b"));
    }

    private PokemonResourceService pokemonService() {
        PokemonFact fact = new PokemonFact(101, 1, 25, "pikachu", "皮卡丘", "default", "皮卡丘",
                "sv-data-v1", "pokemon-scarlet-violet", "https://example.invalid/pokemon", "sha256:fixture",
                "REVIEWED", "ELECTRIC", null, 35, 55, 40, 50, 50, 90);
        return new PokemonResourceService(new InMemoryPokemonFactRepository(List.of(fact),
                List.of(new VersionedDataRelease("sv-data-v1", "pokemon-scarlet-violet",
                        "https://example.invalid/release", "sha256:fixture", "REVIEWED")),
                List.of(new RulesetBinding("sv-ou-v1", "pokemon-scarlet-violet", "OU", true))),
                new InMemorySavedTeamRepository());
    }
}
