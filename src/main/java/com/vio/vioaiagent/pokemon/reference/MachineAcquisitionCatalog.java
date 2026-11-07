package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Broad shared regions only. Source omission is not proof that a TM is unavailable there. */
public final class MachineAcquisitionCatalog {
    public static final String SHA256="sha256:e8cee2cd0fc2e36a1a794329d826f2fefbab988d59053d9b2ca12c850031bd3e";
    public static final String SCHEMA="pokemon-machine-acquisition-reference-v1";
    public static final String SCOPE="COMMON_COMMUNITY_REGIONS_NOT_EXACT_LOCATION_UNLOCK_OR_LEGALITY";
    public record Region(String id,String nameEn) { }
    public record Reference(String schemaVersion,String gameReleaseId,String moveSlug,String label,String nameZh,
                            String status,List<Region> regions,List<MachineRecipeCatalog.Source> sources,String unlockStatus,
                            String checkedAt,String reviewStatus,String scope,String catalogSha256) {
        public Reference { regions=List.copyOf(regions);sources=List.copyOf(sources); }
    }
    private record Location(String path,String label) { }
    private record Excerpt(int offset,int length,String sha256) { }
    private record Observation(String sourceId,List<Location> locations,String unlockNote,Excerpt excerpt) { }
    private record Mapping(String id,String nameEn,Map<String,String> paths) { }
    private record Row(String moveSlug,String label,String nameZh,List<Observation> observations) { }
    private record Catalog(String schemaVersion,String gameReleaseId,String checkedAt,String scope,String reviewStatus,
                           List<MachineRecipeCatalog.Source> sources,List<Mapping> regionMappings,List<Row> records) { }
    private static final class Current { static final MachineAcquisitionCatalog VALUE=new MachineAcquisitionCatalog(new DefaultResourceLoader()); }
    private final Map<String,Reference> references;
    public MachineAcquisitionCatalog(ResourceLoader loader) {
        try(var stream=loader.getResource("classpath:pokemon-machines/acquisition-catalog-v1.json").getInputStream()) {
            byte[] bytes=stream.readNBytes(30001);if(bytes.length>30000)throw new IllegalArgumentException("区域资料过大");
            String text=new String(bytes,StandardCharsets.UTF_8).replace("\r\n","\n");
            if(!SHA256.equals(PokeApiClient.digest(text)))throw new IllegalArgumentException("区域资料身份不符");
            var c=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(text,Catalog.class);
            if(!"pokemon-machine-acquisition-catalog-v1".equals(c.schemaVersion())||!"pokemon-scarlet-violet".equals(c.gameReleaseId())
                    ||!SCOPE.equals(c.scope())||!MachineRecipeCatalog.REVIEW.equals(c.reviewStatus())||c.sources().size()!=2||c.records().size()!=4||c.regionMappings().size()!=4)
                throw new IllegalArgumentException("区域资料范围不符");
            Map<String,Reference> values=new LinkedHashMap<>();
            for(var row:c.records()) {
                var machine=MachineReferenceCatalog.current().find(row.moveSlug());
                if(machine==null||!machine.label().equals(row.label())||row.observations().size()!=2
                        ||!"pokemondb".equals(row.observations().get(0).sourceId())||!"serebii".equals(row.observations().get(1).sourceId()))throw new IllegalArgumentException("区域来源与编号不符");
                var common=c.regionMappings().stream().filter(m->row.observations().stream().allMatch(o->o.locations().stream().anyMatch(l->l.path().equals(m.paths().get(o.sourceId())))))
                        .map(m->new Region(m.id(),m.nameEn())).toList();
                String status=row.observations().stream().anyMatch(o->o.locations().isEmpty())?"SOURCE_INCOMPLETE":common.isEmpty()?"NO_COMMON_REGION":"COMMON_REGION_REFERENCE";
                var reference=new Reference(SCHEMA,c.gameReleaseId(),row.moveSlug(),row.label(),row.nameZh(),status,common,c.sources(),"NOT_CROSS_CHECKED",c.checkedAt(),c.reviewStatus(),SCOPE,SHA256);
                if(values.putIfAbsent(row.moveSlug(),reference)!=null)throw new IllegalArgumentException("重复区域记录");
            }
            references=Map.copyOf(values);
        }catch(Exception invalid){throw new IllegalStateException("固定学习器区域资料校验失败",invalid);}
    }
    public static Reference read(String moveSlug,String gameReleaseId) {
        if(!PokemonLearnsetFacts.slug(moveSlug)||!"pokemon-scarlet-violet".equals(gameReleaseId))throw new IllegalArgumentException("区域参考仅支持招式英文标识及朱紫范围");
        var r=Current.VALUE.references.get(moveSlug);
        return r!=null?r:new Reference(SCHEMA,gameReleaseId,moveSlug,null,null,"NOT_CATALOGUED",List.of(),List.of(),"NOT_CROSS_CHECKED","2026-10-27",MachineRecipeCatalog.REVIEW,SCOPE,SHA256);
    }
    public static String answer(String moveSlug) {
        var r=read(moveSlug,"pokemon-scarlet-violet");if("NOT_CATALOGUED".equals(r.status()))return "";
        var out=new StringBuilder("\n获取区域线索（社区参考，待人工及游戏内核验）：");
        if("COMMON_REGION_REFERENCE".equals(r.status()))out.append(String.join("、",r.regions().stream().map(Region::nameEn).toList())).append("。两份资料共同列出这些大区域；原地点列表并不完全一致。\n");
        else out.append("未取得可交叉对应的区域资料，不能据此断言没有获取地点。\n");
        out.append("\n来源：");
        for(int i=0;i<r.sources().size();i++){var s=r.sources().get(i);if(i>0)out.append(" · ");out.append('[').append(s.publisher()).append("](").append(s.url()).append(')');}
        return out.append("。核对日期 ").append(r.checkedAt()).append("；精确坐标、NPC条件、剧情解锁和路线尚未核验，请在游戏内确认。\n").toString();
    }
}
