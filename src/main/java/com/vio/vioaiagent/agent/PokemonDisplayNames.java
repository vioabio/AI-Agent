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
    static String nature(String slug){for(int i=0;i<NATURES.length;i++)if(NATURES[i].equals(slug))return NAMES[i];return "性格名称待核对";}
    static String label(String kind,String slug){if(slug==null||slug.isBlank())return "未明确";return LABELS.getOrDefault(kind+":"+slug,"中文名称待核对");}
    private static Map<String,String> load() {
        try(var input=PokemonDisplayNames.class.getResourceAsStream("/pokemon-build-options/catalog-v1.json");var metadata=PokemonDisplayNames.class.getResourceAsStream("/pokemon-build-options/catalog-v1.meta.json")) {
            String payload=new String(input.readNBytes(1_000_001),StandardCharsets.UTF_8).replace("\r\n","\n");var json=new ObjectMapper();
            if(payload.length()>1_000_000||!PokeApiClient.digest(payload).equals(json.readTree(metadata).path("sha256").asText()))return Map.of();
            var result=new HashMap<String,String>();for(var row:json.readTree(payload).path("records"))result.put(row.path("kind").asText()+":"+row.path("slug").asText(),row.path("nameZh").asText());return Map.copyOf(result);
        }catch(Exception unavailable){return Map.of();}
    }
}
