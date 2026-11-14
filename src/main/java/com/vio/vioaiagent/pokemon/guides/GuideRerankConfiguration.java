package com.vio.vioaiagent.pokemon.guides;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Explicit fixed endpoint, Jina-style request/results contract, no redirects or response error logging. */
@Configuration
@Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.local-guides.rerank.enabled",havingValue="true")
public class GuideRerankConfiguration {
    @Bean public GuideRerankPipeline guideRerankPipeline(
        @Value("${vio.local-guides.rerank.endpoint:}") String endpoint,
        @Value("${vio.local-guides.rerank.model:}") String model,
        @Value("${vio.local-guides.rerank.api-key:}") String key,
        @Value("${vio.local-guides.rerank.timeout-ms:3000}") int timeout,
        @Value("${vio.local-guides.rerank.candidate-k:12}") int candidateK,
        @Value("${vio.local-guides.rerank.answer-top-k:4}") int answerTopK,
        @Value("${vio.local-guides.rerank.max-input-tokens:12000}") int maxInputTokens) {
        return new GuideRerankPipeline(new HttpReranker(endpoint,model,key,timeout),candidateK,answerTopK,maxInputTokens);
    }
    static final class HttpReranker implements GuideReranker {
        private final URI endpoint;
        private final String model,key;
        private final int timeout;
        private final ObjectMapper json=new ObjectMapper();
        private final HttpClient http;
        HttpReranker(String url,String model,String key,int timeout) {
            try{endpoint=URI.create(url);}catch(Exception e){throw new IllegalArgumentException("Invalid rerank endpoint");}
            boolean local=Set.of("localhost","127.0.0.1","[::1]").contains(Objects.toString(endpoint.getHost(),""));
            if(endpoint.getHost()==null||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null||!("https".equals(endpoint.getScheme())||local&&"http".equals(endpoint.getScheme()))||model==null||model.isBlank()||key==null||key.isBlank()||timeout<1||timeout>30000)throw new IllegalArgumentException("Invalid rerank configuration");
            this.model=model;this.key=key;this.timeout=timeout;
            http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofMillis(timeout)).build();
        }
        public List<Score> rank(String query,List<Candidate> candidates) {
            if(query==null||query.length()>4000||candidates.isEmpty()||candidates.size()>50||candidates.stream().anyMatch(c->c.content()==null||c.content().length()>20000))throw new IllegalArgumentException("Invalid rerank input");
            try {
                var body=json.createObjectNode().put("model",model).put("query",query).put("top_n",candidates.size());
                body.set("documents",json.valueToTree(candidates.stream().map(Candidate::content).toList()));
                var req=HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(timeout)).header("Authorization","Bearer "+key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
                // A bounded subscriber rejects oversized bodies before buffering them all.
                var response=http.send(req,info->new LimitedBody());
                if(response.statusCode()!=200)throw new IllegalStateException("RERANK_HTTP_FAILED");
                var root=json.readTree(response.body());var rows=root.path("results");
                if(!rows.isArray()||rows.size()!=candidates.size())throw new IllegalStateException("RERANK_INVALID_RESPONSE");
                var result=new ArrayList<Score>();
                var seen=new HashSet<Integer>();
                for(var row:rows){
                    var index=row.path("index");double score=row.path("relevance_score").asDouble(Double.NaN);
                    if(!index.isIntegralNumber()||!index.canConvertToInt()||index.asInt()<0||index.asInt()>=candidates.size()||!seen.add(index.asInt())||!row.path("relevance_score").isNumber()||!Double.isFinite(score))throw new IllegalStateException("RERANK_INVALID_RESPONSE");
                    result.add(new Score(index.asInt(),score));
                }
                return List.copyOf(result);
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("Rerank interrupted");}
            catch(java.util.concurrent.CancellationException e){throw e;}
            catch(Exception e){throw new IllegalStateException("RERANK_UNAVAILABLE");}
        }
        static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
            final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
            java.util.concurrent.Flow.Subscription subscription;long size;
            public java.util.concurrent.CompletionStage<byte[]> getBody(){return delegate.getBody();}
            public void onSubscribe(java.util.concurrent.Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
            public void onNext(List<java.nio.ByteBuffer> items){for(var b:items)size+=b.remaining();if(size>2_000_000){subscription.cancel();delegate.onError(new IllegalStateException("RERANK_RESPONSE_TOO_LARGE"));}else delegate.onNext(items);}
            public void onError(Throwable t){delegate.onError(t);}
            public void onComplete(){delegate.onComplete();}
        }
    }
}
