package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.*;
import org.springframework.core.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fixed public source triples. No source URL is followed at runtime. */
public final class MachineReferenceCatalog {
    public static final String SHA256="sha256:4184a7e709d8acaf03cba79c745f1b8b0ddb6c67ac56c80acea69fc5685b09a2";
    public record Machine(long machineId,String itemSlug,String label,String moveUrl,String machineUrl,String itemUrl,
                          String moveChecksum,String machineChecksum,String itemChecksum) { }
    private static final class Current { static final MachineReferenceCatalog VALUE=new MachineReferenceCatalog(new ObjectMapper(),new DefaultResourceLoader()); }
    public static MachineReferenceCatalog current(){return Current.VALUE;}
    private final Map<String,Machine> rows;
    public MachineReferenceCatalog(ObjectMapper json,ResourceLoader loader){
        try {
            String text=read(loader,"catalog-v1.json",16000);
            if(!SHA256.equals(PokeApiClient.digest(text)))throw new IllegalArgumentException("目录身份不符");
            var root=json.readTree(text);if(!"pokemon-machine-catalog-v1".equals(root.path("schemaVersion").asText())||root.path("records").size()!=4)throw new IllegalArgumentException();
            Map<String,Machine> result=new LinkedHashMap<>();
            for(var row:root.path("records")){
                String slug=row.path("moveSlug").asText(),item=row.path("itemSlug").asText();long id=row.path("machineId").asLong();
                if(!Set.of("water-pulse","ice-beam","protect","hydro-pump").contains(slug)||!item.matches("tm[0-9]{2,3}")||id<1||id>100000)throw new IllegalArgumentException();
                var move=source(json,loader,"move-"+slug+".json",row.path("moveSha256").asText());
                var machine=source(json,loader,"machine-"+id+".json",row.path("machineSha256").asText());
                var resource=source(json,loader,"item-"+item+".json",row.path("itemSha256").asText());
                String machineUrl="https://pokeapi.co/api/v2/machine/"+id+"/";
                if(!slug.equals(move.path("name").asText())||move.path("id").asLong()<1||machine.path("id").asLong()!=id||!item.equals(resource.path("name").asText())||resource.path("id").asLong()<1
                        ||!slug.equals(machine.path("move").path("name").asText())||!item.equals(machine.path("item").path("name").asText())
                        ||!sv(machine.path("version_group"))||!machine.path("move").path("url").asText().equals("https://pokeapi.co/api/v2/move/"+move.path("id").asLong()+"/")
                        ||!machine.path("item").path("url").asText().equals("https://pokeapi.co/api/v2/item/"+resource.path("id").asLong()+"/")
                        ||!oneLink(move.path("machines"),machineUrl)||!oneLink(resource.path("machines"),machineUrl))throw new IllegalArgumentException("来源映射不一致");
                var entry=new Machine(id,item,String.format(Locale.ROOT,"TM%03d",Integer.parseInt(item.substring(2))),"https://pokeapi.co/api/v2/move/"+slug+"/",machineUrl,"https://pokeapi.co/api/v2/item/"+item+"/",row.path("moveSha256").asText(),row.path("machineSha256").asText(),row.path("itemSha256").asText());
                if(result.putIfAbsent(slug,entry)!=null)throw new IllegalArgumentException();
            }
            rows=Map.copyOf(result);
        }catch(Exception invalid){throw new IllegalStateException("固定学习器资料校验失败",invalid);}
    }
    private static boolean sv(JsonNode version){return "scarlet-violet".equals(version.path("name").asText())&&"https://pokeapi.co/api/v2/version-group/25/".equals(version.path("url").asText());}
    private static boolean oneLink(JsonNode links,String url){int count=0;for(var row:links)if(sv(row.path("version_group"))){if(!url.equals(row.path("machine").path("url").asText()))return false;count++;}return count==1;}
    private static JsonNode source(ObjectMapper json,ResourceLoader loader,String name,String hash)throws Exception {String text=read(loader,"source/"+name,2_000_000);if(!PokeApiClient.digest(text).equals(hash))throw new IllegalArgumentException("来源摘要不符");return json.readTree(text);}
    private static String read(ResourceLoader loader,String name,int max)throws Exception {try(var stream=loader.getResource("classpath:pokemon-machines/"+name).getInputStream()){byte[] bytes=stream.readNBytes(max+1);if(bytes.length>max)throw new IllegalArgumentException("来源过大");return new String(bytes,StandardCharsets.UTF_8);}}
    public Machine find(String slug){return rows.get(slug);}
}
