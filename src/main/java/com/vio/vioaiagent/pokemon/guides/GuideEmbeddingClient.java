package com.vio.vioaiagent.pokemon.guides;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Fixed configured OpenAI-compatible embeddings endpoint; no redirects or provider errors/keys in logs. */
final class GuideEmbeddingClient {
    static final int MAX_BATCH_SIZE=8;
    private final GuideSemanticProperties properties;
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http;
    GuideEmbeddingClient(GuideSemanticProperties properties){this.properties=properties;this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(10,properties.timeoutSeconds()))).followRedirects(HttpClient.Redirect.NEVER).build();}
    boolean configured(){return properties.endpoint()!=null&&!properties.endpoint().isBlank()&&properties.apiKey()!=null&&!properties.apiKey().isBlank()&&properties.model()!=null&&!properties.model().isBlank();}
    List<double[]> embed(List<String> texts){
        if(!configured())throw new IllegalStateException("UNCONFIGURED");
        if(texts.isEmpty()||texts.size()>MAX_BATCH_SIZE||texts.stream().anyMatch(t->t==null||t.isBlank()||t.length()>4000))throw new IllegalArgumentException("Embedding批次无效");
        try{
            URI endpoint=URI.create(properties.endpoint());String host=endpoint.getHost();
            boolean local=Set.of("127.0.0.1","localhost","[::1]","::1").contains(Objects.toString(host,""));
            if(host==null||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null||!("https".equals(endpoint.getScheme())||local&&"http".equals(endpoint.getScheme())))throw new IllegalStateException("INVALID_ENDPOINT");
            var body=json.createObjectNode().put("model",properties.model()).put("encoding_format","float");body.set("input",json.valueToTree(texts));
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(properties.timeoutSeconds())).header("Authorization","Bearer "+properties.apiKey()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;try(var input=response.body()){if(response.statusCode()!=200)throw new IllegalStateException("PROVIDER_HTTP_"+response.statusCode());bytes=input.readNBytes(4_000_001);}
            if(bytes.length>4_000_000)throw new IllegalStateException("RESPONSE_TOO_LARGE");
            var root=json.readTree(bytes);if(root.hasNonNull("model")&&!properties.model().equals(root.path("model").asText()))throw new IllegalStateException("MODEL_MISMATCH");
            var data=root.path("data");if(!data.isArray()||data.size()!=texts.size())throw new IllegalStateException("INVALID_VECTOR_RESPONSE");
            double[][] ordered=new double[texts.size()][];
            for(var row:data){if(!row.path("index").isIntegralNumber())throw new IllegalStateException("INVALID_VECTOR_RESPONSE");int index=row.path("index").asInt(-1);
                if(index<0||index>=ordered.length||ordered[index]!=null||!row.path("embedding").isArray()||row.path("embedding").size()!=properties.dimensions())throw new IllegalStateException("DIMENSION_OR_INDEX_MISMATCH");
                double[] vector=new double[properties.dimensions()];for(int n=0;n<vector.length;n++){var value=row.path("embedding").get(n);if(!value.isNumber())throw new IllegalStateException("INVALID_VECTOR_RESPONSE");vector[n]=value.asDouble();}validate(vector,properties.dimensions());ordered[index]=vector;}
            return List.copyOf(Arrays.asList(ordered));
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("EMBEDDING_CANCELLED");}
        catch(HttpTimeoutException timeout){throw new IllegalStateException("EMBEDDING_TIMEOUT");}
        catch(java.net.ConnectException|java.net.UnknownHostException connection){throw new IllegalStateException("EMBEDDING_CONNECTION_FAILED");}
        catch(java.io.IOException transport){throw new IllegalStateException("EMBEDDING_IO_ERROR");}
        catch(IllegalStateException safe){throw safe;}catch(Exception failure){throw new IllegalStateException("EMBEDDING_UNAVAILABLE");}
    }
    static void validate(double[] vector,int dimensions){double norm=0;if(vector.length!=dimensions)throw new IllegalStateException("DIMENSION_MISMATCH");for(double v:vector){if(!Double.isFinite(v))throw new IllegalStateException("INVALID_VECTOR");norm+=v*v;}if(!Double.isFinite(norm)||norm<=0)throw new IllegalStateException("INVALID_VECTOR");}
}
