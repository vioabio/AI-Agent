package com.vio.vioaiagent.pokemon.reference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PokeApiProxyTest {
    @TempDir Path directory;

    @Test void localPreferenceRoutesJavaRequestThroughSelectedProxy() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var received=new AtomicReference<String>();
        server.createContext("/",exchange->{
            received.set(exchange.getRequestURI().toString());
            byte[] body="{\"id\":7,\"name\":\"squirtle\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });server.start();
        try {
            Files.writeString(directory.resolve("pokeapi-network.properties"),"proxy=http://127.0.0.1:"+server.getAddress().getPort());
            var selector=PokeApiClient.configuredProxy(directory.resolve("cache"), "");
            var http=HttpClient.newBuilder().proxy(selector).version(HttpClient.Version.HTTP_1_1).build();
            assertTrue(PokeApiClient.fetch(http,"http://pokeapi.co/api/v2/pokemon/7/",1500).contains("squirtle"));
            assertEquals("http://pokeapi.co/api/v2/pokemon/7/",received.get());
            assertEquals(Proxy.NO_PROXY,PokeApiClient.configuredProxy(directory.resolve("cache"),"direct")
                    .select(URI.create("https://pokeapi.co/")).getFirst());
            var override=PokeApiClient.configuredProxy(directory.resolve("cache"),"http://127.0.0.1:9999");
            assertEquals(9999,((InetSocketAddress)override.select(URI.create("https://pokeapi.co/")).getFirst().address()).getPort());
        } finally { server.stop(0); }
    }

    @Test void invalidProxyCannotInjectCredentialsOrChangeDestinationPath() {
        for (String proxy:java.util.List.of("https://127.0.0.1:7897","http://user:secret@127.0.0.1:7897",
                "http://127.0.0.1:7897/path","http://127.0.0.1:7897?x=1","http://127.0.0.1")) {
            assertThrows(IllegalArgumentException.class,()->PokeApiClient.configuredProxy(directory.resolve("cache"),proxy));
        }
    }

    @Test void timeoutAndUpstreamFailureReturn503WithoutExposingCause() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new UnavailableController())
                .setControllerAdvice(new com.vio.vioaiagent.exception.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/reference-test"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(503))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-network-details"))));
    }

    @org.springframework.web.bind.annotation.RestController
    static class UnavailableController {
        @org.springframework.web.bind.annotation.GetMapping("/reference-test")
        public void read(){throw new PokeApiUnavailableException("PokéAPI 暂不可达，请检查资料代理设置或使用已缓存资料",new RuntimeException("private-network-details"));}
    }
}
