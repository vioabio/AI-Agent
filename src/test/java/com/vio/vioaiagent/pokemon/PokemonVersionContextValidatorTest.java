package com.vio.vioaiagent.pokemon;

import com.vio.vioaiagent.pokemon.service.PokemonVersionContextValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** N4 版本上下文入口归一化与跨字段一致性测试。 */
class PokemonVersionContextValidatorTest {
    private final PokemonVersionContextValidator validator = new PokemonVersionContextValidator();

    @Test
    void registeredAliasesNormalizeToCanonicalIds() {
        PokemonVersionContextValidator.VersionedContext result = validator.normalize(
                "sv-1", "zero-secret-treasure", "zh", "singles");

        assertEquals("pokemon-scarlet-violet", result.gameReleaseId());
        assertEquals("the-hidden-treasure-of-area-zero", result.dlcId());
        assertEquals("zh-CN", result.language());
        assertEquals("casual-singles", result.battleFormatId());
        assertEquals(4, result.keysPresent().size());
    }

    @Test
    void missingContextIsPreservedForClarificationFlow() {
        PokemonVersionContextValidator.VersionedContext result = validator.normalize(
                null, null, null, null);

        assertNull(result.gameReleaseId());
        assertNull(result.dlcId());
        assertNull(result.language());
        assertNull(result.battleFormatId());
        assertEquals(0, result.keysPresent().size());
    }

    @Test
    void unknownAliasesFailClosedWithoutEchoingInput() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.normalize("ranked-scarlet", null, "zh-CN", "singles"));

        assertTrue(error.getMessage().startsWith("VERSION_CONTEXT_INVALID:gameReleaseId_UNKNOWN"));
        // Error text is a stable code, not arbitrary user-supplied version text.
        org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains("ranked-scarlet"));
    }

    @Test
    void dlcMustBeExplicitlyBoundToSupportedGame() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.normalize(null, "zero-treasure", "zh-CN", "singles"));
        assertEquals("the-hidden-treasure-of-area-zero",
                validator.normalize("pokemon-scarlet-violet", "zero-treasure", "zh-CN", "doubles").dlcId());
    }

    @Test
    void completeContextRequiresGameLanguageAndBattleFormat() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.requireComplete("sv", null, "zh-CN", null));
        PokemonVersionContextValidator.VersionedContext result = validator.requireComplete(
                "sv", null, "zh-CN", "doubles");
        assertEquals("pokemon-scarlet-violet", result.gameReleaseId());
        assertEquals("casual-doubles", result.battleFormatId());
    }
}
