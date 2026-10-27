package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/pokemon/reference/mechanics") @Profile("local & !prod & !demo")
@ConditionalOnProperty(name = "vio.pokemon.reference.enabled", havingValue = "true")
public class PokemonMechanicsController {
    private final IndependentMechanicCatalog catalog;
    public PokemonMechanicsController(IndependentMechanicCatalog catalog) { this.catalog = catalog; }
    @GetMapping("/{kind}/{slug}") public ObjectNode read(@PathVariable String kind, @PathVariable String slug, @RequestParam String dataReleaseId, @RequestParam String gameReleaseId) {
        return catalog.read(kind, slug, dataReleaseId, gameReleaseId);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<com.vio.vioaiagent.common.Result<Void>> invalid(IllegalArgumentException error) {
        return org.springframework.http.ResponseEntity.badRequest().body(com.vio.vioaiagent.common.Result.error(400, "机制查询参数或朱紫参考范围无效"));
    }
    @ExceptionHandler(com.vio.vioaiagent.pokemon.service.PokemonResourceNotFoundException.class)
    public org.springframework.http.ResponseEntity<com.vio.vioaiagent.common.Result<Void>> notFound() {
        return org.springframework.http.ResponseEntity.status(404).body(com.vio.vioaiagent.common.Result.error(404, "尚未收录该资源的第二来源机制说明"));
    }
    public static class Tools {
        private final IndependentMechanicCatalog catalog;
        private final RunToolScope scope;
        public Tools(IndependentMechanicCatalog catalog, RunToolScope scope) { this.catalog = catalog; this.scope = scope; }
        @Tool(description = "读取固定Pokémon Showdown第九世代文本中的详细机制，补充PokéAPI短文未给出的门槛/倍率/概率。仅收录torrent、rain-dish、snow-cloak、cursed-body四项特性及ice-beam、water-pulse两项招式。中文整理待人工复核；未收录就说明缺失，不外推完整例外、正式赛制合法性或学习方式。保留当前Run数据和游戏版本。")
        public ObjectNode lookupPokemonMechanics(@ToolParam(description = "ability或move") String kind, @ToolParam(description = "上述已收录资源的英文slug") String slug,
                @ToolParam(description = "当前Run的dataReleaseId") String dataReleaseId, @ToolParam(description = "当前Run的gameReleaseId") String gameReleaseId) {
            if (scope != null) scope.check(dataReleaseId, gameReleaseId);
            return catalog.read(kind, slug, dataReleaseId, gameReleaseId);
        }
    }
}
