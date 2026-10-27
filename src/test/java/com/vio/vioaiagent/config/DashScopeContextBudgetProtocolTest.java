package com.vio.vioaiagent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.*;
import com.sun.net.httpserver.HttpServer;
import com.vio.vioaiagent.observability.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.retry.support.RetryTemplate;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DashScopeContextBudgetProtocolTest {
    @Test void nativeDashScopeOptionsAcceptTheBudgetAndSendMaxTokensToHttp() throws Exception {
        var received=new java.util.concurrent.atomic.AtomicReference<JsonNode>();var mapper=new ObjectMapper();
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            requests.incrementAndGet();
            received.set(mapper.readTree(exchange.getRequestBody()));
            byte[] bytes="{\"request_id\":\"fixture\",\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"你好\"}}]},\"usage\":{\"input_tokens\":11,\"output_tokens\":3,\"total_tokens\":14}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();
        try {
            var api=DashScopeApi.builder().apiKey("offline-test-only").baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build();
            var delegate=DashScopeChatModel.builder().dashScopeApi(api).defaultOptions(DashScopeChatOptions.builder().withModel("fixture").withInternalToolExecutionEnabled(false).build())
                    .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
            var ledger=new ModelUsageLedger();var limits=new ContextBudgetProperties(1024,128,256,2,125);
            var observed=new ObservedChatModel(delegate,ledger,new ModelContextBudget(limits,List.of(),ledger));
            assertEquals("你好",observed.call(new Prompt("fixture",ToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build())).getResult().getOutput().getText());
            assertNotNull(received.get());assertEquals(128,received.get().path("parameters").path("max_tokens").asInt());
            assertNull(delegate.getDefaultOptions().getMaxTokens());
            var nativeLedger=new ModelUsageLedger(new TaskModelBudget(new TaskBudgetProperties(1,10000)));
            var nativeObserved=new ObservedChatModel(delegate,nativeLedger,new ModelContextBudget(limits,List.of(),nativeLedger));
            var nativeOptions=DashScopeChatOptions.builder().withModel("fixture").withMaxToken(500).withTemperature(0.2).withInternalToolExecutionEnabled(false).build();
            assertEquals("你好",nativeObserved.call(new Prompt("fixture native",nativeOptions)).getResult().getOutput().getText());
            assertEquals(128,received.get().path("parameters").path("max_tokens").asInt());
            assertEquals(0.2,received.get().path("parameters").path("temperature").asDouble());
            assertEquals(500,nativeOptions.getMaxTokens());assertNull(delegate.getDefaultOptions().getMaxTokens());
            assertThrows(TaskBudgetExceededException.class,()->nativeObserved.call(new Prompt("blocked native",nativeOptions)));
            assertEquals(2,requests.get());
        }finally{server.stop(0);}
    }
}
