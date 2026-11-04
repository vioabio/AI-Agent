package com.vio.vioaiagent.pokemon.reference;

import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.Map;

@RestController @RequestMapping("/pokemon/reference") @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.pokemon.reference.enabled", havingValue="true")
public class PokemonReferenceController {
    private final LocalPokeApiRepository repository;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private LocalResourceReferences resources;
    public PokemonReferenceController(LocalPokeApiRepository repository) { this.repository=repository; }
    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<com.vio.vioaiagent.common.Result<Void>> invalidInput(IllegalArgumentException error) {
        return org.springframework.http.ResponseEntity.badRequest().body(com.vio.vioaiagent.common.Result.error(400,
                error.getMessage() == null ? "资料查询参数无效" : error.getMessage()));
    }
    @GetMapping("/status") public Map<String,Object> status() {
        return Map.of("enabled",true,"dataReleaseId",LocalPokeApiRepository.RELEASE,
                "status","LOCAL_REFERENCE","sourceUrl","https://pokeapi.co/", "limitation","通用参考资料，不代表排位合法性或实时强度");
    }
    @GetMapping("/pokemon/{query}") public LocalPokeApiRepository.Detail detail(@PathVariable String query, @RequestParam String dataReleaseId) {
        return repository.detail(query, dataReleaseId);
    }
    @GetMapping("/names") public java.util.List<PokemonNameIndex.Entry> names(
            @RequestParam(defaultValue="") String query, @RequestParam(defaultValue="30") int limit) {
        return PokemonNameIndex.search(query, limit);
    }
    @GetMapping("/resources/{kind}/{slug}") public Map<String,Object> resource(@PathVariable String kind, @PathVariable String slug, @RequestParam String dataReleaseId) {
        if(resources!=null&&java.util.Set.of("ability","move","nature").contains(kind))return resources.read(kind,slug,dataReleaseId);
        return repository.resource(kind,slug,dataReleaseId);
    }
}
