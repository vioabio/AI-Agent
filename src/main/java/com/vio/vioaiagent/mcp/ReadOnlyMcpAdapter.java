package com.vio.vioaiagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.tools.governance.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Public-data process peer, not a website account or an OIDC principal. */
public final class ReadOnlyMcpAdapter {
    private final ReadOnlyMcpCatalog catalog;
    private final ToolExecutionGateway gateway;
    private final ObjectMapper json = new ObjectMapper();
    private final Semaphore permits = new Semaphore(2);
    private final String peer = "mcp-public-" + UUID.randomUUID();

    public ReadOnlyMcpAdapter(ToolCallback[] rawCallbacks, ToolExecutionGateway gateway) {
        this.catalog = new ReadOnlyMcpCatalog(rawCallbacks); this.gateway = Objects.requireNonNull(gateway);
        for (var description : catalog.tools()) {
            ToolCallback callback = Arrays.stream(rawCallbacks).filter(t -> t.getToolDefinition().name().equals(description.name())).findFirst().orElseThrow();
            if (callback instanceof GovernedToolCallback) throw new IllegalArgumentException("MCP_RAW_CALLBACK_REQUIRED");
            gateway.register(ToolSchemaReferences.adapt(DamageToolSchema.adapt(callback)), ToolCapabilityCatalog.find(description.name()).orElseThrow());
        }
    }
    public ReadOnlyMcpCatalog catalog() { return catalog; }
    public Mono<McpSchema.CallToolResult> invoke(String tool, Map<String, Object> arguments) {
        return Mono.defer(() -> {
            if (!permits.tryAcquire()) return Mono.just(error("MCP_BUSY"));
            return Mono.fromCallable(() -> execute(tool, arguments)).subscribeOn(Schedulers.boundedElastic())
                    .doFinally(signal -> permits.release());
        });
    }
    private McpSchema.CallToolResult execute(String tool, Map<String, Object> arguments) {
        try {
            String input = json.writeValueAsString(arguments);
            catalog.preflight(tool, input);
            // Identity, call ID, roles and deadline policy never come from the JSON-RPC client.
            var context = new ToolExecutionContext(peer, "mcp-call-" + UUID.randomUUID(), Set.of("trainer"), UUID.randomUUID().toString(), null, null)
                    .withInputConstraint((name, body) -> catalog.preflight(name, body));
            var result = gateway.execute(tool, input, context);
            if (result.status() != ToolGovernanceResult.Status.EXECUTED && result.status() != ToolGovernanceResult.Status.DEDUPLICATED)
                return error(result.status() == ToolGovernanceResult.Status.REJECTED ? "MCP_TOOL_REJECTED" : "MCP_TOOL_FAILED");
            return new McpSchema.CallToolResult(catalog.boundedResult(result.output()), false);
        } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
        catch (IllegalArgumentException invalid) {
            String code = invalid.getMessage();
            return error(Set.of("MCP_TOOL_NOT_ALLOWED", "MCP_INPUT_TOO_LARGE", "MCP_INPUT_INVALID").contains(code == null ? "" : code) ? code : "MCP_INPUT_INVALID");
        } catch (Exception unavailable) { return error("MCP_TOOL_UNAVAILABLE"); }
    }
    private static McpSchema.CallToolResult error(String code) { return new McpSchema.CallToolResult(code, true); }
}
