package com.vio.vioaiagent.pokemon;

import com.vio.vioaiagent.pokemon.calculator.TeamLegalityChecker;
import com.vio.vioaiagent.pokemon.domain.RulesetBinding;
import com.vio.vioaiagent.pokemon.domain.VersionedDataRelease;
import com.vio.vioaiagent.pokemon.repository.InMemoryPokemonFactRepository;
import com.vio.vioaiagent.pokemon.service.BattleCalculationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BattleCalculationServiceTest {
    private final BattleCalculationService service = new BattleCalculationService();

    @Test
    void returnsVersionedTypeAndDamageResults() {
        BattleCalculationService.CalculationResult type = service.typeEffectiveness(
                "sv-data-v1", "火", "草", "钢");
        assertEquals("sv-data-v1", type.dataReleaseId());
        assertEquals(4.0d, type.value());

        BattleCalculationService.DamageResult damage = service.damage(
                "sv-data-v1", 50, 80, 120, 100, 1.0d);
        assertEquals("damage-gen9-base-v1", damage.calculationVersion());
        assertEquals(37, damage.minDamage());
        assertEquals(44, damage.maxDamage());
    }

    @Test
    void rejectsMissingReleaseAndReportsLegality() {
        assertThrows(IllegalArgumentException.class,
                () -> service.speed("", 100, 50, 31, 252, 1.0d));
        BattleCalculationService.LegalityResult result = service.legality(
                "sv-data-v1", "sv-ou-v1",
                List.of(new TeamLegalityChecker.Member("pikachu", List.of("thunderbolt")),
                        new TeamLegalityChecker.Member("pikachu", List.of("volt-tackle"))));
        assertFalse(result.legal());
        assertEquals("team-legality-v1", result.calculationVersion());
    }

    @Test
    void productionBackedPathRequiresReviewedReleaseAndBoundRuleset() {
        InMemoryPokemonFactRepository repository = new InMemoryPokemonFactRepository(
                List.of(),
                List.of(new VersionedDataRelease(
                        "sv-data-v1", "sv9", "https://example.invalid/data",
                        "sha256:fixture", "REVIEWED")),
                List.of(new RulesetBinding("sv-ou-v1", "sv9", "Scarlet/Violet OU", true)));
        BattleCalculationService backed = new BattleCalculationService(
                new com.vio.vioaiagent.pokemon.calculator.TypeEffectivenessCalculator(),
                new com.vio.vioaiagent.pokemon.calculator.SpeedLineCalculator(),
                new com.vio.vioaiagent.pokemon.calculator.DamageCalculator(),
                new TeamLegalityChecker(), repository);

        assertEquals(4.0d, backed.typeEffectiveness("sv-data-v1", "火", "草", "钢").value());
        assertThrows(IllegalArgumentException.class,
                () -> backed.typeEffectiveness("missing-release", "火", "草"));
        assertThrows(IllegalArgumentException.class,
                () -> backed.legality("sv-data-v1", "missing-ruleset", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> backed.legality("sv-data-v1", "sv-other-v1", List.of()));
    }

    @Test
    void productionBackedPathRejectsUnreviewedRelease() {
        InMemoryPokemonFactRepository repository = new InMemoryPokemonFactRepository(
                List.of(),
                List.of(new VersionedDataRelease(
                        "draft-data-v1", "sv9", "https://example.invalid/data",
                        "sha256:fixture", "DRAFT")),
                List.of());
        BattleCalculationService backed = new BattleCalculationService(
                new com.vio.vioaiagent.pokemon.calculator.TypeEffectivenessCalculator(),
                new com.vio.vioaiagent.pokemon.calculator.SpeedLineCalculator(),
                new com.vio.vioaiagent.pokemon.calculator.DamageCalculator(),
                new TeamLegalityChecker(), repository);

        assertThrows(IllegalArgumentException.class,
                () -> backed.speed("draft-data-v1", 100, 50, 31, 252, 1.0d));
    }
}
