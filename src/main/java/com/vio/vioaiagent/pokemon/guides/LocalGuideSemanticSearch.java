package com.vio.vioaiagent.pokemon.guides;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.PokeApiClient;
import com.vio.vioaiagent.rag.retrieval.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.*;
import java.nio.file.*;
import java.util.*;

/** Small local corpus: persisted vectors, canonical citations, scope-first cosine search and RRF. */
public final class LocalGuideSemanticSearch {
    private final GuideSemanticProperties config;
    private final GuideEmbeddingClient client;
    private final ObjectMapper json=new ObjectMapper();
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private volatile String state="NOT_INDEXED";
    public record Status(boolean enabled,boolean configured,boolean ready,String mode,String model,int dimensions,int indexedChunks,String state) { }
    public LocalGuideSemanticSearch(GuideSemanticProperties config){this.config=config;client=new GuideEmbeddingClient(config);}
    private synchronized void database(){
        if(jdbc!=null)return;
        try{
            Path file=Path.of(config.path()).toAbsolutePath().normalize();Files.createDirectories(file.getParent());
            var options=new SQLiteConfig();options.setBusyTimeout(5000);options.setJournalMode(SQLiteConfig.JournalMode.WAL);
            var ds=new SQLiteDataSource(options);ds.setUrl("jdbc:sqlite:"+file);
            var db=new JdbcTemplate(ds);
            db.execute("CREATE TABLE IF NOT EXISTS guide_vector_meta(index_id TEXT PRIMARY KEY,chunk_count INTEGER NOT NULL)");
            db.execute("CREATE TABLE IF NOT EXISTS guide_vector(index_id TEXT NOT NULL,chunk_id TEXT NOT NULL,content_hash TEXT NOT NULL,payload TEXT NOT NULL,payload_hash TEXT NOT NULL,PRIMARY KEY(index_id,chunk_id))");
            transactions=new TransactionTemplate(new DataSourceTransactionManager(ds));jdbc=db;
        }catch(Exception failure){throw new IllegalStateException("VECTOR_STORAGE_UNAVAILABLE");}
    }
    private String key(SqliteGuideKnowledgeRetriever index){return PokeApiClient.digest("guide-semantic-v1\n"+config.endpoint()+"\n"+config.model()+"\n"+config.dimensions()+"\n"+index.status().fingerprint());}
    private record Row(String id,String hash,String payload,String payloadHash) { }
    private Map<String,double[]> load(SqliteGuideKnowledgeRetriever index){
        database();var source=index.embeddingSources();String key=key(index);
        return transactions.execute(tx->{
            var meta=jdbc.queryForList("SELECT chunk_count FROM guide_vector_meta WHERE index_id=?",key);
            if(meta.size()!=1||((Number)meta.getFirst().get("chunk_count")).intValue()!=source.size())throw new IllegalStateException("INDEX_NOT_READY");
            var rows=jdbc.query("SELECT chunk_id,content_hash,payload,payload_hash FROM guide_vector WHERE index_id=?",(r,n)->new Row(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),key);
            if(rows.size()!=source.size())throw new IllegalStateException("INDEX_NOT_READY");
            var expected=new HashMap<String,String>();source.forEach(s->expected.put(s.view().chunk().id(),s.view().chunk().contentHash()));
            var result=new HashMap<String,double[]>();
            for(var row:rows){
                if(!Objects.equals(expected.get(row.id()),row.hash())||!PokeApiClient.digest(row.payload()).equals(row.payloadHash()))throw new IllegalStateException("INDEX_INTEGRITY_ERROR");
                try{double[] vector=json.readValue(row.payload(),double[].class);GuideEmbeddingClient.validate(vector,config.dimensions());result.put(row.id(),vector);}
                catch(Exception invalid){throw new IllegalStateException("INDEX_INTEGRITY_ERROR");}
            }
            return Map.copyOf(result);
        });
    }
    /** Explicit ingestion only: neither startup nor an ordinary question uploads the corpus. */
    public synchronized Status build(SqliteGuideKnowledgeRetriever index){
        if(!client.configured()){state="UNCONFIGURED";return status(index);}
        try{
            var source=index.embeddingSources();if(source.isEmpty()||source.size()>512)throw new IllegalStateException("CORPUS_SIZE_LIMIT");
            try{load(index);state="READY_REUSED";return status(index);}catch(IllegalStateException needsBuild){/* Explicit user build may replace an incomplete/corrupt cache. */}
            var vectors=new ArrayList<double[]>();
            long deadline=System.nanoTime()+45_000_000_000L;
            for(int offset=0;offset<source.size();offset+=GuideEmbeddingClient.MAX_BATCH_SIZE){
                if(Thread.currentThread().isInterrupted())throw new IllegalStateException("EMBEDDING_CANCELLED");
                if(System.nanoTime()>deadline)throw new IllegalStateException("CORPUS_SIZE_LIMIT");
                vectors.addAll(client.embed(source.subList(offset,Math.min(offset+GuideEmbeddingClient.MAX_BATCH_SIZE,source.size())).stream().map(s->s.view().sourceTitle()+"\n"+s.view().chunk().content()).toList()));
            }
            // Check the canonical index has not changed while external batches were running.
            index.embeddingSources();database();String key=key(index);
            transactions.executeWithoutResult(tx->{
                jdbc.update("DELETE FROM guide_vector_meta WHERE index_id=?",key);jdbc.update("DELETE FROM guide_vector WHERE index_id=?",key);
                for(int n=0;n<source.size();n++)try{String payload=json.writeValueAsString(vectors.get(n));var chunk=source.get(n).view().chunk();jdbc.update("INSERT INTO guide_vector VALUES(?,?,?,?,?)",key,chunk.id(),chunk.contentHash(),payload,PokeApiClient.digest(payload));}catch(Exception invalid){throw new IllegalStateException("VECTOR_STORAGE_UNAVAILABLE");}
                jdbc.update("INSERT INTO guide_vector_meta VALUES(?,?)",key,source.size());
            });state="READY";
        }catch(RuntimeException unavailable){state=safeState(unavailable);}
        return status(index);
    }
    public Status status(SqliteGuideKnowledgeRetriever index){
        boolean configured=client.configured();int count=0;
        if(configured)try{count=load(index).size();}catch(RuntimeException notReady){/* Status must not trigger model requests. */}
        boolean ready=count>0;
        return new Status(true,configured,ready,ready&&state.startsWith("READY")?"HYBRID":"BM25",config.model(),config.dimensions(),count,configured?state:"UNCONFIGURED");
    }
    public List<RetrievedKnowledgeChunk> retrieve(KnowledgeRetrievalQuery query,SqliteGuideKnowledgeRetriever index){
        var lexical=index.retrieve(query);
        if(!client.configured()){state="UNCONFIGURED";return lexical;}
        var eligible=index.semanticEligible(query);if(eligible.isEmpty())return lexical;
        try{
            var vectors=load(index);var question=client.embed(List.of(query.text())).getFirst();
            var scores=new HashMap<String,Double>();var canonical=new HashMap<String,RetrievedKnowledgeChunk>();
            for(var hit:eligible){String id=hit.view().chunk().id();canonical.put(id,hit);double score=cosine(question,vectors.get(id));if(score>=config.minimumSimilarity())scores.put(id,score);}
            var semantic=scores.keySet().stream().sorted(Comparator.<String>comparingDouble(scores::get).reversed().thenComparing(Comparator.naturalOrder())).limit(Math.max(10,query.topK()*2)).toList();
            var ranks=new HashMap<String,int[]>();var views=new HashMap<String,RetrievedKnowledgeChunk>(canonical);
            for(int n=0;n<lexical.size();n++){String id=lexical.get(n).view().chunk().id();ranks.computeIfAbsent(id,k->new int[2])[0]=n+1;views.put(id,lexical.get(n));}
            for(int n=0;n<semantic.size();n++)ranks.computeIfAbsent(semantic.get(n),k->new int[2])[1]=n+1;
            var result=new ArrayList<RetrievedKnowledgeChunk>();
            ranks.forEach((id,r)->result.add(new RetrievedKnowledgeChunk(views.get(id).view(),r[0],r[1],(r[0]>0?1.0/(60+r[0]):0)+(r[1]>0?1.0/(60+r[1]):0))));
            state="READY";return result.stream().sorted(Comparator.comparingDouble(RetrievedKnowledgeChunk::retrievalScore).reversed().thenComparing(h->h.view().chunk().id())).limit(query.topK()).toList();
        }catch(RuntimeException unavailable){state=safeState(unavailable);return lexical;}
    }
    private static String safeState(RuntimeException failure){return failure.getMessage()!=null&&failure.getMessage().matches("PROVIDER_HTTP_[1-5][0-9]{2}")?failure.getMessage():Set.of("EMBEDDING_TIMEOUT","EMBEDDING_CONNECTION_FAILED","EMBEDDING_IO_ERROR","UNCONFIGURED","INDEX_NOT_READY","INDEX_INTEGRITY_ERROR","CORPUS_SIZE_LIMIT","EMBEDDING_CANCELLED","DIMENSION_OR_INDEX_MISMATCH","MODEL_MISMATCH","PROVIDER_ERROR","INVALID_ENDPOINT","VECTOR_STORAGE_UNAVAILABLE").contains(failure.getMessage())?failure.getMessage():"EMBEDDING_UNAVAILABLE";}
    private static double cosine(double[] left,double[] right){double dot=0,a=0,b=0;for(int i=0;i<left.length;i++){dot+=left[i]*right[i];a+=left[i]*left[i];b+=right[i]*right[i];}return dot/Math.sqrt(a*b);}
}
