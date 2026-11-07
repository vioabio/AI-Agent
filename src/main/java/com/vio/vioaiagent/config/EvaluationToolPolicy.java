package com.vio.vioaiagent.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;

/** Process-level development ablation. No request parameter can change the active mode. */
@Component
public final class EvaluationToolPolicy {
    public enum Mode { OFF, MODEL_ONLY, GUIDES_ONLY, READ_ONLY_TOOLS }
    private static final Set<String> READ_ONLY=Set.of("searchPokemonGuides","lookupPokemonReference","lookupPokemonResource","lookupPokemonLearnset","lookupPokemonMachine","lookupPokemonMechanics","checkPokemonBuild","checkPokemonTeamBuild",
            "lookupPokemonTeamReference","calculateBattleDamage","checkTypeEffectiveness","getTypeChart");
    private final Mode mode;
    @org.springframework.beans.factory.annotation.Autowired
    public EvaluationToolPolicy(Environment environment) {
        try {mode=Mode.valueOf(environment.getProperty("vio.evaluation.tool-mode","OFF").trim().toUpperCase(Locale.ROOT).replace('-','_'));}
        catch(IllegalArgumentException failure){throw new IllegalArgumentException("未知评测工具模式");}
        if(active()) {
            var profiles=Set.of(environment.getActiveProfiles());
            if(!profiles.contains("local")||!profiles.contains("evaluation")||profiles.stream().anyMatch(Set.of("prod","production","demo")::contains))
                throw new IllegalStateException("评测工具模式仅支持显式 local,evaluation 配置");
            if(!environment.getProperty("vio.local-accounts.enabled",Boolean.class,false)
                    ||environment.getProperty("vio.agent.v1.enabled",Boolean.class,false)
                    ||environment.getProperty("vio.run.durable-resume",Boolean.class,false)
                    ||environment.getProperty("spring.ai.mcp.client.enabled",Boolean.class,false)
                    ||environment.getProperty("vio.tools.high-risk.enabled",Boolean.class,false))
                throw new IllegalStateException("评测模式要求本地账号，关闭版本化主链、持久恢复、MCP及高风险工具");
        }
    }
    private EvaluationToolPolicy(Mode mode){this.mode=mode;}
    public static EvaluationToolPolicy off(){return new EvaluationToolPolicy(Mode.OFF);}
    public boolean active(){return mode!=Mode.OFF;}
    public Mode mode(){return mode;}
    public ToolCallback[] select(ToolCallback[] registered) {
        if(!active())return registered.clone();
        if(mode==Mode.MODEL_ONLY)return new ToolCallback[0];
        var selected=Arrays.stream(registered).filter(tool->mode==Mode.GUIDES_ONLY
                ? "searchPokemonGuides".equals(tool.getToolDefinition().name()):READ_ONLY.contains(tool.getToolDefinition().name())).toArray(ToolCallback[]::new);
        var names=Arrays.stream(selected).map(tool->tool.getToolDefinition().name()).toList();
        Set<String> required=mode==Mode.GUIDES_ONLY?Set.of("searchPokemonGuides"):Set.of("searchPokemonGuides","lookupPokemonReference","calculateBattleDamage");
        if(!names.containsAll(required))throw new IllegalStateException("评测所需只读工具未完整注册");
        return selected;
    }
    public Map<String,Object> describe(ToolCallback[] registered) {
        return Map.of("schemaVersion","evaluation-tool-policy-v1","mode",mode.name(),"active",active(),
                "effectiveTools",Arrays.stream(select(registered)).map(tool->tool.getToolDefinition().name()).sorted().toList(),
                "scope",active()?"DEVELOPMENT_PROCESS_TOOL_SUBSET_NOT_SEMANTIC_ACCEPTANCE":"NORMAL_APPLICATION",
                "mcpProviderAllowed",!active());
    }
}
