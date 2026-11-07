package com.vio.vioaiagent.pokemon.reference;

import org.springframework.ai.tool.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/pokemon/reference/learnsets") @Profile("local & !prod & !demo")
@ConditionalOnProperty(name = "vio.pokemon.reference.enabled", havingValue = "true")
public class PokemonLearnsetController {
    private final LocalLearnsetReferences references;
    public PokemonLearnsetController(LocalLearnsetReferences references) { this.references = references; }
    @GetMapping("/{pokemonId}/{moveSlug}")
    public PokemonLearnsetFacts read(@PathVariable long pokemonId, @PathVariable String moveSlug,
            @RequestParam String dataReleaseId, @RequestParam String gameReleaseId) {
        return references.read(pokemonId, moveSlug, dataReleaseId, gameReleaseId);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<com.vio.vioaiagent.common.Result<Void>> invalid(IllegalArgumentException error) {
        return org.springframework.http.ResponseEntity.badRequest().body(com.vio.vioaiagent.common.Result.error(400, "学习途径查询参数无效"));
    }
    public static class Tools {
        private final LocalLearnsetReferences references;
        private final RunToolScope scope;
        public Tools(LocalLearnsetReferences references, RunToolScope scope) { this.references = references; this.scope = scope; }
        @Tool(description = "查询指定成员和招式在朱紫快照中记录的学习方式及等级。先从真实图鉴取得成员formId和招式英文slug，再查询；保留当前Run的数据/游戏版本。未记录不代表不能学习；不证明四招配置、赛事合法性，不提供TM编号、地点或完整遗传操作。")
        public PokemonLearnsetFacts lookupPokemonLearnset(
                @ToolParam(description = "真实图鉴中的成员formId，不能用物种全国编号替代不同形态ID") long pokemonId,
                @ToolParam(description = "实际招式英文slug，例如ice-beam") String moveSlug,
                @ToolParam(description = "当前Run的dataReleaseId") String dataReleaseId,
                @ToolParam(description = "当前Run的gameReleaseId") String gameReleaseId) {
            if (scope != null) scope.check(dataReleaseId, gameReleaseId);
            return references.read(pokemonId, moveSlug, dataReleaseId, gameReleaseId);
        }
    }
}
