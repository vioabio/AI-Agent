package com.vio.vioaiagent.pokemon.reference;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class PokemonReferenceTools {
    private final LocalPokeApiRepository repository;
    private final RunToolScope scope;
    private final LocalResourceReferences resources;
    public PokemonReferenceTools(LocalPokeApiRepository repository) { this(repository,null); }
    public PokemonReferenceTools(LocalPokeApiRepository repository,RunToolScope scope) { this(repository,scope,null); }
    public PokemonReferenceTools(LocalPokeApiRepository repository,RunToolScope scope,LocalResourceReferences resources) { this.repository=repository;this.scope=scope;this.resources=resources; }
    @Tool(description="读取真实 PokéAPI 宝可梦资料，包括中文名称、属性、种族值、特性和朱紫招式列表。支持中文、英文或全国编号。必须使用当前 Run 的数据版本，参考库版本为 pokeapi-reference-v1。不是排名或赛制合法性证明。")
    public LocalPokeApiRepository.Detail lookupPokemonReference(
            @ToolParam(description="宝可梦中文/英文名或全国编号") String query,
            @ToolParam(description="当前 Run 的 dataReleaseId，不能自行改变版本") String dataReleaseId) {
        if(scope!=null)scope.check(dataReleaseId,null);
        try {
            return repository.detail(query,dataReleaseId);
        } catch(com.vio.vioaiagent.pokemon.service.PokemonResourceNotFoundException missing) {
            throw new com.vio.vioaiagent.pokemon.service.PokemonResourceNotFoundException("POKEMON_REFERENCE_NOT_FOUND");
        } catch(IllegalArgumentException invalid) {
            if ("未识别该中文名称，请尝试英文名称或全国编号".equals(invalid.getMessage()))
                throw new com.vio.vioaiagent.pokemon.service.PokemonResourceNotFoundException("POKEMON_REFERENCE_NOT_FOUND");
            throw invalid;
        }
    }
    @Tool(description="读取 PokéAPI 招式、特性、道具或性格资料。仅供参考，不证明特定版本合法性。")
    public java.util.Map<String,Object> lookupPokemonResource(
            @ToolParam(description="move、ability、item 或 nature") String kind,
            @ToolParam(description="英文 slug，例如 ice-beam、torrent、leftovers、modest") String slug,
            @ToolParam(description="当前 Run 的 dataReleaseId") String dataReleaseId) {
        if(scope!=null)scope.check(dataReleaseId,null);
        if(resources!=null&&java.util.Set.of("ability","move","nature").contains(kind))return resources.read(kind,slug,dataReleaseId);
        return repository.resource(kind,slug,dataReleaseId);
    }
}
