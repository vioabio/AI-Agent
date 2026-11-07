package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LocalBuildOptionsTest {
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper();
    final String release="pokeapi-reference-v1", game="pokemon-scarlet-violet";
    final String pokemon="""
      {"id":445,"name":"garchomp","abilities":[
        {"ability":{"name":"sand-veil"},"is_hidden":false},
        {"ability":{"name":"rough-skin"},"is_hidden":true}],
       "moves":[
        {"move":{"name":"earthquake"},"version_group_details":[{"version_group":{"name":"scarlet-violet"},"move_learn_method":{"name":"machine"},"level_learned_at":0}]},
        {"move":{"name":"ice-beam"},"version_group_details":[{"version_group":{"name":"red-blue"},"move_learn_method":{"name":"machine"},"level_learned_at":0}]},
        {"move":{"name":"protect"},"version_group_details":[{"version_group":{"name":"scarlet-violet"},"move_learn_method":{"name":"machine"},"level_learned_at":0}]}]}
      """;
    LocalBuildOptions options(String data) {
        return new LocalBuildOptions(mapper,new DefaultResourceLoader(),new PokeApiClient(mapper,directory,url->data));
    }
    @Test void abilitiesAreRestrictedToThePokemonWithChineseLabelsAndHiddenMarker() {
        var page=options(pokemon).search("ability",445L,release,game,"",0,50);
        assertEquals(2,page.total());
        var hidden=page.items().stream().filter(row->row.slug().equals("rough-skin")).findFirst().orElseThrow();
        assertTrue(hidden.hidden()); assertEquals("粗糙皮肤",hidden.nameZh());
        assertTrue(page.items().stream().noneMatch(row->row.slug().equals("torrent")));
        assertNotNull(page.pokemonChecksum());
    }
    @Test void movesRequireSvLearningRowsAndRetainNullableParameters() {
        var service=options(pokemon);
        var page=service.search("move",445L,release,game,"",0,50);
        assertEquals(2,page.total());
        assertTrue(page.items().stream().noneMatch(row->row.slug().equals("ice-beam")));
        var earthquake=service.search("move",445L,release,game,"地震",0,50).items().getFirst();
        assertEquals("earthquake",earthquake.slug()); assertEquals(100,earthquake.power());
        assertEquals("machine",earthquake.learning().getFirst().method());
        var protect=service.search("move",445L,release,game,"protect",0,50).items().getFirst();
        assertNull(protect.power()); assertNull(protect.accuracy());
    }
    @Test void itemsSearchAndPaginationAreBundledAndNeverNeedAnUpstreamRequest() {
        var service=new LocalBuildOptions(mapper,new DefaultResourceLoader(),new PokeApiClient(mapper,directory,url->{throw new AssertionError("Unexpected network");}));
        var first=service.search("item",null,release,game,"",0,2);
        var second=service.search("item",null,release,game,"",first.nextOffset(),2);
        assertEquals(2,first.items().size());assertNotEquals(first.items().getFirst(),second.items().getFirst());
        assertNull(first.pokemonChecksum());
        assertEquals("leftovers",service.search("item",null,release,game,"吃剩",0,50).items().getFirst().slug());
        assertTrue(service.search("item",null,release,game,"bicycle",0,50).items().isEmpty());
    }
    @Test void invalidRequestVersionsAndLimitsAreRejectedBeforeNetwork() {
        var calls=new AtomicInteger();
        var service=new LocalBuildOptions(mapper,new DefaultResourceLoader(),new PokeApiClient(mapper,directory,url->{calls.incrementAndGet();return pokemon;}));
        assertThrows(IllegalArgumentException.class,()->service.search("ability",445L,"other",game,"",0,50));
        assertThrows(IllegalArgumentException.class,()->service.search("move",445L,release,"other","",0,50));
        assertThrows(IllegalArgumentException.class,()->service.search("ability",null,release,game,"",0,50));
        assertThrows(IllegalArgumentException.class,()->service.search("item",null,release,game,"",0,101));
        assertEquals(0,calls.get());
    }
    @Test void wrongPokemonAndMalformedHiddenFlagsCannotProduceCandidates() {
        assertThrows(IllegalStateException.class,()->options(pokemon.replace("\"id\":445","\"id\":444")).search("ability",445L,release,game,"",0,50));
        assertThrows(IllegalStateException.class,()->options(pokemon.replace("\"is_hidden\":true","\"is_hidden\":\"true\"")).search("ability",445L,release,game,"",0,50));
    }
    @Test void controllerReturnsPagedDataAndBadRequestForUnsupportedVersions() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new PokemonBuildOptionsController(options(pokemon))).build();
        mvc.perform(get("/pokemon/reference/build-options").param("kind","ability").param("pokemonId","445").param("dataReleaseId",release).param("gameReleaseId",game))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/pokemon/reference/build-options").param("kind","item").param("dataReleaseId","wrong").param("gameReleaseId",game))
                .andExpect(status().isBadRequest());
    }
}
