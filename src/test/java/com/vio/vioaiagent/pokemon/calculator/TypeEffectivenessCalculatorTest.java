package com.vio.vioaiagent.pokemon.calculator;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TypeEffectivenessCalculatorTest {
    private static final Set<String> TYPES = Set.of("一般", "火", "水", "电", "草", "冰", "格斗", "毒", "地面", "飞行", "超能力", "虫", "岩石", "幽灵", "龙", "恶", "钢", "妖精");

    @Test
    void allSingleTypeCombinationsAreDeterministic() {
        TypeEffectivenessCalculator calculator = new TypeEffectivenessCalculator();
        for (String attack : TYPES) {
            for (String defend : TYPES) {
                double first = calculator.calculate(attack, defend);
                double second = calculator.calculate(attack, defend);
                assertEquals(first, second);
                assertTrue(first == 0d || first == .25d || first == .5d || first == 1d || first == 2d || first == 4d);
            }
        }
    }

    @Test
    void dualTypeAndImmunityFollowMultiplicationRules() {
        TypeEffectivenessCalculator calculator = new TypeEffectivenessCalculator();
        assertEquals(4d, calculator.calculate("火", "草", "冰"));
        assertEquals(0d, calculator.calculate("电", "水", "地面"));
    }

    @Test
    void unknownTypeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new TypeEffectivenessCalculator().calculate("光", "水"));
    }
}
