package com.vio.vioaiagent.tools;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PokeTypeToolTest {
    @Test
    void ghostChartIsIndependentFromIceChart() {
        PokeTypeTool tool = new PokeTypeTool();
        String ice = tool.getTypeChart("冰");
        String ghost = tool.getTypeChart("幽灵");
        assertTrue(ice.startsWith("冰 属性的攻击克制关系"));
        assertTrue(ghost.startsWith("幽灵 属性的攻击克制关系"));
        assertTrue(ghost.contains("克制(2x): 超能力 幽灵"));
        assertTrue(ghost.contains("免疫(0x): 一般"));
        assertNotEquals(ice, ghost);
        assertEquals(ice, tool.getTypeChart("冰"));
    }
}
