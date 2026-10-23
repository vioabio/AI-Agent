package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.agent.*;
import com.vio.vioaiagent.pokemon.reference.TeamReferenceTools;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import java.security.MessageDigest;
import java.util.*;

/** Local authenticated diagnostics. Reports configured identity, never credentials. */
@RestController @Profile("local & !prod & !demo")
public class LocalRuntimeController {
    private final Environment environment;
    private final ObjectProvider<ChatModel> models;
    private final ToolCallback[] tools;
    public LocalRuntimeController(Environment environment, ObjectProvider<ChatModel> models,
                                  @Qualifier("allTools") ToolCallback[] tools) {
        this.environment = environment; this.models = models; this.tools = tools;
    }
    @GetMapping("/chat/runtime")
    public Map<String, Object> runtime() {
        ChatModel model = models.getIfAvailable();
        String configuredModel = model == null || model.getDefaultOptions() == null
                || model.getDefaultOptions().getModel() == null ? "unconfigured" : model.getDefaultOptions().getModel();
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Class<?> implementation : List.of(VioManus.class, ToolCallAgent.class, TeamAnswerEvidence.class, DamageAnswerEvidence.class,
                GuideAnswerEvidence.class, SavedTeamAnswerEvidence.class, EvidenceText.class, TeamReferenceTools.class,
                com.vio.vioaiagent.pokemon.team.TeamWorkflowService.class, com.vio.vioaiagent.run.RunMutationBoundary.class,
                com.vio.vioaiagent.run.DefaultRunExecutor.class,
                com.vio.vioaiagent.run.AgentRunContext.class,com.vio.vioaiagent.run.PokemonAgentRunService.class,
                com.vio.vioaiagent.observability.ObservedChatModel.class,com.vio.vioaiagent.observability.ModelUsageLedger.class,
                com.vio.vioaiagent.observability.ProviderTokenUsage.class,
                com.vio.vioaiagent.pokemon.guides.LocalGuideSearch.class,com.vio.vioaiagent.pokemon.guides.GuideChunks.class,
                com.vio.vioaiagent.pokemon.guides.SqliteGuideKnowledgeRetriever.class,com.vio.vioaiagent.pokemon.guides.GuideProvenance.class,
                com.vio.vioaiagent.pokemon.reference.RunToolScope.class)) {
            hashes.put(implementation.getSimpleName(), classDigest(implementation));
        }
        return Map.of("profiles", List.of(environment.getActiveProfiles()),
                "configuredProvider", environment.getProperty("vio.model.provider", "dashscope"),
                "configuredModelId", configuredModel,
                "providerReturnedModelId", "not-attested-by-this-endpoint",
                "promptVersion", VioManus.PROMPT_VERSION,
                "implementationSha256", hashes,
                "features", Map.of("mcpClient", environment.getProperty("spring.ai.mcp.client.enabled", Boolean.class, false),
                        "highRiskTools", environment.getProperty("vio.tools.high-risk.enabled", Boolean.class, false),
                        "answerStreaming", environment.getProperty("vio.run.answer-streaming-enabled", Boolean.class, false),
                        "versionedGrounding", environment.getProperty("vio.agent.v1.enabled", Boolean.class, false),"modelUsage",true),
                "tools", Arrays.stream(tools).map(tool -> tool.getToolDefinition().name()).sorted().toList());
    }
    private static String classDigest(Class<?> implementation) {
        try (var input = implementation.getResourceAsStream(implementation.getSimpleName() + ".class")) {
            if (input == null) return "unavailable";
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        } catch (Exception error) { return "unavailable"; }
    }
}
