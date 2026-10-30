package com.vio.vioaiagent.tools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.vio.vioaiagent.config.ResourceDownloadProperties;

/**
 * 集中的工具注册类
 */
@Configuration
public class ToolRegistration {

    /** 可选治理网关；未加载治理模块时保留旧工具注册行为。 */
    @Autowired(required = false)
    private com.vio.vioaiagent.tools.governance.ToolExecutionGateway governanceGateway;

    @Value("${search-api.api-key:}")
    private String searchApiKey;

    @Value("${vio.tools.search.enabled:true}")
    private boolean searchEnabled = true;

    @Value("${vio.tools.high-risk.enabled:false}")
    private boolean highRiskToolsEnabled;

    @Autowired(required = false)
    private ResourceDownloadProperties resourceDownloadProperties;

    @Autowired(required = false)
    private com.vio.vioaiagent.pokemon.reference.LocalPokeApiRepository referenceRepository;
    @Autowired(required = false)
    private com.vio.vioaiagent.pokemon.reference.LocalResourceReferences resourceReferences;
    @Autowired(required = false)
    private com.vio.vioaiagent.pokemon.reference.LocalLearnsetReferences learnsetReferences;
    @Autowired(required = false)
    private com.vio.vioaiagent.pokemon.reference.IndependentMechanicCatalog independentMechanics;
    @Autowired(required = false)
    private com.vio.vioaiagent.pokemon.reference.LocalBuildCheckService buildChecks;
    @Autowired(required = false)
    private com.vio.vioaiagent.pokemon.reference.LocalMachineReferences machineReferences;
    @Autowired(required = false)
    private com.vio.vioaiagent.pokemon.engine.LocalDamageEngine damageEngine;
    @Autowired(required = false)
    private com.vio.vioaiagent.pokemon.guides.LocalGuideSearch guideSearch;
    @Autowired(required = false)
    private com.vio.vioaiagent.pokemon.reference.RunToolScope toolScope;
    @Autowired(required = false)
    private com.vio.vioaiagent.pokemon.team.TeamWorkflowService teamWorkflow;

    @Bean
    public ToolCallback[] allTools() {
        ToolCallback[] pokemonTools = createPokemonTools();
        // 默认只暴露宝可梦事实查询和只读搜索；终端、文件写入、下载、抓取等
        // 高风险能力必须显式打开开关后才加入 Agent 工具集合。
        if (!highRiskToolsEnabled) {
            return govern(pokemonTools);
        }

        ToolCallback[] adminTools = createAdminTools();
        ToolCallback[] merged = new ToolCallback[pokemonTools.length + adminTools.length];
        System.arraycopy(pokemonTools, 0, merged, 0, pokemonTools.length);
        System.arraycopy(adminTools, 0, merged, pokemonTools.length, adminTools.length);
        return govern(merged);
    }

    /** 玩家主链工具白名单：只读宝可梦事实和搜索。 */
    @Bean(name = "pokemonTools")
    public ToolCallback[] pokemonTools() {
        return govern(createPokemonTools());
    }

    /** 管理/高风险工具集合；默认开关关闭时返回空数组。 */
    @Bean(name = "adminTools")
    public ToolCallback[] adminTools() {
        return highRiskToolsEnabled ? govern(createAdminTools()) : new ToolCallback[0];
    }

    private ToolCallback[] createPokemonTools() {
        PokeTypeTool pokeTypeTool = new PokeTypeTool();
        // 终止工具属于控制面低风险能力；即使高风险工具关闭，Agent 也能显式结束。
        ToolCallback[] base = searchEnabled && WebSearchTool.isConfigured(searchApiKey)
                ? ToolCallbacks.from(pokeTypeTool, new WebSearchTool(searchApiKey), new TerminateTool())
                : ToolCallbacks.from(pokeTypeTool, new TerminateTool());
        java.util.List<ToolCallback> tools = new java.util.ArrayList<>(java.util.Arrays.asList(base));
        if (teamWorkflow != null) tools.addAll(java.util.Arrays.asList(ToolCallbacks.from(teamWorkflow)));
        if (referenceRepository != null) tools.addAll(java.util.Arrays.asList(ToolCallbacks.from(new com.vio.vioaiagent.pokemon.reference.PokemonReferenceTools(referenceRepository,toolScope,resourceReferences))));
        if (learnsetReferences != null) tools.addAll(java.util.Arrays.asList(ToolCallbacks.from(new com.vio.vioaiagent.pokemon.reference.PokemonLearnsetController.Tools(learnsetReferences,toolScope))));
        if (independentMechanics != null) tools.addAll(java.util.Arrays.asList(ToolCallbacks.from(new com.vio.vioaiagent.pokemon.reference.PokemonMechanicsController.Tools(independentMechanics,toolScope))));
        if (buildChecks != null) tools.addAll(java.util.Arrays.asList(ToolCallbacks.from(new com.vio.vioaiagent.pokemon.reference.PokemonBuildCheckController.Tools(buildChecks,toolScope))));
        if (buildChecks != null) tools.addAll(java.util.Arrays.asList(ToolCallbacks.from(new com.vio.vioaiagent.pokemon.reference.PokemonTeamBuildCheckController.Tools(buildChecks,toolScope))));
        if (machineReferences != null) tools.addAll(java.util.Arrays.asList(ToolCallbacks.from(new com.vio.vioaiagent.pokemon.reference.PokemonMachineController.Tools(machineReferences,toolScope))));
        if (referenceRepository != null) tools.addAll(java.util.Arrays.asList(ToolCallbacks.from(new com.vio.vioaiagent.pokemon.reference.TeamReferenceTools(referenceRepository,toolScope))));
        if (damageEngine != null) tools.addAll(java.util.Arrays.asList(ToolCallbacks.from(new com.vio.vioaiagent.pokemon.engine.DamageEngineController.Tools(damageEngine,toolScope))));
        if (guideSearch != null) tools.addAll(java.util.Arrays.asList(ToolCallbacks.from(new com.vio.vioaiagent.pokemon.guides.LocalGuideController.Tools(guideSearch,toolScope))));
        return tools.toArray(ToolCallback[]::new);
    }

    private ToolCallback[] createAdminTools() {
        return ToolCallbacks.from(
                new FileOperationTool(),
                new WebScrapingTool(),
                resourceDownloadProperties == null
                        ? new ResourceDownloadTool() : new ResourceDownloadTool(resourceDownloadProperties),
                new TerminalOperationTool(),
                new PDFGenerationTool(),
                new TerminateTool()
        );
    }

    private ToolCallback[] govern(ToolCallback[] callbacks) {
        var adapted=java.util.Arrays.stream(callbacks).map(com.vio.vioaiagent.tools.governance.DamageToolSchema::adapt)
                .map(com.vio.vioaiagent.tools.governance.ToolSchemaReferences::adapt).toArray(ToolCallback[]::new);
        return governanceGateway == null ? adapted : governanceGateway.wrap(adapted);
    }
}
