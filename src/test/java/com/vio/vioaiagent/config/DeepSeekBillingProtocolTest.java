package com.vio.vioaiagent.config;

import com.sun.net.httpserver.HttpServer;
import com.vio.vioaiagent.observability.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekBillingProtocolTest {
    @SuppressWarnings("unchecked") private static Map<String,Object> cost(ModelUsageLedger ledger){return (Map<String,Object>)ledger.freeze().get("cost");}
    private static String usage(boolean malformed) {
        return "\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":100,\"total_tokens\":1100,\"prompt_cache_hit_tokens\":400,\"prompt_cache_miss_tokens\":"
                +(malformed?"601":"600")+",\"completion_tokens_details\":{\"reasoning_tokens\":30}}";
    }
    @Test void realHttpAndSseDecodersPreserveDeepSeekCacheAndReasoningPartitionsWithoutAffectingTheReply() throws Exception {
        for(boolean stream:List.of(false,true))for(boolean malformed:List.of(false,true)) {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/chat/completions",exchange->{
                exchange.getRequestBody().readAllBytes();String data;
                if(stream)data="data: {\"id\":\"fixture\",\"model\":\"fixture-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"}}]}\n\n"
                        +"data: {\"id\":\"fixture\",\"model\":\"fixture-model\",\"choices\":[],"+usage(malformed)+"}\n\n";
                else data="{\"id\":\"fixture\",\"model\":\"fixture-model\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"你好\"}}],"+usage(malformed)+"}";
                byte[] bytes=data.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",stream?"text/event-stream":"application/json");
                exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
            });server.start();
            try {
                var prices=ModelPriceCatalog.load(Path.of(getClass().getResource("/pricing/fixture-prices.json").toURI()).toString(),"offline-test","deepseek");
                var ledger=new ModelUsageLedger(null,prices);
                var model=new ObservedChatModel(DeepSeekModelConfiguration.build("offline-test-only","fixture-model","http://127.0.0.1:"+server.getAddress().getPort()),ledger);
                if(stream) {
                    var chunks=model.stream(new Prompt("fixture")).collectList().block(Duration.ofSeconds(10));assertNotNull(chunks);
                    assertTrue(chunks.stream().anyMatch(r->r.getResult()!=null && "你好".equals(r.getResult().getOutput().getText())));
                } else assertEquals("你好",model.call(new Prompt("fixture")).getResult().getOutput().getText());
                assertEquals(malformed?"UNKNOWN":"ESTIMATED",cost(ledger).get("status"),"stream="+stream+", malformed="+malformed+", snapshot="+ledger.snapshot());
                if(!malformed)assertEquals("0.00094",cost(ledger).get("amount"));
                assertEquals(1100L,((Map<?,?>)ledger.snapshot().get("totals")).get("totalTokens"));
            } finally {server.stop(0);}
        }
    }
}
