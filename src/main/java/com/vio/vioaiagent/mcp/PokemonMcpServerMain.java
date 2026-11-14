package com.vio.vioaiagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.*;
import com.vio.vioaiagent.pokemon.engine.*;
import com.vio.vioaiagent.security.*;
import com.vio.vioaiagent.tools.governance.*;
import org.springframework.ai.support.ToolCallbacks;
import java.io.*;
import java.nio.file.Path;
import java.util.concurrent.*;

/** Separate local process: no Spring web context, model, login, private teams or incoming HTTP port. */
public final class PokemonMcpServerMain {
    private PokemonMcpServerMain() { }
    public static void main(String[] args) {
        OutputStream protocol = System.out;
        System.setOut(System.err);
        configureLogging();
        try {
            Path root = Path.of(System.getenv().getOrDefault("VIO_MCP_PROJECT_ROOT", System.getProperty("user.dir"))).toAbsolutePath().normalize();
            Path cache = Path.of(System.getenv().getOrDefault("VIO_MCP_CACHE_DIRECTORY", root.resolve(".local-data/pokeapi").toString()));
            var json = new ObjectMapper();
            serve(root, new PokeApiClient(json, cache), System.in, protocol);
        } catch (Exception failure) { System.err.println("MCP_SERVER_STOPPED"); System.exit(1); }
    }
    public static void configureLogging() {
        var context = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        context.reset();
        // The pinned SDK logs malformed input contents at error/debug. Do not collect those payloads.
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.OFF);
    }
    public static void serve(Path root, PokeApiClient client, InputStream input, OutputStream output) throws Exception {
        var json = new ObjectMapper();
        var reference = new LocalPokeApiRepository(json, client);
        var learning = new LocalLearnsetReferences(json, client);
        var checks = new LocalBuildCheckService(json, reference, learning, client);
        var engine = new VerifiedLocalDamageEngine(reference, json, "node", root.resolve("tools/pokemon-engine/calculate-v2.cjs").toString());
        var callbacks = ToolCallbacks.from(new PokemonReferenceTools(reference), new PokemonBuildCheckController.Tools(checks, null), new DamageEngineController.Tools(engine, null));
        var hitl = new HitlManager();
        ToolAuditSink audit = entry -> System.err.println("MCP_AUDIT tool=" + entry.toolName() + " outcome=" + entry.outcome());
        var gateway = new ToolExecutionGateway(hitl, null, null, null, audit, null, null, false, new ToolExecutionProperties(2, 0, 500));
        var end = new CountDownLatch(1);
        var server = McpStdioServer.start(new ReadOnlyMcpAdapter(callbacks, gateway), input, output, end);
        Thread hook = new Thread(() -> { gateway.shutdown(); hitl.shutdown(); }, "mcp-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try { end.await(); }
        finally {
            try { server.closeGracefully().block(java.time.Duration.ofSeconds(2)); }
            finally {
                gateway.shutdown(); hitl.shutdown();
                try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException shuttingDown) { }
            }
        }
    }
}
