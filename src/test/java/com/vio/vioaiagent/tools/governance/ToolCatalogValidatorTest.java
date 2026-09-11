package com.vio.vioaiagent.tools.governance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolCatalogValidatorTest {
    @Test
    void builtInCatalogHasExplicitPlayerAndOperatorPartitions() {
        ToolCatalogValidator.CatalogSnapshot snapshot = ToolCatalogValidator.validate();
        assertFalse(snapshot.playerTools().isEmpty());
        assertFalse(snapshot.operatorTools().isEmpty());
        assertTrue(snapshot.playerTools().values().stream().allMatch(card ->
                card.riskLevel() == ToolCapabilityCard.RiskLevel.LOW && !card.isWriteLike()));
    }
}
