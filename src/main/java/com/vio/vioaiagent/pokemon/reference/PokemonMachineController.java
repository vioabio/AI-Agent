package com.vio.vioaiagent.pokemon.reference;

import org.springframework.web.bind.annotation.*;
import org.springframework.ai.tool.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestController @RequestMapping("/pokemon/reference/machines") @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.pokemon.reference.enabled",havingValue="true")
public class PokemonMachineController {
    private final LocalMachineReferences references;
    public PokemonMachineController(LocalMachineReferences references){this.references=references;MachineCraftingGuide.read("pokemon-scarlet-violet");MachineRecipeCatalog.read("protect","pokemon-scarlet-violet");MachineAcquisitionCatalog.read("protect","pokemon-scarlet-violet");}
    @GetMapping("/acquisition/{moveSlug}") public MachineAcquisitionCatalog.Reference acquisition(@PathVariable String moveSlug,@RequestParam String gameReleaseId){return MachineAcquisitionCatalog.read(moveSlug,gameReleaseId);}
    @GetMapping("/recipes/{moveSlug}") public MachineRecipeCatalog.Recipe recipe(@PathVariable String moveSlug,@RequestParam String gameReleaseId){return MachineRecipeCatalog.read(moveSlug,gameReleaseId);}
    @GetMapping("/crafting-guide") public MachineCraftingGuide.Guide craftingGuide(@RequestParam String gameReleaseId){return MachineCraftingGuide.read(gameReleaseId);}
    @GetMapping("/{pokemonId}/{moveSlug}") public MachineReferenceFacts.Report read(@PathVariable long pokemonId,@PathVariable String moveSlug,@RequestParam String dataReleaseId,@RequestParam String gameReleaseId){return references.read(pokemonId,moveSlug,dataReleaseId,gameReleaseId);}
    @ExceptionHandler(IllegalArgumentException.class) public org.springframework.http.ResponseEntity<com.vio.vioaiagent.common.Result<Void>> invalid(){return org.springframework.http.ResponseEntity.badRequest().body(com.vio.vioaiagent.common.Result.error(400,"学习器查询参数或朱紫参考范围无效"));}
    public static class Tools {
        private final LocalMachineReferences references;private final RunToolScope scope;
        public Tools(LocalMachineReferences references,RunToolScope scope){this.references=references;this.scope=scope;}
        @Tool(description="核对一个真实成员和招式的朱紫学习器编号。先核对成员学习记录中的machine途径，再查固定4项资料中的move/machine/item映射。仅支持water-pulse、ice-beam、protect、hydro-pump编号；编号匹配后由程序附固定社区配方和共同大区域参考，来源差异不提供材料数量。不提供精确位置、剧情解锁、遗传或联合合法性，不保存队伍。")
        public MachineReferenceFacts.Report lookupPokemonMachine(@ToolParam long pokemonId,@ToolParam String moveSlug,@ToolParam String dataReleaseId,@ToolParam String gameReleaseId){if(scope!=null)scope.check(dataReleaseId,gameReleaseId);return references.read(pokemonId,moveSlug,dataReleaseId,gameReleaseId);}
    }
}
