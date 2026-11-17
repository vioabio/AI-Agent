package com.vio.vioaiagent.pokemon.guides;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GuideRerankHttpTest {
    @Test void rejectsHttpErrorsRedirectMalformedAndOversizeWithoutFollowingRedirect() throws Exception {
        for (String scenario : List.of("unavailable", "redirect", "malformed", "oversize")) {
            var redirects = new java.util.concurrent.atomic.AtomicInteger();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/rank", e -> {
                e.getRequestBody().readAllBytes();
                byte[] body = (scenario.equals("oversize") ? "x".repeat(2_000_001) : "invalid").getBytes();
                int status = scenario.equals("unavailable") ? 503 : scenario.equals("redirect") ? 302 : 200;
                e.getResponseHeaders().add("Location", "/redirect-target");
                e.sendResponseHeaders(status, body.length);
                try (var out = e.getResponseBody()) { out.write(body); } catch (java.io.IOException expectedDisconnect) { }
            });
            server.createContext("/redirect-target", e -> { redirects.incrementAndGet(); e.sendResponseHeaders(500, -1); e.close(); });
            server.start();
            try {
                var client = new GuideRerankConfiguration.HttpReranker("http://127.0.0.1:" + server.getAddress().getPort() + "/rank", "test-model", "test-only", 1000);
                var error = assertThrows(IllegalStateException.class, () -> client.rank("培养", List.of(new GuideReranker.Candidate("id", "text"))), scenario);
                assertEquals("RERANK_UNAVAILABLE", error.getMessage());
                assertEquals(0, redirects.get());
            } finally { server.stop(0); }
        }
    }
    @Test void slowResponseHonorsRequestTimeout() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rank", e -> {
            try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            e.close();
        });
        server.start();
        try {
            var client = new GuideRerankConfiguration.HttpReranker("http://127.0.0.1:" + server.getAddress().getPort() + "/rank", "test-model", "test-only", 100);
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> assertThrows(IllegalStateException.class, () -> client.rank("培养", List.of(new GuideReranker.Candidate("id", "text")))));
        } finally { release.countDown(); server.stop(0); }
    }
    @Test void realHttpMapsOnlyIndexAndScore() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/rank",e->{e.getRequestBody().readAllBytes();byte[] bytes="{\"results\":[{\"index\":0,\"relevance_score\":0.8}]}".getBytes();e.sendResponseHeaders(200,bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}});server.start();
        try{var client=new GuideRerankConfiguration.HttpReranker("http://127.0.0.1:"+server.getAddress().getPort()+"/rank","test-model","test-only",1000);
            assertEquals(List.of(new GuideReranker.Score(0,.8)),client.rank("培养",List.of(new GuideReranker.Candidate("id","canonical text"))));
        }finally{server.stop(0);}
    }
    @Test void rejectsInsecureRemoteAndCredentialsInUrl() {
        assertThrows(IllegalArgumentException.class,()->new GuideRerankConfiguration.HttpReranker("http://example.com/rank","m","k",1000));
        assertThrows(IllegalArgumentException.class,()->new GuideRerankConfiguration.HttpReranker("https://user:secret@example.com/rank","m","k",1000));
    }
    @Test void rejectsOverflowingAndDuplicateProviderIndicesBeforeNarrowing() throws Exception {
        for(String rows:List.of("[{\"index\":4294967296,\"relevance_score\":0.8}]","[{\"index\":-4294967296,\"relevance_score\":0.8}]","[{\"index\":0,\"relevance_score\":1e999}]")) {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/rank",e->{e.getRequestBody().readAllBytes();byte[] body=("{\"results\":"+rows+"}").getBytes();e.sendResponseHeaders(200,body.length);try(var out=e.getResponseBody()){out.write(body);}});server.start();
            try {
                var client=new GuideRerankConfiguration.HttpReranker("http://127.0.0.1:"+server.getAddress().getPort()+"/rank","test-model","test-only",1000);
                assertEquals("RERANK_UNAVAILABLE",assertThrows(IllegalStateException.class,()->client.rank("培养",List.of(new GuideReranker.Candidate("id","text")))).getMessage());
            }finally{server.stop(0);}
        }
    }
}
