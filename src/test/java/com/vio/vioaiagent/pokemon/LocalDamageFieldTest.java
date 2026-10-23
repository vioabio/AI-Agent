package com.vio.vioaiagent.pokemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.engine.LocalDamageEngine;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalDamageFieldTest {
    @Test void explicitNoConditionAliasesNormalizeWithoutInventingWeather() throws Exception {
        for(String value:new String[]{"","none","NONE","无","  none  "}) {
            var field=new LocalDamageEngine.Field("Singles",value,value);
            assertNull(field.weather());assertNull(field.terrain());
        }
        var field=new ObjectMapper().readValue("{\"gameType\":\"Singles\",\"weather\":\"none\",\"terrain\":\"none\"}",LocalDamageEngine.Field.class);
        assertNull(field.weather());assertNull(field.terrain());
        assertEquals("Rain",new LocalDamageEngine.Field("Doubles","Rain","Grassy").weather());
    }
    @Test void unknownConditionsStillFailClosed() {
        assertThrows(IllegalArgumentException.class,()->new LocalDamageEngine.Field("Singles","made-up-weather",null));
        assertThrows(IllegalArgumentException.class,()->new LocalDamageEngine.Field("Singles",null,"made-up-terrain"));
        assertThrows(IllegalArgumentException.class,()->new LocalDamageEngine.Field("Triples",null,null));
    }
    @Test void toolSchemaDoesNotRequireAbsentWeatherOrTerrain() throws Exception {
        var tools=new com.vio.vioaiagent.pokemon.engine.DamageEngineController.Tools(org.mockito.Mockito.mock(LocalDamageEngine.class),null);
        var callback=org.springframework.ai.support.ToolCallbacks.from(tools)[0];
        var field=new ObjectMapper().readTree(callback.getToolDefinition().inputSchema()).path("properties").path("request").path("properties").path("field");
        assertTrue(field.path("properties").has("weather"));
        assertFalse(field.path("required").toString().contains("weather"));
        assertFalse(field.path("required").toString().contains("terrain"));
    }
}
