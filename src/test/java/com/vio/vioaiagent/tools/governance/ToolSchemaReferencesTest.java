package com.vio.vioaiagent.tools.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.team.TeamWorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.chat.model.ToolContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolSchemaReferencesTest {
    @Test void actualPreviewSchemaExpandsBothStatObjectsWithoutDroppingRequiredFields() throws Exception {
        var callback=Arrays.stream(ToolCallbacks.from(new TeamWorkflowService(null,null,null,null)))
                .filter(tool->tool.getToolDefinition().name().equals("previewTeamChanges")).findFirst().orElseThrow();
        var mapper=new ObjectMapper();
        var original=mapper.readTree(callback.getToolDefinition().inputSchema());
        var schema=mapper.readTree(ToolSchemaReferences.adapt(callback).getToolDefinition().inputSchema());
        assertFalse(schema.toString().contains("\"$ref\""));
        assertFalse(schema.toString().contains("\"$defs\""));
        assertEquals(original.path("required"),schema.path("required"));
        var change=schema.path("properties").path("changes").path("items");
        assertTrue(change.path("required").toString().contains("slot"));
        for(String field:List.of("evs","ivs")) {
            var stats=change.path("properties").path(field).path("properties");
            assertEquals(6,stats.size());
            for(String stat:List.of("hp","atk","def","spa","spd","spe")) assertEquals("integer",stats.path(stat).path("type").asText());
        }
    }

    @Test void malformedOrRecursiveReferencesFailClosed() {
        for(String input:List.of("{\"$ref\":\"#/$defs/Missing\"}","{\"$ref\":\"https://example.com/schema\"}",
                "{\"$defs\":{\"A\":{\"$ref\":\"#/$defs/A\"}},\"$ref\":\"#/$defs/A\"}")) {
            var callback=mock(ToolCallback.class);
            when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name("previewTeamChanges").description("preview").inputSchema(input).build());
            assertThrows(IllegalStateException.class,()->ToolSchemaReferences.adapt(callback));
            verify(callback,never()).call(anyString());
        }
    }

    @Test void adapterPreservesMetadataInputAndExecutionContext() {
        var source=Arrays.stream(ToolCallbacks.from(new TeamWorkflowService(null,null,null,null)))
                .filter(tool->tool.getToolDefinition().name().equals("previewTeamChanges")).findFirst().orElseThrow();
        var delegate=mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(source.getToolDefinition());
        when(delegate.getToolMetadata()).thenReturn(source.getToolMetadata());
        var context=new ToolContext(Map.of("owner","test"));
        when(delegate.call("input",context)).thenReturn("result");
        var adapted=ToolSchemaReferences.adapt(delegate);
        assertSame(source.getToolMetadata(),adapted.getToolMetadata());
        assertEquals("result",adapted.call("input",context));
        verify(delegate).call("input",context);
        var other=mockOtherTool();
        assertSame(other,ToolSchemaReferences.adapt(other));
    }

    private ToolCallback mockOtherTool() {
        var callback=mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name("other").description("other").inputSchema("{}").build());
        return callback;
    }

    @Test void bothReadOnlyBuildCheckSchemasAlsoHaveNoDanglingReferences() throws Exception {
        var mapper=new ObjectMapper();
        var callbacks=ToolCallbacks.from(
                new com.vio.vioaiagent.pokemon.reference.PokemonBuildCheckController.Tools(null,null),
                new com.vio.vioaiagent.pokemon.reference.PokemonTeamBuildCheckController.Tools(null,null));
        assertEquals(2,callbacks.length);
        for(var callback:callbacks) {
            var original=mapper.readTree(callback.getToolDefinition().inputSchema());
            var adapted=ToolSchemaReferences.adapt(callback);
            var schema=mapper.readTree(adapted.getToolDefinition().inputSchema());
            assertFalse(schema.toString().contains("\"$ref\""));
            assertFalse(schema.toString().contains("\"$defs\""));
            assertEquals(original.path("required"),schema.path("required"));
            assertTrue(schema.path("properties").path("request").path("required").toString().contains("dataReleaseId"));
        }
    }
}
