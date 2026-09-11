package com.vio.vioaiagent.pokemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.config.PokemonFixtureInitializer;
import com.vio.vioaiagent.pokemon.repository.InMemoryPokemonFactRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证显式 fixture loader 只加载数据，不隐式改变候选发布状态。 */
class PokemonFixtureInitializerTest {
    @Test
    void loadsFixtureWhenExplicitlyInvoked() {
        InMemoryPokemonFactRepository repository = new InMemoryPokemonFactRepository();
        PokemonFixtureInitializer initializer = new PokemonFixtureInitializer(repository, new ObjectMapper(),
                new DefaultResourceLoader(), "file:data/v1.1-fixture/fixture.json");
        initializer.load();
        assertEquals(3, repository.search("pokemon-scarlet-violet-data-v1", null, null, 50).size());
        assertEquals("FIXTURE_CANDIDATE", repository.findDataRelease("pokemon-scarlet-violet-data-v1")
                .orElseThrow().status());
    }
}
