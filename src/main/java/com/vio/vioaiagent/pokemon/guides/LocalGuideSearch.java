package com.vio.vioaiagent.pokemon.guides;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.reference.PokeApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** Small local lexical knowledge index, not a vector database or a current metagame feed. */
@Service @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.local-guides.enabled",havingValue="true")
public class LocalGuideSearch {
    private final List<Document> documents;
    public LocalGuideSearch(ObjectMapper mapper,ResourceLoader loader,
            @Value("${vio.local-guides.location:classpath:pokemon-guides/index.json}") String location) {
        try(var stream=loader.getResource(location).getInputStream()) {
            byte[] bytes=stream.readNBytes(2_000_001);
            if(bytes.length>2_000_000)throw new IllegalArgumentException("攻略索引超过大小限制");
            JsonNode root=mapper.readTree(bytes);
            if(root.path("schemaVersion").asInt()!=1 || !root.path("documents").isArray() || root.path("documents").size()>200)throw new IllegalArgumentException("攻略索引格式错误");
            List<Document> values=new ArrayList<>();Set<String> ids=new HashSet<>();
            for(JsonNode row:root.path("documents")) {
                Document doc=mapper.treeToValue(row,Document.class);
                if(doc.id()==null||!ids.add(doc.id())||doc.title()==null||doc.content()==null||doc.content().length()>20000
                        ||doc.author()==null||doc.updatedAt()==null||!"pokemon-scarlet-violet".equals(doc.gameReleaseId()))throw new IllegalArgumentException("攻略缺少身份、作者、日期或游戏范围");
                java.time.LocalDate.parse(doc.updatedAt());
                if(doc.sourceUrl()!=null&&!doc.sourceUrl().isBlank()) {
                    var url=java.net.URI.create(doc.sourceUrl());
                    if(!Set.of("https","http").contains(url.getScheme())||url.getHost()==null||url.getUserInfo()!=null)throw new IllegalArgumentException("攻略来源链接无效");
                }
                values.add(doc);
            }
            documents=List.copyOf(values);
        }catch(Exception e){throw new IllegalStateException("本地攻略索引加载失败",e);}
    }
    public List<Hit> search(String query,String gameReleaseId) {
        if(!"pokemon-scarlet-violet".equals(gameReleaseId))throw new IllegalArgumentException("攻略游戏版本未登记");
        if(query==null||query.isBlank()||query.length()>500)throw new IllegalArgumentException("请输入 1..500 字的攻略问题");
        Set<String> tokens=tokens(query);
        return documents.stream().filter(d->d.gameReleaseId().equals(gameReleaseId)).map(d->{
            int score=0;
            for(String token:tokens) {if(d.title().toLowerCase(Locale.ROOT).contains(token))score+=4;if((d.keywords()+" "+d.content()).toLowerCase(Locale.ROOT).contains(token))score++;}
            return new Hit(d,score,PokeApiClient.digest(d.id()+d.gameReleaseId()+d.updatedAt()+d.content()));
        }).filter(hit->hit.score()>=2).sorted(Comparator.comparingInt(Hit::score).reversed().thenComparing(h->h.document().id())).limit(4).toList();
    }
    private Set<String> tokens(String query) {
        Set<String> values=new HashSet<>();
        String normalized=query.toLowerCase(Locale.ROOT);
        for(String token:normalized.split("[^a-z0-9]+"))if(token.length()>1)values.add(token);
        int[] chars=normalized.codePoints().toArray();
        for(int i=0;i<chars.length-1;i++)if(Character.UnicodeScript.of(chars[i])==Character.UnicodeScript.HAN&&Character.UnicodeScript.of(chars[i+1])==Character.UnicodeScript.HAN)values.add(new String(chars,i,2));
        return values;
    }
    public record Document(String id,String title,String gameReleaseId,String keywords,String author,String updatedAt,String sourceUrl,String content){}
    public record Hit(Document document,int score,String checksum){}
}
