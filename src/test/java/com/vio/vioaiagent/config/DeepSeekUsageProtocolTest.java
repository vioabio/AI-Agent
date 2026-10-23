package com.vio.vioaiagent.config;

import com.sun.net.httpserver.HttpServer;
import com.vio.vioaiagent.observability.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekUsageProtocolTest {
    @SuppressWarnings("unchecked") static Map<String,Object> tokens(ModelUsageLedger ledger){return (Map<String,Object>)ledger.freeze().get("totals");}
    @Test void actualHttpDecoderPreservesUsageAndMissingFieldsRemainUnknown() throws Exception {
        for(boolean missing:List.of(false,true)) {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/chat/completions",exchange->{
                exchange.getRequestBody().readAllBytes();
                String json="{\"id\":\"fixture\",\"model\":\"deepseek-fixture\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"你好\"}}],\"usage\":{\"prompt_tokens\":"+(missing?"null":"11")+",\"completion_tokens\":3,\"total_tokens\":"+(missing?"3":"14")+"}}";
                byte[] bytes=json.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
                try(var out=exchange.getResponseBody()){out.write(bytes);}
            });server.start();
            try {
                var ledger=new ModelUsageLedger();var model=new ObservedChatModel(DeepSeekModelConfiguration.build("offline-test-only","fixture","http://127.0.0.1:"+server.getAddress().getPort()),ledger);
                assertEquals("你好",model.call(new Prompt("fixture")).getResult().getOutput().getText());
                if(missing)assertNull(tokens(ledger).get("totalTokens"));else assertEquals(14L,tokens(ledger).get("totalTokens"));
            }finally{server.stop(0);}
        }
    }
    @Test void realSseDecoderCapturesFinalUsageWithOrWithoutAContentChoice() throws Exception {
        for(boolean usageOnly:List.of(false,true)) {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/chat/completions",exchange->{
                exchange.getRequestBody().readAllBytes();
                String first="data: {\"id\":\"fixture\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"你好\"}}]}\n\n";
                String choices=usageOnly?"[]":"[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]";
                String last="data: {\"id\":\"fixture\",\"model\":\"fixture\",\"choices\":"+choices+",\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3,\"total_tokens\":14}}\n\ndata: [DONE]\n\n";
                byte[] bytes=(first+last).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);
                try(var out=exchange.getResponseBody()){out.write(bytes);}
            });server.start();
            try {
                var ledger=new ModelUsageLedger();var model=new ObservedChatModel(DeepSeekModelConfiguration.build("offline-test-only","fixture","http://127.0.0.1:"+server.getAddress().getPort()),ledger);
                var chunks=model.stream(new Prompt("fixture")).collectList().block(Duration.ofSeconds(10));assertNotNull(chunks);
                assertEquals(1,ledger.freeze().get("modelCallCount"));assertEquals(14L,tokens(ledger).get("totalTokens"));
            }finally{server.stop(0);}
        }
    }
}
