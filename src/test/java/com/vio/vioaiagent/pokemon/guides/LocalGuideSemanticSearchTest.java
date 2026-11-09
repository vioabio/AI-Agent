package com.vio.vioaiagent.pokemon.guides;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vio.vioaiagent.rag.retrieval.KnowledgeRetrievalQuery;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LocalGuideSemanticSearchTest {
    @TempDir Path dir;
    final ObjectMapper json=new ObjectMapper();
    final AtomicInteger calls=new AtomicInteger();
    HttpServer server;
    String endpoint;
    volatile String mode="OK";
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/embeddings",exchange->{
            calls.incrementAndGet();var request=json.readTree(exchange.getRequestBody());
            if(mode.equals("FAIL")){exchange.sendResponseHeaders(503,-1);exchange.close();return;}
            var root=json.createObjectNode().put("model",mode.equals("MODEL")?"other-model":request.path("model").asText());var data=root.putArray("data");
            for(int n=request.path("input").size()-1;n>=0;n--){String text=request.path("input").get(n).asText();var row=data.addObject().put("index",mode.equals("DUPLICATE")?0:n);var vector=row.putArray("embedding");
                if(mode.equals("ZERO")){vector.add(0).add(0);}else {vector.add(text.contains("香蕉")?0:1);if(!mode.equals("DIMENSION"))vector.add(text.contains("香蕉")?1:0);}}
            byte[] bytes=json.writeValueAsBytes(root);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}exchange.close();
        });server.start();endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/embeddings";
    }
    @AfterEach void stop(){server.stop(0);}
    GuideSemanticProperties properties(String model){return new GuideSemanticProperties(endpoint,"unit-test",model,2,dir.resolve("vectors.sqlite").toString(),2,.8);}
    LocalGuideSearch.Document doc(String id,String text){return new LocalGuideSearch.Document(id,"资料说明","pokemon-scarlet-violet","索引","VIO project","2026-10-01","",text);}
    SqliteGuideKnowledgeRetriever index(List<LocalGuideSearch.Document> docs){return new SqliteGuideKnowledgeRetriever(json,docs,dir.resolve("source.sqlite").toString());}
    KnowledgeRetrievalQuery query(String text){return new KnowledgeRetrievalQuery(text,"zh-CN","pokemon-scarlet-violet","casual-singles","casual-v1",5);}

    @Test void explicitBuildEnablesParaphraseRetrievalWithCanonicalCitationAndPersistentReuse(){
        var source=index(List.of(doc("apple","苹果培养资料。"),doc("banana","香蕉资料。")));var search=new LocalGuideSemanticSearch(properties("fixture-model"));
        assertTrue(source.retrieve(query("水果")).isEmpty());assertTrue(search.retrieve(query("水果"),source).isEmpty());assertEquals(0,calls.get());
        assertTrue(search.build(source).ready());assertEquals(1,calls.get());
        var hits=search.retrieve(query("水果"),source);assertEquals(1,hits.size());assertEquals("apple",hits.getFirst().view().documentId());assertEquals(0,hits.getFirst().lexicalRank());assertEquals(1,hits.getFirst().semanticRank());
        var hit=source.hit(hits.getFirst());assertTrue(GuideChunks.validCitation(hit.document(),hit.citation()));assertEquals(hit.citation(),source.locate(hit.citation().chunkId()).orElseThrow().citation());
        int before=calls.get();var reopened=new LocalGuideSemanticSearch(properties("fixture-model"));assertTrue(reopened.build(source).ready());assertEquals(before,calls.get());assertEquals("READY_REUSED",reopened.status(source).state());
    }
    @Test void scopesAndExpiryAreFilteredBeforeCallingTheQueryEmbedding(){
        var expired=new LocalGuideSearch.Document("expired","资料说明","pokemon-scarlet-violet","索引","VIO project","2026-10-01","","苹果过期资料。","zh-CN","casual-singles","casual-v1","2026-10-02");
        var source=index(List.of(expired));var search=new LocalGuideSemanticSearch(properties("fixture-model"));search.build(source);int before=calls.get();
        assertTrue(search.retrieve(query("水果"),source).isEmpty());
        assertTrue(search.retrieve(new KnowledgeRetrievalQuery("水果","en","pokemon-scarlet-violet","casual-singles","casual-v1",5),source).isEmpty());
        assertTrue(search.retrieve(new KnowledgeRetrievalQuery("水果","zh-CN","pokemon-sword-shield","casual-singles","casual-v1",5),source).isEmpty());
        assertTrue(search.retrieve(new KnowledgeRetrievalQuery("水果","zh-CN","pokemon-scarlet-violet","gen9ou","ou",5),source).isEmpty());assertEquals(before,calls.get());
    }
    @Test void modelAndSourceChangesCannotReuseOldVectors(){
        var source=index(List.of(doc("apple","苹果培养资料。")));var first=new LocalGuideSemanticSearch(properties("first"));assertTrue(first.build(source).ready());
        int before=calls.get();var other=new LocalGuideSemanticSearch(properties("second"));assertTrue(other.retrieve(query("水果"),source).isEmpty());assertEquals(before,calls.get());
        assertTrue(other.build(source).ready());assertEquals(before+1,calls.get());
        var replacement=index(List.of(doc("apple","苹果修订后的培养资料。")));assertTrue(other.retrieve(query("水果"),replacement).isEmpty());assertEquals(before+1,calls.get());
    }
    @Test void providerFailureFallsBackWithoutLeakingTheProviderResponse(){
        var source=index(List.of(doc("apple","苹果培养资料。")));var search=new LocalGuideSemanticSearch(properties("fixture-model"));search.build(source);mode="FAIL";
        assertEquals(source.retrieve(query("苹果")),search.retrieve(query("苹果"),source));assertEquals("PROVIDER_ERROR",search.status(source).state());assertEquals("BM25",search.status(source).mode());
    }
    @Test void invalidBatchesNeverPublishAnIndexAndUnconfiguredDoesNotCallTheProvider(){
        var source=index(List.of(doc("apple","苹果资料。"),doc("banana","香蕉资料。")));
        for(String invalid:List.of("DIMENSION","DUPLICATE","ZERO","MODEL")){mode=invalid;var search=new LocalGuideSemanticSearch(properties(invalid));assertFalse(search.build(source).ready(),invalid);assertTrue(search.retrieve(query("水果"),source).isEmpty());}
        int before=calls.get();var absent=new LocalGuideSemanticSearch(new GuideSemanticProperties(endpoint,"","",2,dir.resolve("absent.sqlite").toString(),2,.8));assertFalse(absent.build(source).configured());absent.retrieve(query("水果"),source);assertEquals(before,calls.get());assertFalse(properties("x").toString().contains("unit-test"));
    }
    @Test void vectorTamperingFallsBackAndExplicitBuildRepairsCacheAtomically() throws Exception {
        var source=index(List.of(doc("apple","苹果资料。")));var search=new LocalGuideSemanticSearch(properties("fixture-model"));search.build(source);
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("vectors.sqlite"));var statement=connection.createStatement()){statement.execute("UPDATE guide_vector SET payload='[0,1]'");}
        int before=calls.get();assertTrue(search.retrieve(query("水果"),source).isEmpty());assertEquals(before,calls.get());assertEquals("INDEX_INTEGRITY_ERROR",search.status(source).state());
        assertTrue(search.build(source).ready());assertFalse(search.retrieve(query("水果"),source).isEmpty());
    }
}
