package com.vio.vioaiagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** A second actual STDIO client implementation, distinct from the handwritten Node probe. */
class SdkMcpCompatibilityTest {
    @TempDir Path directory;
    @Test void pinnedOfficialSdkDiscoversAndCallsAllThreePublicTools() throws Exception {
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var parameters=ServerParameters.builder(executable).args(List.of("-cp",classpath,McpStdioFixtureMain.class.getName(),directory.toString())).build();
        var transport=new StdioClientTransport(parameters);
        transport.setStdErrorHandler(ignored->{});
        var client=McpClient.sync(transport).requestTimeout(Duration.ofSeconds(12)).build();
        try {
            assertEquals("2024-11-05",client.initialize().protocolVersion());
            assertEquals(List.of("calculateBattleDamage","checkPokemonBuild","lookupPokemonReference"),client.listTools().tools().stream().map(McpSchema.Tool::name).sorted().toList());
            var json=new ObjectMapper();
            var fact=client.callTool(new McpSchema.CallToolRequest("lookupPokemonReference",Map.of("query","7","dataReleaseId","pokeapi-reference-v1")));
            assertFalse(fact.isError());assertEquals(44,json.readTree(((McpSchema.TextContent)fact.content().getFirst()).text()).path("fact").path("hp").asInt());
            var build=client.callTool(new McpSchema.CallToolRequest("checkPokemonBuild",Map.of("request",Map.of("pokemonId",7,"dataReleaseId","pokeapi-reference-v1","gameReleaseId","pokemon-scarlet-violet"))));
            assertFalse(build.isError());assertEquals("INCOMPLETE",json.readTree(((McpSchema.TextContent)build.content().getFirst()).text()).path("status").asText());
            var damage=client.callTool(new McpSchema.CallToolRequest("calculateBattleDamage",Map.of("request",Map.of("generation",9,"dataReleaseId","pokeapi-reference-v1","attacker",Map.of("species","squirtle"),"defender",Map.of("species","squirtle"),"move","tackle","field",Map.of("gameType","Singles")))));
            assertFalse(damage.isError());assertTrue(json.readTree(((McpSchema.TextContent)damage.content().getFirst()).text()).path("range").path("maximum").asInt()>0);
            assertTrue(client.callTool(new McpSchema.CallToolRequest("lookupPokemonReference",Map.of("query","7","dataReleaseId","wrong"))).isError());
            assertNotNull(client.ping());
        }finally{assertTrue(client.closeGracefully(),"SDK client did not close gracefully");}
    }
}
