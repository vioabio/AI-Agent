package com.vio.vioaiagent.mcp;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.tools.governance.*;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** U5 protocol catalog and admission preflight; not a transport, authenticator or domain executor. */
public final class ReadOnlyMcpCatalog {
    public static final int MAX_INPUT_BYTES = 16_384, MAX_RESULT_BYTES = 65_536;
    private static final List<String> NAMES = List.of("lookupPokemonReference", "checkPokemonBuild", "calculateBattleDamage");
    private static final Set<String> FORBIDDEN = Set.of("userid", "runid", "roles", "traceid", "principal", "principalpolicy", "tokenbinding", "inputconstraint", "idempotencykey", "script", "command", "node");
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(MAX_INPUT_BYTES).maxNumberLength(100).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Map<String, String> schemas;

    public ReadOnlyMcpCatalog(org.springframework.ai.tool.ToolCallback[] registeredTools) {
        Objects.requireNonNull(registeredTools);
        var checked = new LinkedHashMap<String, String>();
        for (String name : NAMES) {
            var card = ToolCapabilityCatalog.find(name).orElseThrow();
            if (card.isWriteLike() || card.requiresApproval() || !card.idempotent()) throw new IllegalStateException("MCP_CATALOG_UNSAFE");
            var matches = Arrays.stream(registeredTools).filter(t -> t.getToolDefinition().name().equals(name)).toList();
            if (matches.size() != 1) throw new IllegalStateException("MCP_CATALOG_UNAVAILABLE");
            // Capability cards carry risk/roles; actual generated callback definitions carry the input schema.
            String schema = ToolSchemaReferences.adapt(DamageToolSchema.adapt(matches.getFirst())).getToolDefinition().inputSchema();
            try { if (!json.readTree(schema).path("properties").isObject()) throw new IllegalArgumentException(); }
            catch (Exception invalid) { throw new IllegalStateException("MCP_CATALOG_INVALID_SCHEMA"); }
            new McpSchema.Tool(name, "只读公共宝可梦参考或计算；不代表赛事合法性，不读取私有队伍。", schema);
            checked.put(name, schema);
        }
        schemas = Map.copyOf(checked);
    }

    public String protocolVersion() { return McpSchema.LATEST_PROTOCOL_VERSION; }
    public List<McpSchema.Tool> tools() {
        // Reparse so a client changing SDK schema maps cannot alter our admission policy.
        return NAMES.stream().map(name -> new McpSchema.Tool(name, "只读公共宝可梦参考或计算；不代表赛事合法性，不读取私有队伍。", schemas.get(name))).toList();
    }

    /** Only an admission check. The adapter must still invoke the existing gateway's full schema/policy validation. */
    public JsonNode preflight(String toolName, String input) {
        if (toolName == null || !schemas.containsKey(toolName)) throw new IllegalArgumentException("MCP_TOOL_NOT_ALLOWED");
        if (input == null || input.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) throw new IllegalArgumentException("MCP_INPUT_TOO_LARGE");
        try {
            var root = json.readTree(input);
            var schema = json.readTree(schemas.get(toolName));
            if (root == null || !root.isObject()) throw new IllegalArgumentException();
            for (var fields = root.fieldNames(); fields.hasNext();) if (!schema.path("properties").has(fields.next())) throw new IllegalArgumentException();
            for (var required : schema.path("required")) if (!root.hasNonNull(required.asText())) throw new IllegalArgumentException();
            rejectAuthority(root);
            var version = toolName.equals("lookupPokemonReference") ? root.path("dataReleaseId") : root.path("request").path("dataReleaseId");
            if (!version.isTextual() || !version.asText().equals("pokeapi-reference-v1")) throw new IllegalArgumentException();
            return root;
        } catch (Exception invalid) { throw new IllegalArgumentException("MCP_INPUT_INVALID"); }
    }
    private static void rejectAuthority(JsonNode value) {
        if (value.isObject()) value.properties().forEach(field -> {
            String key = field.getKey().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
            if (FORBIDDEN.contains(key)) throw new IllegalArgumentException();
            rejectAuthority(field.getValue());
        });
        else if (value.isArray()) value.forEach(ReadOnlyMcpCatalog::rejectAuthority);
    }
    public String boundedResult(String output) {
        if (output == null || output.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) throw new IllegalStateException("MCP_RESULT_TOO_LARGE");
        return output;
    }
}
