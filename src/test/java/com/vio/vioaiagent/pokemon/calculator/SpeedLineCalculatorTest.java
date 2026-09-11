package com.vio.vioaiagent.pokemon.calculator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpeedLineCalculatorTest {
    @Test
    void sameInputProducesSameSpeed() {
        SpeedLineCalculator calculator = new SpeedLineCalculator();
        int first = calculator.calculate(100, 50, 31, 252, 1.1);
        int second = calculator.calculate(100, 50, 31, 252, 1.1);
        assertEquals(first, second);
    }

    @Test
    void invalidEffortValueIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SpeedLineCalculator().calculate(100, 50, 31, 253, 1.0));
    }
}
