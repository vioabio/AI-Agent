package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small community recipe projection. Agreement is not an independent game verification. */
public final class MachineRecipeCatalog {
    public static final String SHA256="sha256:4852256e2f3cb25a9ae5545051dd346e5305ee2f0f7431efe3ed5809b86ef2df";
    public static final String SCHEMA="pokemon-machine-recipe-reference-v1";
    public static final String SCOPE="COMMUNITY_RECIPE_REFERENCE_NOT_UNLOCK_ACQUISITION_OR_LEGALITY";
    public static final String REVIEW="PENDING_HUMAN_AND_IN_GAME_VERIFICATION";
    public record Material(String name,int quantity) { }
    public record Source(String id,String publisher,String url,String pageSha256) { }
    public record Recipe(String schemaVersion,String gameReleaseId,String moveSlug,String label,String nameZh,
                         String status,Integer leaguePoints,List<Material> materials,List<Source> sources,
                         String checkedAt,String reviewStatus,String scope,String catalogSha256) {
        public Recipe { materials=List.copyOf(materials);sources=List.copyOf(sources); }
    }
    private record Excerpt(int offset,int length,String sha256) { }
    private record Observation(String sourceId,int leaguePoints,List<Material> materials,Excerpt excerpt) { }
    private record Row(String moveSlug,String label,String nameZh,List<Observation> observations) { }
    private record Catalog(String schemaVersion,String gameReleaseId,String checkedAt,String scope,String reviewStatus,List<Source> sources,List<Row> records) { }
    private static final class Current { static final MachineRecipeCatalog VALUE=new MachineRecipeCatalog(new DefaultResourceLoader()); }
    private final Map<String,Recipe> recipes;
    public MachineRecipeCatalog(ResourceLoader loader) {
        try(var stream=loader.getResource("classpath:pokemon-machines/recipe-catalog-v1.json").getInputStream()) {
            byte[] bytes=stream.readNBytes(20001);if(bytes.length>20000)throw new IllegalArgumentException("配方资料过大");
            String text=new String(bytes,StandardCharsets.UTF_8).replace("\r\n","\n");
            if(!SHA256.equals(PokeApiClient.digest(text)))throw new IllegalArgumentException("配方资料身份不符");
            var c=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(text,Catalog.class);
            if(!"pokemon-machine-recipe-catalog-v1".equals(c.schemaVersion())||!"pokemon-scarlet-violet".equals(c.gameReleaseId())
                    ||!SCOPE.equals(c.scope())||!REVIEW.equals(c.reviewStatus())||c.records().size()!=4||c.sources().size()!=2)
                throw new IllegalArgumentException("配方资料范围不符");
            Map<String,Recipe> values=new LinkedHashMap<>();
            for(Row row:c.records()) {
                var machine=MachineReferenceCatalog.current().find(row.moveSlug());
                if(machine==null||!machine.label().equals(row.label())||row.observations().size()!=2)throw new IllegalArgumentException("配方与编号不符");
                var first=row.observations().get(0);var second=row.observations().get(1);
                if(!"pokemondb".equals(first.sourceId())||!"serebii".equals(second.sourceId()))throw new IllegalArgumentException("缺少两份登记资料");
                for(Observation o:row.observations()) {
                    if(o.leaguePoints()<1||o.leaguePoints()>100000||o.materials().isEmpty()||o.materials().size()>3
                            ||o.materials().stream().anyMatch(m->m.quantity()<1||m.quantity()>99||!m.name().matches("([A-Za-z]+ )*[A-Za-z]+"))
                            ||o.materials().stream().map(Material::name).distinct().count()!=o.materials().size())throw new IllegalArgumentException("材料数量无效");
                }
                boolean agrees=first.leaguePoints()==second.leaguePoints()&&new HashSet<>(first.materials()).equals(new HashSet<>(second.materials()));
                var recipe=new Recipe(SCHEMA,c.gameReleaseId(),row.moveSlug(),row.label(),row.nameZh(),agrees?"CONSISTENT_REFERENCE":"SOURCE_DISAGREEMENT",
                        agrees?first.leaguePoints():null,agrees?first.materials():List.of(),c.sources(),c.checkedAt(),REVIEW,SCOPE,SHA256);
                if(values.putIfAbsent(row.moveSlug(),recipe)!=null)throw new IllegalArgumentException("重复配方");
            }
            recipes=Map.copyOf(values);
        }catch(Exception invalid){throw new IllegalStateException("固定学习器配方资料校验失败",invalid);}
    }
    public static Recipe read(String moveSlug,String gameReleaseId) {
        if(!PokemonLearnsetFacts.slug(moveSlug)||!"pokemon-scarlet-violet".equals(gameReleaseId))throw new IllegalArgumentException("配方仅支持招式英文标识与朱紫范围");
        var r=Current.VALUE.recipes.get(moveSlug);
        return r!=null?r:new Recipe(SCHEMA,gameReleaseId,moveSlug,null,null,"NOT_CATALOGUED",null,List.of(),List.of(),"2026-10-27",REVIEW,SCOPE,SHA256);
    }
    public static String answer(String moveSlug) {
        var r=read(moveSlug,"pokemon-scarlet-violet");if("NOT_CATALOGUED".equals(r.status()))return "";
        var out=new StringBuilder("\n配方参考（社区资料，待人工及游戏内核验）：");
        if("CONSISTENT_REFERENCE".equals(r.status())) {
            out.append(r.leaguePoints()).append(" LP");
            r.materials().forEach(m->out.append("；").append(m.name()).append(" × ").append(m.quantity()));
            out.append("。\n");
        }else out.append("来源差异待核对，本次不提供材料或数量。\n");
        out.append("\n来源：");
        for(int i=0;i<r.sources().size();i++){var s=r.sources().get(i);if(i>0)out.append(" · ");out.append('[').append(s.publisher()).append("](").append(s.url()).append(')');}
        return out.append("。核对日期 ").append(r.checkedAt()).append("；不证明已解锁、已备齐资源或能学习，不提供获取地点。\n").toString();
    }
}
