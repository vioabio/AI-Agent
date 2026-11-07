package com.vio.vioaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.ResolvableType;
import org.springframework.http.*;
import org.springframework.http.codec.*;
import reactor.core.publisher.*;
import java.util.*;

/** SDK 1.0 decodes SSE strings through its static mapper. Normalize only DeepSeek usage before that step. */
public final class DeepSeekBillingSseReader implements HttpMessageReader<String> {
    private final ServerSentEventHttpMessageReader delegate=new ServerSentEventHttpMessageReader();
    private final ObjectMapper mapper;
    DeepSeekBillingSseReader(ObjectMapper mapper){this.mapper=mapper;}
    @Override public List<MediaType> getReadableMediaTypes(){return List.of(MediaType.TEXT_EVENT_STREAM);}
    @Override public boolean canRead(ResolvableType type,MediaType mediaType) {
        return type.resolve()==String.class && mediaType!=null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(mediaType);
    }
    @Override public Flux<String> read(ResolvableType type,ReactiveHttpInputMessage message,Map<String,Object> hints) {
        return delegate.read(type,message,hints).cast(String.class).map(this::normalize);
    }
    @Override public Mono<String> readMono(ResolvableType type,ReactiveHttpInputMessage message,Map<String,Object> hints) {
        return read(type,message,hints).next();
    }
    private String normalize(String event) {
        if(!event.contains("prompt_cache_"))return event;
        try {
            var root=mapper.readTree(event);
            if(root instanceof ObjectNode object && root.path("usage").isObject()) {
                var usage=mapper.treeToValue(root.get("usage"),OpenAiApi.Usage.class);
                object.set("usage",mapper.valueToTree(usage));
                return mapper.writeValueAsString(root);
            }
            return event;
        } catch(Exception invalid) {throw new IllegalStateException("模型流式数据解码失败");}
    }
}
