package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.PokeApiClient;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Display-only names from the same checksum-verified public catalog as the editor. */
final class PokemonDisplayNames {
    private static final Map<String,String> LABELS=load();
    private static final String[] NATURES={"hardy","lonely","brave","adamant","naughty","bold","docile","relaxed","impish","lax","timid","hasty","serious","jolly","naive","modest","mild","quiet","bashful","rash","calm","gentle","sassy","careful","quirky"};
    private static final String[] NAMES={"勤奋","怕寂寞","勇敢","固执","顽皮","大胆","坦率","悠闲","淘气","乐天","胆小","急躁","认真","爽朗","天真","内敛","慢吞吞","冷静","害羞","马虎","温和","温顺","自大","慎重","浮躁"};
    private PokemonDisplayNames() { }
    private static String key(String value){return value==null?"":value.strip().toLowerCase(Locale.ROOT).replace(' ','-').replace("'","").replace("’","");}
    static String species(String value){String normalized=key(value);return com.vio.vioaiagent.pokemon.reference.PokemonNameIndex.all().stream()
            .filter(e->key(e.slug()).equals(normalized)||e.aliases().stream().anyMatch(a->key(a).equals(normalized)))
            .map(com.vio.vioaiagent.pokemon.reference.PokemonNameIndex.Entry::nameZh).findFirst().orElse(value+"（形态中文名待核对）");}
    static String calculationLabel(String kind,String value){if(value==null||value.isBlank())return "未明确";return LABELS.getOrDefault(kind+":"+key(value),value+"（中文名待核对）");}
    static String type(String value){return switch(key(value)){
        case "normal"->"一般";case "fire"->"火";case "water"->"水";case "electric"->"电";case "grass"->"草";case "ice"->"冰";
        case "fighting"->"格斗";case "poison"->"毒";case "ground"->"地面";case "flying"->"飞行";case "psychic"->"超能力";
        case "bug"->"虫";case "rock"->"岩石";case "ghost"->"幽灵";case "dragon"->"龙";case "dark"->"恶";case "steel"->"钢";
        case "fairy"->"妖精";case "stellar"->"星晶";default->value+"（属性名待核对）";};}
    static String condition(String value){if(value==null||value.isBlank())return "无";return switch(value){
        case "Singles"->"单打";case "Doubles"->"双打";case "Sun","Harsh Sunshine"->value.equals("Sun")?"晴天":"大日照";
        case "Rain","Heavy Rain"->value.equals("Rain")?"下雨":"大雨";case "Sand"->"沙暴";case "Hail"->"冰雹";case "Snow"->"下雪";
        case "Strong Winds"->"乱流";case "Electric"->"电气场地";case "Grassy"->"青草场地";case "Misty"->"薄雾场地";case "Psychic"->"精神场地";
        case "brn"->"灼伤";case "par"->"麻痹";case "slp"->"睡眠";case "frz"->"冰冻";case "psn"->"中毒";case "tox"->"剧毒";
        default->value+"（中文名待核对）";};}
    static String nature(String slug){for(int i=0;i<NATURES.length;i++)if(NATURES[i].equals(key(slug)))return NAMES[i];return "性格名称待核对";}
    static String label(String kind,String slug){if(slug==null||slug.isBlank())return "未明确";return LABELS.getOrDefault(kind+":"+slug,"中文名称待核对");}
    private static Map<String,String> load() {
        try(var input=PokemonDisplayNames.class.getResourceAsStream("/pokemon-build-options/catalog-v1.json");var metadata=PokemonDisplayNames.class.getResourceAsStream("/pokemon-build-options/catalog-v1.meta.json")) {
            String payload=new String(input.readNBytes(1_000_001),StandardCharsets.UTF_8).replace("\r\n","\n");var json=new ObjectMapper();
            if(payload.length()>1_000_000||!PokeApiClient.digest(payload).equals(json.readTree(metadata).path("sha256").asText()))return Map.of();
            var result=new HashMap<String,String>();for(var row:json.readTree(payload).path("records"))result.put(row.path("kind").asText()+":"+row.path("slug").asText(),row.path("nameZh").asText());return Map.copyOf(result);
        }catch(Exception unavailable){return Map.of();}
    }
}
