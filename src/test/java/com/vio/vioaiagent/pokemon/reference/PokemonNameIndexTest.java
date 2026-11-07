package com.vio.vioaiagent.pokemon.reference;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PokemonNameIndexTest {
    @Test void bundledVerifiedIndexSupportsNamesPrefixesAndNumbersWithoutNetwork() {
        assertEquals(1025, PokemonNameIndex.all().size());
        for (String query : new String[]{"杰尼龟", "傑尼龜", "SQUIRTLE", "杰尼", "squir", "007"})
            assertEquals(7, PokemonNameIndex.search(query, 10).getFirst().nationalNo(), query);
        assertEquals("雪妖女", PokemonNameIndex.search("froslass", 10).getFirst().nameZh());
        assertTrue(PokemonNameIndex.search("not-a-pokemon", 10).isEmpty());
        assertEquals(2, PokemonNameIndex.search("", 2).size());
        assertThrows(IllegalArgumentException.class, () -> PokemonNameIndex.search("a".repeat(81), 10));
    }
    @Test void controllerExposesAnIdentityIndexNotBattleFacts() {
        var result = new PokemonReferenceController(null).names("雪妖", 10);
        assertEquals(478, result.getFirst().nationalNo());
        assertEquals("froslass", result.getFirst().slug());
    }
}
