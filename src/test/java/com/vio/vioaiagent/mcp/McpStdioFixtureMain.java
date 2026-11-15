package com.vio.vioaiagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.PokeApiClient;
import java.nio.file.Path;

/** Test-only offline upstream, using the real repository, gateway, SDK server and damage engine. */
public final class McpStdioFixtureMain {
    public static void main(String[] args) throws Exception {
        var protocol = System.out; System.setOut(System.err); PokemonMcpServerMain.configureLogging();
        PokemonMcpServerMain.serve(Path.of(System.getProperty("user.dir")), client(Path.of(args[0])), System.in, protocol);
    }
    static PokeApiClient client(Path cache) {
        return new PokeApiClient(new ObjectMapper(), cache, url -> {
            if(url.endsWith("pokemon_species_names.csv"))return "pokemon_species_id,local_language_id,name,genus\n7,12,杰尼龟,小龟宝可梦\n";
            var slow = java.util.regex.Pattern.compile("/(slow(?:-\\d{1,3})?)/$").matcher(url);
            if (slow.find()) {
                String marker = slow.group(1);
                try {
                    java.nio.file.Files.writeString(cache.resolve(marker + "-started"), "started");
                    Thread.sleep(20000);
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    try { java.nio.file.Files.writeString(cache.resolve(marker + "-stopped"), "stopped"); }
                    catch (java.io.IOException failure) { throw new IllegalStateException("FIXTURE_MARKER_FAILED"); }
                    throw new IllegalStateException("FIXTURE_INTERRUPTED");
                } catch (java.io.IOException failure) { throw new IllegalStateException("FIXTURE_MARKER_FAILED"); }
            }
            return url.contains("pokemon-species/") ? """
            {"id":7,"name":"squirtle","generation":{"name":"generation-i"},"names":[{"name":"杰尼龟","language":{"name":"zh-hans"}}]}
            """ : """
            {"id":7,"name":"squirtle","species":{"name":"squirtle"},"types":[{"type":{"name":"water"}}],
             "stats":[{"stat":{"name":"hp"},"base_stat":44},{"stat":{"name":"attack"},"base_stat":48},
             {"stat":{"name":"defense"},"base_stat":65},{"stat":{"name":"special-attack"},"base_stat":50},
             {"stat":{"name":"special-defense"},"base_stat":64},{"stat":{"name":"speed"},"base_stat":43}],
             "abilities":[{"ability":{"name":"torrent"}},{"ability":{"name":"rain-dish"}}],"moves":[]}
            """;
        });
    }
}
