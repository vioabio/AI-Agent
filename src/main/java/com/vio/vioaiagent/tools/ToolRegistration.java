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

    @Value("${search-api.api-key}")
    private String searchApiKey;

    @Value("${vio.tools.high-risk.enabled:false}")
    private boolean highRiskToolsEnabled;

    @Autowired(required = false)
    private ResourceDownloadProperties resourceDownloadProperties;

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
        WebSearchTool webSearchTool = new WebSearchTool(searchApiKey);
        PokeTypeTool pokeTypeTool = new PokeTypeTool();
        // 终止工具属于控制面低风险能力；即使高风险工具关闭，Agent 也能显式结束。
        return ToolCallbacks.from(pokeTypeTool, webSearchTool, new TerminateTool());
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
        return governanceGateway == null ? callbacks : governanceGateway.wrap(callbacks);
    }
}
