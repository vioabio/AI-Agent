package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Searchable public labels; membership is reference evidence, never a legality verdict. */
@Service
@Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.pokemon.reference.enabled", havingValue="true")
public class LocalBuildOptions {
    private final ObjectMapper mapper;
    private final PokeApiClient client;
    private final Map<String, Choice> catalog;
    private final String catalogSha256;

    @org.springframework.beans.factory.annotation.Autowired
    public LocalBuildOptions(ObjectMapper mapper, ResourceLoader loader,
            @Value("${vio.pokemon.reference.path:${user.dir}/.local-data/pokeapi}") String directory,
            @Value("${vio.pokemon.reference.proxy:}") String proxy) {
        this(mapper, loader, PokeApiClient.configured(mapper, Path.of(directory), proxy));
    }
    public LocalBuildOptions(ObjectMapper mapper, ResourceLoader loader, PokeApiClient client) {
        this.mapper=mapper; this.client=client;
        try (var input=loader.getResource("classpath:pokemon-build-options/catalog-v1.json").getInputStream();
             var metadata=loader.getResource("classpath:pokemon-build-options/catalog-v1.meta.json").getInputStream()) {
            String payload=new String(input.readNBytes(1_000_001), StandardCharsets.UTF_8).replace("\r\n", "\n");
            JsonNode meta=mapper.readTree(metadata.readNBytes(32000)), data=mapper.readTree(payload);
            catalogSha256=meta.path("sha256").asText();
            if(payload.length()>1_000_000 || !PokeApiClient.digest(payload).equals(catalogSha256)
                    || data.path("schemaVersion").asInt()!=1 || !data.path("records").isArray()
                    || data.path("records").size()>10000) throw new IllegalStateException("候选目录校验失败");
            Map<String, Choice> values=new LinkedHashMap<>();
            for(JsonNode row:data.path("records")) {
                String kind=row.path("kind").asText(), slug=row.path("slug").asText(), name=row.path("nameZh").asText();
                if(!Set.of("ability","move","item").contains(kind) || !slug.matches("[a-z0-9][a-z0-9-]{0,79}")
                        || name.isBlank() || name.length()>128) throw new IllegalStateException("候选记录无效");
                Choice value=new Choice(kind, slug, name, row.path("typeKey").asText(null), row.path("damageClass").asText(null),
                        nullableInt(row.get("power")), nullableInt(row.get("accuracy")), row.path("description").asText(null), null, List.of());
                if(values.put(kind+":"+slug,value)!=null) throw new IllegalStateException("候选目录重复");
            }
            catalog=Collections.unmodifiableMap(values);
        } catch(java.io.IOException error) { throw new IllegalStateException("候选目录无法读取", error); }
    }
    private static Integer nullableInt(JsonNode value) { return value==null || value.isNull() ? null : value.intValue(); }

    public Page search(String kind, Long pokemonId, String release, String game, String query, int offset, int limit) {
        if(!Set.of("ability","move","item").contains(kind) || !"pokeapi-reference-v1".equals(release)
                || !"pokemon-scarlet-violet".equals(game) || query==null || query.length()>80
                || offset<0 || offset>10000 || limit<1 || limit>100) throw new IllegalArgumentException("候选查询参数或资料版本无效");
        List<Choice> choices=new ArrayList<>();
        String pokemonChecksum=null;
        if(kind.equals("item")) {
            catalog.values().stream().filter(value->value.kind().equals("item")).forEach(choices::add);
        } else {
            if(pokemonId==null || pokemonId<1 || pokemonId>Integer.MAX_VALUE) throw new IllegalArgumentException("请选择有效的队伍成员");
            var snapshot=client.read("pokemon", pokemonId.toString());
            try {
                JsonNode pokemon=mapper.readTree(snapshot.payload());
                if(!snapshot.sha256().equals(PokeApiClient.digest(snapshot.payload())) || pokemon.path("id").asLong()!=pokemonId
                        || !pokemon.path(kind.equals("ability")?"abilities":"moves").isArray()) throw new IllegalStateException("成员候选来源不匹配");
                pokemonChecksum=snapshot.sha256();
                if(kind.equals("ability")) {
                    for(JsonNode row:pokemon.path("abilities")) {
                        if(!row.path("is_hidden").isBoolean()) throw new IllegalStateException("特性记录缺少隐藏标记");
                        Choice base=choice(kind,row.path("ability").path("name").asText());
                        choices.add(new Choice(kind,base.slug(),base.nameZh(),null,null,null,null,base.description(),row.path("is_hidden").asBoolean(),List.of()));
                    }
                } else {
                    for(JsonNode row:pokemon.path("moves")) {
                        List<Learning> learning=new ArrayList<>();
                        if(!row.path("version_group_details").isArray()) throw new IllegalStateException("招式记录缺少版本范围");
                        for(JsonNode version:row.path("version_group_details")) {
                            if(!"scarlet-violet".equals(version.path("version_group").path("name").asText())) continue;
                            String method=version.path("move_learn_method").path("name").asText();
                            JsonNode level=version.path("level_learned_at");
                            if(!method.matches("[a-z0-9-]{1,80}") || !level.isIntegralNumber() || level.intValue()<0 || level.intValue()>100)
                                throw new IllegalStateException("学习记录无效");
                            learning.add(new Learning(method,level.intValue()));
                        }
                        if(learning.isEmpty()) continue;
                        Choice base=choice(kind,row.path("move").path("name").asText());
                        choices.add(new Choice(kind,base.slug(),base.nameZh(),base.typeKey(),base.damageClass(),base.power(),base.accuracy(),null,null,List.copyOf(learning)));
                    }
                }
            } catch(com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("成员候选资料无法解析",error); }
        }
        String needle=query.trim().toLowerCase(Locale.ROOT);
        List<Choice> matches=choices.stream().filter(value->value.slug().contains(needle)||value.nameZh().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(Choice::slug)).toList();
        int start=Math.min(offset,matches.size()), end=Math.min(start+limit,matches.size());
        return new Page(kind,release,game,kind.equals("item")?null:pokemonId,List.copyOf(matches.subList(start,end)),matches.size(),
                end<matches.size()?end:null,catalogSha256,pokemonChecksum,"参考候选与参数不代表补丁冻结数据、当前可获得性或赛制合法性");
    }
    private Choice choice(String kind,String slug) {
        if(!slug.matches("[a-z0-9][a-z0-9-]{0,79}")) throw new IllegalStateException("候选名称无效");
        return catalog.getOrDefault(kind+":"+slug,new Choice(kind,slug,slug,null,null,null,null,null,null,List.of()));
    }
    public record Learning(String method,int level) {}
    public record Choice(String kind,String slug,String nameZh,String typeKey,String damageClass,Integer power,Integer accuracy,String description,Boolean hidden,List<Learning> learning) {}
    public record Page(String kind,String dataReleaseId,String gameReleaseId,Long pokemonId,List<Choice> items,int total,Integer nextOffset,
                       String catalogSha256,String pokemonChecksum,String limitation) {}
}
