package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Validated public projections for common resources, with the existing fixed-origin cache for other names. */
@Service @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.pokemon.reference.enabled",havingValue="true")
public class LocalResourceReferences {
    private final ObjectMapper mapper;
    private final PokeApiClient client;
    private final Map<String,ObjectNode> bundled;
    @org.springframework.beans.factory.annotation.Autowired
    public LocalResourceReferences(ObjectMapper mapper,ResourceLoader loader,
            @Value("${vio.pokemon.reference.path:${user.dir}/.local-data/pokeapi}")String path,
            @Value("${vio.pokemon.reference.proxy:}")String proxy) {
        this(mapper,loader,PokeApiClient.configured(mapper,Path.of(path),proxy));
    }
    public LocalResourceReferences(ObjectMapper mapper,ResourceLoader loader,PokeApiClient client) {
        this.mapper=mapper;this.client=client;
        try(var data=loader.getResource("classpath:pokemon-mechanics/references-v1.json").getInputStream();
            var metadata=loader.getResource("classpath:pokemon-mechanics/references-v1.meta.json").getInputStream()) {
            byte[] bytes=data.readNBytes(400001);if(bytes.length>400000)throw new IllegalArgumentException("机制资料过大");
            var meta=mapper.readTree(metadata.readNBytes(16000));String text=new String(bytes,StandardCharsets.UTF_8).replace("\r\n","\n");
            var root=mapper.readTree(text);
            if(!"pokemon-mechanic-catalog-v1".equals(root.path("schemaVersion").asText())||!root.path("records").isArray()||root.path("records").size()>200
                    ||!PokeApiClient.digest(text).equals(meta.path("catalogSha256").asText())||root.path("records").size()!=meta.path("recordCount").asInt()
                    ||!"PENDING_HUMAN_REVIEW".equals(meta.path("reviewStatus").asText()))throw new IllegalArgumentException("机制资料版本无效");
            Map<String,ObjectNode> rows=new HashMap<>();
            for(var row:root.path("records")){var checked=ResourceReferenceFacts.validate(row);String key=checked.path("kind").asText()+":"+checked.path("slug").asText();
                if(!"BUNDLED_PUBLIC_REFERENCE".equals(checked.path("sourceKind").asText())||rows.putIfAbsent(key,checked)!=null)throw new IllegalArgumentException("重复机制资料");}
            bundled=Map.copyOf(rows);
        }catch(Exception invalid){throw new IllegalStateException("本地机制参考加载失败",invalid);}
    }
    public Map<String,Object> read(String kind,String slug,String release) {
        if(!"pokeapi-reference-v1".equals(release)||kind==null||!Set.of("ability","move","nature").contains(kind)||slug==null||!slug.matches("[a-z0-9][a-z0-9-]{0,79}"))throw new IllegalArgumentException("资源身份或参考版本无效");
        ObjectNode fact=bundled.get(kind+":"+slug);
        if(fact==null)fact=project(kind,slug,client.read(kind,slug));
        return mapper.convertValue(fact,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
    }
    ObjectNode project(String kind,String slug,PokeApiClient.Snapshot raw) {
        try {
            var d=mapper.readTree(raw.payload());
            if(!slug.equals(d.path("name").asText())||!d.path("id").isIntegralNumber()||!raw.sha256().equals(PokeApiClient.digest(raw.payload())))throw new IllegalArgumentException("资源身份不符");
            ObjectNode out=mapper.createObjectNode();out.put("schemaVersion",ResourceReferenceFacts.SCHEMA).put("kind",kind).put("slug",slug).set("resourceId",d.get("id"));
            out.put("dataReleaseId","pokeapi-reference-v1").put("sourceUrl",raw.url()).put("checksum",raw.sha256()).put("retrievedAt",raw.retrievedAt().toString())
                    .put("sourceKind","LIVE_CACHE_REFERENCE").put("fieldScope","UPSTREAM_REFERENCE_NOT_PATCH_FROZEN");
            List<JsonNode> names=new ArrayList<>();for(var name:d.path("names"))if("zh-hans".equalsIgnoreCase(name.path("language").path("name").asText()))names.add(name);
            if(names.size()>1)throw new IllegalArgumentException("资源中文名不唯一");out.put("nameZh",names.isEmpty()?slug:names.getFirst().path("name").asText());
            if(kind.equals("move")){out.put("typeKey",d.path("type").path("name").asText()).put("damageClass",d.path("damage_class").path("name").asText());
                for(String field:List.of("power","accuracy","pp","priority")){if(!d.has(field))throw new IllegalArgumentException("缺少招式字段");out.set(field,d.get(field));}}
            if(kind.equals("nature")){for(String field:List.of("increased_stat","decreased_stat"))if(!d.has(field))throw new IllegalArgumentException("缺少性格字段");
                if(d.get("increased_stat").isNull())out.putNull("increasedStat");else out.put("increasedStat",d.path("increased_stat").path("name").asText());
                if(d.get("decreased_stat").isNull())out.putNull("decreasedStat");else out.put("decreasedStat",d.path("decreased_stat").path("name").asText());
                out.put("neutralNature",d.get("increased_stat").isNull()&&d.get("decreased_stat").isNull());}
            List<JsonNode> zh=new ArrayList<>(),en=new ArrayList<>();
            for(var row:d.path("flavor_text_entries"))if("scarlet-violet".equals(row.path("version_group").path("name").asText())) {
                String language=row.path("language").path("name").asText();if(language.equalsIgnoreCase("zh-hans"))zh.add(row);else if(language.equals("en"))en.add(row);}
            var chosen=zh.isEmpty()?en:zh;if(chosen.size()>1)throw new IllegalArgumentException("朱紫说明不唯一");
            if(!chosen.isEmpty()){var row=chosen.getFirst();String text=row.path("flavor_text").asText().replaceAll("\\s+"," ").strip();boolean english=zh.isEmpty();String[] words=text.split(" ");
                out.put("descriptionLanguage",english?"en":"zh-hans").put("descriptionVersionGroup","scarlet-violet").put("descriptionExcerpt",english?String.join(" ",Arrays.copyOf(words,Math.min(25,words.length))):text)
                        .put("descriptionSha256",PokeApiClient.digest(text)).put("descriptionTruncated",english&&words.length>25);}
            return ResourceReferenceFacts.seal(out);
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalArgumentException("资源JSON无效",invalid);}
    }
}
