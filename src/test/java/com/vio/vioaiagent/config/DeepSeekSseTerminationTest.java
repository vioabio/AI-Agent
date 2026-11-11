package com.vio.vioaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.*;
import org.springframework.http.*;
import reactor.core.publisher.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekSseTerminationTest {
    private Flux<String> read(Flux<DataBuffer> body) {
        var message=new ReactiveHttpInputMessage(){
            public HttpHeaders getHeaders(){var headers=new HttpHeaders();headers.setContentType(MediaType.TEXT_EVENT_STREAM);return headers;}
            public Flux<DataBuffer> getBody(){return body;}
        };
        return new DeepSeekBillingSseReader(new ObjectMapper()).read(ResolvableType.forClass(String.class),message,Map.of());
    }
    private DataBuffer event(String text){return DefaultDataBufferFactory.sharedInstance.wrap(text.getBytes(StandardCharsets.UTF_8));}
    @Test void explicitDoneCompletesWithoutDrainOrError(){
        var values=read(Flux.just(event("data: hello\n\ndata: [DONE]\n\n"))).collectList().block();
        assertEquals(java.util.List.of("hello","[DONE]"),values);
    }
    @Test void transportErrorPreservesDecodedEventsBeforeDrainAndFailure(){
        var failure=new IllegalStateException("offline fixture");
        var values=read(Flux.concat(Flux.just(event("data: partial\n\n")),Flux.error(failure))).materialize().collectList().block();
        assertNotNull(values);assertEquals("partial",values.get(0).get());
        assertTrue(values.get(1).get().contains("incomplete-stream-drain"));
        assertSame(failure,values.getLast().getThrowable());
    }
    @Test void subscriberCancellationDoesNotBecomeAnIncompleteStreamError(){
        var values=read(Flux.concat(Flux.just(event("data: partial\n\n")),Flux.never())).take(1).collectList().block();
        assertEquals(java.util.List.of("partial"),values);
    }
}
