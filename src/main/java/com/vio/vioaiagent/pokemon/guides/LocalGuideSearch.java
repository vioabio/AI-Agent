package com.vio.vioaiagent.pokemon.guides;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.reference.PokeApiClient;
import com.vio.vioaiagent.rag.retrieval.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;

/** Local source adapter; the separately gated v1 release/PGVector pipeline is unchanged. */
@Service @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.local-guides.enabled",havingValue="true")
public class LocalGuideSearch {
    private final SqliteGuideKnowledgeRetriever index;
    private final KnowledgeRetriever retriever;
    private final Catalog catalog;
    public LocalGuideSearch(ObjectMapper mapper,ResourceLoader loader,
            @Value("${vio.local-guides.location:classpath:pokemon-strategy/overview-v1.json}") String location,
            @Value("${vio.local-guides.path:${user.dir}/.local-data/guides.sqlite}") String path) {
        try(var stream=loader.getResource(location).getInputStream()) {
            byte[] bytes=stream.readNBytes(2_000_001);
            if(bytes.length>2_000_000)throw new IllegalArgumentException("攻略索引超过大小限制");
            JsonNode root=mapper.readTree(bytes);
            int schema=root.path("schemaVersion").asInt();
            if(!root.path("schemaVersion").isIntegralNumber() || (schema!=1 && schema!=2 && schema!=3) || !root.path("documents").isArray() || root.path("documents").size()>200)
                throw new IllegalArgumentException("攻略索引格式错误");
            String catalogId=schema==1?"local-guides-legacy-v1":root.path("catalogId").asText();
            if(!catalogId.matches("[a-z0-9][a-z0-9-]{0,99}"))throw new IllegalArgumentException("攻略资料集身份无效");
            List<Document> values=new ArrayList<>(); Set<String> ids=new HashSet<>();
            for(JsonNode row:root.path("documents")) {
                Document doc=mapper.treeToValue(row,Document.class); validate(doc);
                if(schema>=2 && doc.provenance()==null)throw new IllegalArgumentException("扩充资料必须登记来源依据");
                if(schema==3 && doc.applicability()==null)throw new IllegalArgumentException("主题资料必须登记检索范围");
                if (!ids.add(doc.id())) throw new IllegalArgumentException("攻略身份重复");
                values.add(doc);
            }
            index=new SqliteGuideKnowledgeRetriever(mapper,values,path);
            retriever=index;
            catalog=new Catalog(catalogId,schema,PokeApiClient.digest(new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n")).substring(7),values.size());
        }catch(Exception e){throw new IllegalStateException("本地攻略索引加载失败",e);}
    }
    public List<Hit> search(String query,String gameReleaseId) {
        return search(query,gameReleaseId,"zh-CN","casual-singles","casual-v1");
    }
    public List<Hit> search(String query,String gameReleaseId,String language,String format,String rules) {
        if(!"pokemon-scarlet-violet".equals(gameReleaseId))throw new IllegalArgumentException("攻略游戏版本未登记");
        List<RetrievedKnowledgeChunk> chunks=retrieve(new KnowledgeRetrievalQuery(query,language,gameReleaseId,format,rules,20));
        Map<String,Hit> result=new LinkedHashMap<>();
        if(query!=null&&java.util.regex.Pattern.compile("组队|队伍搭配|联防|属性覆盖").matcher(query).find()) {
            // Reserve one result for the explicitly requested team-building topic.
            // Long secondary questions about provenance must not crowd it out.
            var focused=retrieve(new KnowledgeRetrievalQuery("组队 角色 属性覆盖 轮转",language,gameReleaseId,format,rules,4));
            if(!focused.isEmpty())result.put(focused.getFirst().view().documentId(),index.hit(focused.getFirst()));
        }
        for(var chunk:chunks) result.putIfAbsent(chunk.view().documentId(),index.hit(chunk));
        return result.values().stream().limit(4).toList();
    }
    public List<RetrievedKnowledgeChunk> retrieve(KnowledgeRetrievalQuery query) { return retriever.retrieve(query); }
    public SqliteGuideKnowledgeRetriever.Status status() { return index.status(); }
    public Catalog catalog() {return catalog;}
    public Optional<Hit> locate(String chunkId) { return index.locate(chunkId); }
    public static String checksum(Document doc) {
        // Preserve the frozen S2 source checksum contract; chunk versions also bind source metadata.
        return PokeApiClient.digest(doc.id()+doc.gameReleaseId()+doc.updatedAt()+doc.content());
    }
    public static void validate(Document doc) {
        if(doc==null || blank(doc.id(),100) || !doc.id().matches("[a-z0-9][a-z0-9-]*") || blank(doc.title(),200)
                || blank(doc.content(),20000) || blank(doc.author(),200) || doc.updatedAt()==null
                || !"pokemon-scarlet-violet".equals(doc.gameReleaseId()) || !"zh-CN".equals(doc.language())
                || !"casual-singles".equals(doc.battleFormatId()) || !"casual-v1".equals(doc.rulesetVersionId())
                || doc.keywords()==null || doc.keywords().length()>2000)
            throw new IllegalArgumentException("攻略缺少身份、作者、日期或已登记适用范围");
        if(doc.provenance()!=null) {
            if(!"VIO project".equals(doc.author()))throw new IllegalArgumentException("本批来源登记仅支持项目原创说明");
            doc.provenance().validate(doc.updatedAt());
        }
        if(doc.applicability()!=null)doc.applicability().validate();
        LocalDate updated=LocalDate.parse(doc.updatedAt());
        if(updated.isAfter(LocalDate.now()))throw new IllegalArgumentException("攻略日期不能晚于当前日期");
        if(doc.validUntil()!=null && LocalDate.parse(doc.validUntil()).isBefore(updated))
            throw new IllegalArgumentException("攻略有效期早于更新日期");
        if(doc.sourceUrl()!=null&&!doc.sourceUrl().isBlank()) {
            var url=java.net.URI.create(doc.sourceUrl());
            if(!Set.of("https","http").contains(url.getScheme())||url.getHost()==null||url.getUserInfo()!=null)
                throw new IllegalArgumentException("攻略来源链接无效");
        }
    }
    private static boolean blank(String text,int max) {return text==null||text.isBlank()||text.length()>max;}
    public record Document(String id,String title,String gameReleaseId,String keywords,String author,String updatedAt,String sourceUrl,String content,
                           String language,String battleFormatId,String rulesetVersionId,String validUntil,
                           @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) GuideProvenance provenance,
                           @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) GuideApplicability applicability) {
        public Document {
            language=language==null?"zh-CN":language;
            battleFormatId=battleFormatId==null?"casual-singles":battleFormatId;
            rulesetVersionId=rulesetVersionId==null?"casual-v1":rulesetVersionId;
        }
        public Document(String id,String title,String game,String keywords,String author,String date,String url,String content) {
            this(id,title,game,keywords,author,date,url,content,null,null,null,null,null,null);
        }
        public Document(String id,String title,String game,String keywords,String author,String date,String url,String content,
                        String language,String format,String rules,String validUntil) {
            this(id,title,game,keywords,author,date,url,content,language,format,rules,validUntil,null,null);
        }
        public Document(String id,String title,String game,String keywords,String author,String date,String url,String content,
                        String language,String format,String rules,String validUntil,GuideProvenance provenance) {
            this(id,title,game,keywords,author,date,url,content,language,format,rules,validUntil,provenance,null);
        }
    }
    public record Catalog(String catalogId,int schemaVersion,String corpusSha256,int documentCount) { }
    public record Citation(String chunkId,String documentVersion,int chunkNo,int startOffset,int endOffset,String text,String contentHash) { }
    public record Hit(Document document,int score,String checksum,Citation citation) {
        public Hit(Document document,int score,String checksum){this(document,score,checksum,null);}
    }
}
