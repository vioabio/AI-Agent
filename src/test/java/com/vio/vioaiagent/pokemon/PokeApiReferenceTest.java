package com.vio.vioaiagent.pokemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PokeApiReferenceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private String pokemon() { return """
        {"id":7,"name":"squirtle","species":{"name":"squirtle"},"types":[{"type":{"name":"water"}}],
        "stats":[{"stat":{"name":"hp"},"base_stat":44},{"stat":{"name":"attack"},"base_stat":48},
        {"stat":{"name":"defense"},"base_stat":65},{"stat":{"name":"special-attack"},"base_stat":50},
        {"stat":{"name":"special-defense"},"base_stat":64},{"stat":{"name":"speed"},"base_stat":43}],
        "abilities":[{"ability":{"name":"torrent"}}],"moves":[{"move":{"name":"surf"},
        "version_group_details":[{"version_group":{"name":"scarlet-violet"}}]}]}
        """; }
    private String species() { return """
        {"id":7,"name":"squirtle","generation":{"name":"generation-i"},"names":[{"name":"杰尼龟","language":{"name":"zh-hans"}}]}
        """; }
    @Test void queriesChineseMapsSpeciesAndPersistsOfflineSnapshots() {
        AtomicInteger calls = new AtomicInteger();
        PokeApiClient client = new PokeApiClient(mapper,directory,url -> {
            calls.incrementAndGet();
            return url.endsWith(".csv") ? "pokemon_species_id,local_language_id,name,genus\n7,12,杰尼龟,小龟宝可梦\n"
                : url.contains("pokemon-species/") ? species() : pokemon();
        });
        var repository = new LocalPokeApiRepository(mapper,client);
        var result = repository.detail("杰尼龟",LocalPokeApiRepository.RELEASE);
        assertEquals(44,result.fact().hp()); assertEquals("水",result.fact().type1());
        assertEquals("杰尼龟",result.fact().speciesNameZh());
        assertEquals("LOCAL_REFERENCE",result.fact().releaseStatus());
        assertEquals(java.util.List.of("surf"),result.moves());
        assertEquals(3,calls.get());
        var offline = new LocalPokeApiRepository(mapper,new PokeApiClient(mapper,directory,url -> { throw new AssertionError("No network expected"); }));
        assertEquals(7,offline.detail("squirtle",LocalPokeApiRepository.RELEASE).fact().nationalNo());
        assertThrows(IllegalArgumentException.class,()->repository.detail("7","pokemon-scarlet-violet-data-v1"));
        assertTrue(repository.findDataRelease("candidate").isEmpty());
    }
    @Test void cacheCorruptionAndIdentityMismatchFailClosed() throws Exception {
        var client = new PokeApiClient(mapper,directory,url -> pokemon());
        client.read("pokemon","7");
        Path file = directory.resolve("pokemon-7.json");
        Files.writeString(file,Files.readString(file).replace("base_stat\\\":44","base_stat\\\":45"));
        assertThrows(IllegalStateException.class,()->client.read("pokemon","7"));
        var repository = new LocalPokeApiRepository(mapper,new PokeApiClient(mapper,directory.resolve("other"),url -> pokemon()));
        assertThrows(IllegalStateException.class,()->repository.detail("25",LocalPokeApiRepository.RELEASE));
        assertThrows(IllegalArgumentException.class,()->client.read("pokemon","../../secret"));
    }
    @Test void liveSmokeOnlyWhenExplicitlyRequested() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("vio.test.pokeapi.live"));
        var repository = new LocalPokeApiRepository(mapper,PokeApiClient.configured(mapper,directory,
                System.getProperty("vio.test.pokeapi.proxy", "")));
        assertEquals(7,repository.detail("squirtle",LocalPokeApiRepository.RELEASE).fact().nationalNo());
        assertEquals(7,repository.detail("杰尼龟",LocalPokeApiRepository.RELEASE).fact().nationalNo());
        assertEquals("杰尼龟",repository.detail("杰尼龟",LocalPokeApiRepository.RELEASE).fact().speciesNameZh());
    }
}
