package com.vio.vioaiagent.config;

import com.sun.net.httpserver.HttpServer;
import com.vio.vioaiagent.observability.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekFailureProtocolTest {
    @Test void actualSpringHttpAndStreamingErrorAdaptersExposeOnlyStatusAndCategoryWithoutRetry() throws Exception {
        for(boolean streaming:new boolean[]{false,true})for(int status:new int[]{401,429,503}) {
            var requests=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/chat/completions",exchange->{
                requests.incrementAndGet();exchange.getRequestBody().readAllBytes();
                byte[] bytes="{\"error\":{\"message\":\"PRIVATE_PROVIDER_BODY\",\"code\":\"PRIVATE_CODE\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.getResponseHeaders().set("X-Private-Token","PRIVATE_HEADER");
                exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
            });server.start();
            try {
                var ledger=new ModelUsageLedger();
                var model=new ObservedChatModel(DeepSeekModelConfiguration.build("offline-test-only","fixture-model",
                        "http://127.0.0.1:"+server.getAddress().getPort()),ledger);
                assertThrows(RuntimeException.class,()->{
                    if(streaming)model.stream(new Prompt("PRIVATE_PROMPT")).collectList().block(Duration.ofSeconds(10));
                    else model.call(new Prompt("PRIVATE_PROMPT"));
                });
                var snapshot=ledger.freeze();var row=(Map<?,?>)((List<?>)snapshot.get("calls")).getFirst();
                var failure=(Map<?,?>)row.get("failure");
                assertEquals(status,failure.get("httpStatus"));
                assertEquals(status==401?"AUTHENTICATION_FAILED":status==429?"RATE_LIMITED":"UPSTREAM_SERVER_ERROR",failure.get("category"));
                assertEquals(1,requests.get());assertEquals("FAILED",row.get("outcome"));
                assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(snapshot).contains("PRIVATE"));
            } finally {server.stop(0);}
        }
    }
}
