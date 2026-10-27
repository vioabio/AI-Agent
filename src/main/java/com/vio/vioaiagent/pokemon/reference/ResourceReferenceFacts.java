package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;

/** Flat, bounded source projection. A digest detects changes; it is not an upstream signature. */
public final class ResourceReferenceFacts {
    public static final String SCHEMA="pokemon-resource-facts-v1";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> FIELDS=Set.of("schemaVersion","kind","slug","resourceId","dataReleaseId","sourceUrl","checksum","retrievedAt","sourceKind","fieldScope","nameZh",
            "typeKey","damageClass","power","accuracy","pp","priority","increasedStat","decreasedStat","neutralNature","descriptionLanguage","descriptionVersionGroup","descriptionExcerpt","descriptionSha256","descriptionTruncated","explanationZh","explanationStatus","projectionSha256");
    private static final Set<String> TYPES=Set.of("normal","fire","water","electric","grass","ice","fighting","poison","ground","flying","psychic","bug","rock","ghost","dragon","dark","steel","fairy");
    private static final Set<String> STATS=Set.of("attack","defense","special-attack","special-defense","speed");
    private ResourceReferenceFacts(){ }
    public static ObjectNode validate(JsonNode root) {
        if(!root.isObject()||root.size()>FIELDS.size())throw new IllegalArgumentException("资源事实格式无效");
        root.fieldNames().forEachRemaining(k->{if(!FIELDS.contains(k))throw new IllegalArgumentException("未知资源事实字段");});
        for(String k:List.of("schemaVersion","kind","slug","dataReleaseId","sourceUrl","checksum","retrievedAt","sourceKind","fieldScope","nameZh","projectionSha256"))text(root,k,1000);
        String kind=root.path("kind").asText(),slug=root.path("slug").asText();
        if(!SCHEMA.equals(root.path("schemaVersion").asText())||!Set.of("ability","move","nature").contains(kind)
                ||!slug.matches("[a-z0-9][a-z0-9-]{0,79}")||!"pokeapi-reference-v1".equals(root.path("dataReleaseId").asText())
                ||!Set.of("BUNDLED_PUBLIC_REFERENCE","LIVE_CACHE_REFERENCE").contains(root.path("sourceKind").asText())
                ||!"UPSTREAM_REFERENCE_NOT_PATCH_FROZEN".equals(root.path("fieldScope").asText()))throw new IllegalArgumentException("资源事实身份或范围无效");
        number(root,"resourceId",1,Integer.MAX_VALUE,false);
        String url=root.path("sourceUrl").asText(),prefix="https://pokeapi.co/api/v2/"+kind+"/";
        if(!Set.of(prefix+slug+"/",prefix+root.path("resourceId").asInt()+"/").contains(url)
                ||!root.path("checksum").asText().matches("sha256:[a-f0-9]{64}")
                ||Instant.parse(root.path("retrievedAt").asText()).isAfter(Instant.now().plusSeconds(300)))throw new IllegalArgumentException("资源来源无效");
        if(kind.equals("move")) {
            if(!TYPES.contains(root.path("typeKey").asText())||!Set.of("physical","special","status").contains(root.path("damageClass").asText()))throw new IllegalArgumentException("招式类型无效");
            number(root,"power",0,1000,true);number(root,"accuracy",0,100,true);number(root,"pp",1,100,false);number(root,"priority",-7,7,false);
        } else for(String k:List.of("typeKey","damageClass","power","accuracy","pp","priority"))if(root.has(k))throw new IllegalArgumentException("资源字段类别不符");
        if(kind.equals("nature")) {
            if(!root.path("neutralNature").isBoolean()||!root.has("increasedStat")||!root.has("decreasedStat"))throw new IllegalArgumentException("性格字段缺失");
            boolean neutral=root.path("neutralNature").asBoolean();
            if(neutral?!(root.path("increasedStat").isNull()&&root.path("decreasedStat").isNull())
                    :!(STATS.contains(root.path("increasedStat").asText())&&STATS.contains(root.path("decreasedStat").asText())&&!root.path("increasedStat").equals(root.path("decreasedStat"))))throw new IllegalArgumentException("性格能力字段无效");
        }else for(String k:List.of("increasedStat","decreasedStat","neutralNature"))if(root.has(k))throw new IllegalArgumentException("资源字段类别不符");
        boolean description=root.has("descriptionExcerpt");
        if(description) {
            text(root,"descriptionExcerpt",1000);text(root,"descriptionSha256",100);
            if(!"scarlet-violet".equals(root.path("descriptionVersionGroup").asText())
                    ||!Set.of("en","zh-hans").contains(root.path("descriptionLanguage").asText())||!root.path("descriptionTruncated").isBoolean()
                    ||!root.path("descriptionSha256").asText().matches("sha256:[a-f0-9]{64}")
                    ||root.path("descriptionLanguage").asText().equals("en")&&root.path("descriptionExcerpt").asText().split("\\s+").length>25)throw new IllegalArgumentException("朱紫说明范围无效");
        }else for(String k:List.of("descriptionLanguage","descriptionVersionGroup","descriptionSha256","descriptionTruncated","explanationZh","explanationStatus"))if(root.has(k))throw new IllegalArgumentException("机制说明缺少来源");
        if(root.has("explanationZh")) {
            text(root,"explanationZh",500);
            if(!"PROJECT_PARAPHRASE_PENDING_HUMAN_REVIEW".equals(root.path("explanationStatus").asText()))throw new IllegalArgumentException("中文释义状态无效");
        }else if(root.has("explanationStatus"))throw new IllegalArgumentException("中文释义缺失");
        if(!checksum((ObjectNode)root).equals(root.path("projectionSha256").asText()))throw new IllegalArgumentException("资源事实摘要不符");
        return ((ObjectNode)root).deepCopy();
    }
    public static ObjectNode seal(ObjectNode root) {root.put("projectionSha256",checksum(root));return validate(root);}
    private static String checksum(ObjectNode root) {
        var sorted=new TreeMap<String,JsonNode>();root.fields().forEachRemaining(e->{if(!e.getKey().equals("projectionSha256"))sorted.put(e.getKey(),e.getValue());});
        try{return PokeApiClient.digest(JSON.writeValueAsString(sorted));}catch(Exception invalid){throw new IllegalArgumentException("资源摘要编码失败",invalid);}
    }
    private static void text(JsonNode root,String field,int limit) {
        if(!root.path(field).isTextual()||root.path(field).asText().isBlank()||root.path(field).asText().length()>limit||root.path(field).asText().contains("\u0000"))throw new IllegalArgumentException("资源文字字段无效");
    }
    private static void number(JsonNode root,String field,int min,int max,boolean nullable) {
        var n=root.get(field);if(n==null||!(nullable&&n.isNull()||n.isIntegralNumber()&&n.canConvertToInt()&&n.intValue()>=min&&n.intValue()<=max))throw new IllegalArgumentException("资源数值字段无效");
    }
}
