package com.vio.vioaiagent.pokemon.reference;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PokeApiDeadlineTest {
    @Test void headersWithoutCompletedBodyCannotHangClient() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        CountDownLatch release=new CountDownLatch(1);
        server.createContext("/",exchange->{
            exchange.sendResponseHeaders(200,100);
            exchange.getResponseBody().write('{');exchange.getResponseBody().flush();
            try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            exchange.close();
        });server.start();
        try {
            var http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),()->{
                var error=assertThrows(IllegalStateException.class,()->PokeApiClient.fetch(http,"http://127.0.0.1:"+server.getAddress().getPort()+"/",200));
                assertTrue(error.getMessage().contains("超时"));
            });
        }finally{release.countDown();server.stop(0);}
    }
}
