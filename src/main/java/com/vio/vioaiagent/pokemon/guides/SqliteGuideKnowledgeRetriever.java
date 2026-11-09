package com.vio.vioaiagent.pokemon.guides;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.PokeApiClient;
import com.vio.vioaiagent.rag.catalog.KnowledgeCatalog.KnowledgeChunkView;
import com.vio.vioaiagent.rag.domain.DocumentChunk;
import com.vio.vioaiagent.rag.retrieval.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Persisted inverted index with BM25 ranking. No embedding service, model or network is used.
 * Owned by LocalGuideSearch rather than registered as a second unqualified retriever bean. */
public final class SqliteGuideKnowledgeRetriever implements KnowledgeRetriever {
    public static final String DATA_RELEASE = "local-guides-source-v1";
    private static final Object INITIALIZATION_LOCK = new Object();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Map<String,LocalGuideSearch.Document> documents;
    private final Map<String,GuideChunks.Part> parts;
    private final Status status;

    public SqliteGuideKnowledgeRetriever(ObjectMapper mapper,List<LocalGuideSearch.Document> source,String file) {
        try {
            Path path=Path.of(file).toAbsolutePath().normalize(); Files.createDirectories(path.getParent());
            SQLiteConfig config=new SQLiteConfig(); config.setBusyTimeout(5000);
            config.setJournalMode(SQLiteConfig.JournalMode.WAL);
            config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
            SQLiteDataSource dataSource=new SQLiteDataSource(config); dataSource.setUrl("jdbc:sqlite:"+path);
            jdbc=new JdbcTemplate(dataSource);
            transactions=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            Map<String,LocalGuideSearch.Document> docs=new TreeMap<>();
            Map<String,GuideChunks.Part> chunks=new TreeMap<>();
            List<List<Object>> docRows=new ArrayList<>(),chunkRows=new ArrayList<>(),termRows=new ArrayList<>();
            Set<String> contentKeys=new HashSet<>(),ids=new HashSet<>();
            for(var doc:source.stream().sorted(Comparator.comparing(LocalGuideSearch.Document::id)).toList()) {
                LocalGuideSearch.validate(doc);
                if(!ids.add(doc.id()))throw new IllegalArgumentException("攻略身份重复");
                String contentKey=PokeApiClient.digest(doc.gameReleaseId()+doc.language()+doc.battleFormatId()
                        +doc.rulesetVersionId()+doc.validUntil()+doc.content().replace("\r\n","\n").strip());
                if(!contentKeys.add(contentKey))continue;
                String payload=mapper.writeValueAsString(doc);
                String version=GuideChunks.documentVersion(doc);
                docs.put(doc.id(),doc); docRows.add(List.of(doc.id(),payload));
                for(var part:GuideChunks.split(doc,version)) {
                    chunks.put(part.id(),part);
                    Map<String,Integer> terms=new TreeMap<>(GuideChunks.frequencies(part.content()));
                    GuideChunks.frequencies(doc.title()).forEach((term,tf)->terms.merge(term,tf*4,Integer::sum));
                    GuideChunks.frequencies(doc.keywords()).forEach((term,tf)->terms.merge(term,tf,Integer::sum));
                    int length=terms.values().stream().mapToInt(Integer::intValue).sum();
                    chunkRows.add(List.of(part.id(),part.documentId(),part.version(),part.number(),part.start(),part.end(),part.hash(),length));
                    terms.forEach((term,tf)->termRows.add(List.of(term,part.id(),tf)));
                }
            }
            docRows.sort(Comparator.comparing(row->(String)row.get(0)));
            chunkRows.sort(Comparator.comparing(row->(String)row.get(0)));
            termRows.sort(Comparator.<List<Object>,String>comparing(row->(String)row.get(0)).thenComparing(row->(String)row.get(1)));
            documents=Map.copyOf(docs); parts=Map.copyOf(chunks);
            String fingerprint=PokeApiClient.digest(GuideChunks.ALGORITHM+GuideApplicability.POLICY+mapper.writeValueAsString(List.of(docRows,chunkRows,termRows)));
            // SQLite can fail to open a brand-new Windows file before its normal busy
            // handler applies. Serialize initial opening as well as the import transaction.
            synchronized(INITIALIZATION_LOCK) {
            try(var channel=java.nio.channels.FileChannel.open(path.resolveSibling(path.getFileName()+".import.lock"),
                    StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var initializationLock=channel.lock()) {
            status=transactions.execute(tx->{
                jdbc.execute("CREATE TABLE IF NOT EXISTS guide_meta(id INTEGER PRIMARY KEY CHECK(id=1),fingerprint TEXT NOT NULL,built_at TEXT NOT NULL)");
                jdbc.execute("CREATE TABLE IF NOT EXISTS guide_document(id TEXT PRIMARY KEY,payload TEXT NOT NULL)");
                jdbc.execute("CREATE TABLE IF NOT EXISTS guide_chunk(id TEXT PRIMARY KEY,document_id TEXT NOT NULL,version TEXT NOT NULL,chunk_no INTEGER NOT NULL,start_offset INTEGER NOT NULL,end_offset INTEGER NOT NULL,hash TEXT NOT NULL,length INTEGER NOT NULL)");
                jdbc.execute("CREATE TABLE IF NOT EXISTS guide_term(term TEXT NOT NULL,chunk_id TEXT NOT NULL,tf INTEGER NOT NULL,PRIMARY KEY(term,chunk_id))");
                var meta=jdbc.queryForList("SELECT fingerprint,built_at FROM guide_meta WHERE id=1");
                boolean reuse=meta.size()==1 && fingerprint.equals(meta.get(0).get("fingerprint"))
                        && readRows("SELECT id,payload FROM guide_document ORDER BY id").equals(docRows)
                        && readRows("SELECT id,document_id,version,chunk_no,start_offset,end_offset,hash,length FROM guide_chunk ORDER BY id").equals(chunkRows)
                        && readRows("SELECT term,chunk_id,tf FROM guide_term ORDER BY term,chunk_id").equals(termRows);
                String builtAt=reuse?(String)meta.get(0).get("built_at"):Instant.now().toString();
                if(!reuse) {
                    jdbc.update("DELETE FROM guide_term"); jdbc.update("DELETE FROM guide_chunk"); jdbc.update("DELETE FROM guide_document");
                    jdbc.batchUpdate("INSERT INTO guide_document VALUES(?,?)",docRows.stream().map(List::toArray).toList());
                    jdbc.batchUpdate("INSERT INTO guide_chunk VALUES(?,?,?,?,?,?,?,?)",chunkRows.stream().map(List::toArray).toList());
                    jdbc.batchUpdate("INSERT INTO guide_term VALUES(?,?,?)",termRows.stream().map(List::toArray).toList());
                    jdbc.update("INSERT INTO guide_meta VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET fingerprint=excluded.fingerprint,built_at=excluded.built_at",fingerprint,builtAt);
                }
                return new Status(GuideChunks.ALGORITHM+"+"+GuideApplicability.POLICY,fingerprint,builtAt,reuse,docs.size(),chunks.size(),source.size()-docs.size(),"none");
            });
            }
            }
        } catch(Exception error) {throw new IllegalStateException("本地攻略持久索引初始化失败",error);}
    }
    private List<List<Object>> readRows(String sql) {
        return jdbc.query(sql,(rs,n)->{
            List<Object> row=new ArrayList<>();
            for(int i=1;i<=rs.getMetaData().getColumnCount();i++)row.add(rs.getObject(i));
            return row;
        });
    }
    @Override public List<RetrievedKnowledgeChunk> retrieve(KnowledgeRetrievalQuery query) {
        if(query.text().length()>500)throw new IllegalArgumentException("请输入 1..500 字的攻略问题");
        if(query.language()==null || query.gameReleaseId()==null || query.battleFormatId()==null || query.rulesetVersionId()==null)
            return List.of();
        if(query.dataReleaseId()!=null && !DATA_RELEASE.equals(query.dataReleaseId())
                || query.knowledgeReleaseId()!=null && !status.fingerprint().equals(query.knowledgeReleaseId())
                || query.indexReleaseId()!=null && !status.fingerprint().equals(query.indexReleaseId()))return List.of();
        Set<String> terms=GuideChunks.tokens(query.text()); if(terms.isEmpty())return List.of();
        return transactions.execute(tx->{
            assertCurrent();
            Set<String> eligible=new HashSet<>();
            for(var part:parts.values()) {
                var doc=documents.get(part.documentId());
                if(doc.language().equals(query.language()) && doc.gameReleaseId().equals(query.gameReleaseId())
                        && doc.battleFormatId().equals(query.battleFormatId()) && doc.rulesetVersionId().equals(query.rulesetVersionId())
                        && fresh(doc) && (doc.applicability()==null||doc.applicability().allows(query.text())))eligible.add(part.id());
            }
            if(eligible.isEmpty())return List.of();
            Map<String,Integer> lengths=new HashMap<>();
            jdbc.query("SELECT id,length FROM guide_chunk",(org.springframework.jdbc.core.RowCallbackHandler)rs->{
                if(eligible.contains(rs.getString(1)))lengths.put(rs.getString(1),rs.getInt(2));
            });
            double average=lengths.values().stream().mapToInt(Integer::intValue).average().orElse(1);
            List<Posting> postings=jdbc.query("SELECT term,chunk_id,tf FROM guide_term WHERE term IN ("
                    +String.join(",",Collections.nCopies(terms.size(),"?"))+")",
                    (rs,n)->new Posting(rs.getString(1),rs.getString(2),rs.getInt(3)),terms.toArray());
            postings=postings.stream().filter(p->eligible.contains(p.chunkId())).toList();
            Map<String,Integer> df=new HashMap<>(); postings.forEach(p->df.merge(p.term(),1,Integer::sum));
            Map<String,Double> scores=new HashMap<>();
            for(var posting:postings) {
                double idf=Math.log(1+(eligible.size()-df.get(posting.term())+0.5)/(df.get(posting.term())+0.5));
                double norm=posting.tf()+1.2*(0.25+0.75*lengths.get(posting.chunkId())/Math.max(1,average));
                scores.merge(posting.chunkId(),idf*posting.tf()*2.2/norm,Double::sum);
            }
            List<Map.Entry<String,Double>> sorted=scores.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String,Double>>comparingInt(entry->{
                        var scope=documents.get(parts.get(entry.getKey()).documentId()).applicability();
                        return scope!=null&&!scope.subjects().isEmpty()?0:1;
                    }).thenComparing(Map.Entry.<String,Double>comparingByValue().reversed()).thenComparing(Map.Entry.comparingByKey()))
                    .limit(query.topK()).toList();
            List<RetrievedKnowledgeChunk> result=new ArrayList<>();
            for(int i=0;i<sorted.size();i++)result.add(view(parts.get(sorted.get(i).getKey()),i+1,sorted.get(i).getValue()));
            return List.copyOf(result);
        });
    }
    private RetrievedKnowledgeChunk view(GuideChunks.Part part,int rank,double score) {
        var doc=documents.get(part.documentId());
        var metadata=Map.<String,Object>of("author",doc.author(),"updatedAt",doc.updatedAt(),
                "startOffset",part.start(),"endOffset",part.end(),"documentChecksum",LocalGuideSearch.checksum(doc),
                "indexReleaseId",status.fingerprint(),"sourceKind","PROJECT_NOTE");
        var chunk=new DocumentChunk(part.id(),part.version(),part.number(),part.content(),part.hash(),metadata,
                null,null,DocumentChunk.EmbeddingStatus.PENDING);
        var source=new KnowledgeChunkView(chunk,doc.id(),part.version(),doc.title(),doc.sourceUrl(),doc.language(),
                doc.gameReleaseId(),doc.battleFormatId(),doc.rulesetVersionId());
        return new RetrievedKnowledgeChunk(source,rank,0,score);
    }
    /** Canonical source views for vector ingestion; vectors never become the citation authority. */
    List<RetrievedKnowledgeChunk> embeddingSources() {
        return transactions.execute(tx->{assertCurrent();return parts.values().stream().sorted(Comparator.comparing(GuideChunks.Part::id)).map(p->view(p,0,0)).toList();});
    }
    List<RetrievedKnowledgeChunk> semanticEligible(KnowledgeRetrievalQuery query) {
        if(query.text().length()>500)throw new IllegalArgumentException("请输入 1..500 字的攻略问题");
        if(query.language()==null||query.gameReleaseId()==null||query.battleFormatId()==null||query.rulesetVersionId()==null)return List.of();
        if(query.dataReleaseId()!=null&&!DATA_RELEASE.equals(query.dataReleaseId())||query.knowledgeReleaseId()!=null&&!status.fingerprint().equals(query.knowledgeReleaseId())||query.indexReleaseId()!=null&&!status.fingerprint().equals(query.indexReleaseId()))return List.of();
        return transactions.execute(tx->{assertCurrent();return parts.values().stream().filter(p->{var d=documents.get(p.documentId());return d.language().equals(query.language())&&d.gameReleaseId().equals(query.gameReleaseId())&&d.battleFormatId().equals(query.battleFormatId())&&d.rulesetVersionId().equals(query.rulesetVersionId())&&fresh(d)&&(d.applicability()==null||d.applicability().allows(query.text()));}).map(p->view(p,0,0)).toList();});
    }
    public LocalGuideSearch.Hit hit(RetrievedKnowledgeChunk result) {
        var part=parts.get(result.view().chunk().id()); var doc=documents.get(part.documentId());
        var citation=new LocalGuideSearch.Citation(part.id(),part.version(),part.number(),part.start(),part.end(),part.content(),part.hash());
        return new LocalGuideSearch.Hit(doc,Math.max(2,(int)Math.round(result.retrievalScore()*1000)),LocalGuideSearch.checksum(doc),citation);
    }
    public Optional<LocalGuideSearch.Hit> locate(String id) {
        return transactions.execute(tx->{
            assertCurrent(); var part=parts.get(id);
            if(part==null || !fresh(documents.get(part.documentId())))return Optional.empty();
            return Optional.of(hit(view(part,1,0)));
        });
    }
    private static boolean fresh(LocalGuideSearch.Document doc) {
        return doc.validUntil()==null || !LocalDate.parse(doc.validUntil()).isBefore(LocalDate.now());
    }
    private void assertCurrent() {
        if(!status.fingerprint().equals(jdbc.queryForObject("SELECT fingerprint FROM guide_meta WHERE id=1",String.class)))
            throw new IllegalStateException("攻略索引已被其他进程更新，请重启后重新检索");
    }
    public Status status() {return status;}
    private record Posting(String term,String chunkId,int tf) { }
    public record Status(String algorithm,String fingerprint,String builtAt,boolean reused,int documentCount,int chunkCount,int duplicateCount,String embeddingModel) { }
}
