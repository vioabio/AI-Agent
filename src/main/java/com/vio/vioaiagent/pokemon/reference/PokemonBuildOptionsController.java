package com.vio.vioaiagent.pokemon.reference;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pokemon/reference/build-options")
@Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.pokemon.reference.enabled",havingValue="true")
public class PokemonBuildOptionsController {
    private final LocalBuildOptions options;
    public PokemonBuildOptionsController(LocalBuildOptions options) { this.options=options; }
    @GetMapping
    public LocalBuildOptions.Page search(@RequestParam String kind, @RequestParam(required=false) Long pokemonId,
            @RequestParam String dataReleaseId, @RequestParam String gameReleaseId,
            @RequestParam(defaultValue="") String query, @RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="50") int limit) {
        return options.search(kind,pokemonId,dataReleaseId,gameReleaseId,query,offset,limit);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public com.vio.vioaiagent.common.Result<Void> invalid(IllegalArgumentException error) {
        return com.vio.vioaiagent.common.Result.error(400,error.getMessage());
    }
}
