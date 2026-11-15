package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.agent.*;
import com.vio.vioaiagent.run.ConversationConstraints;
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
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.vio.vioaiagent.pokemon.engine.VerifiedLocalDamageEngine damageEngine;
    private final Environment environment;
    private final ObjectProvider<ChatModel> models;
    private final ToolCallback[] tools;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.vio.vioaiagent.config.ContextBudgetProperties contextBudgetProperties=com.vio.vioaiagent.config.ContextBudgetProperties.defaults();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.vio.vioaiagent.config.TaskBudgetProperties taskBudgetProperties=com.vio.vioaiagent.config.TaskBudgetProperties.defaults();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.vio.vioaiagent.observability.ModelPriceCatalog modelPrices=com.vio.vioaiagent.observability.ModelPriceCatalog.disabled();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.vio.vioaiagent.config.EvaluationToolPolicy evaluationPolicy=com.vio.vioaiagent.config.EvaluationToolPolicy.off();
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
        for (Class<?> implementation : List.of(VioManus.class, ToolCallAgent.class, AnswerPresentation.class, CapabilityCatalog.class, AccountScopeReply.class, TeamAnswerEvidence.class, DamageAnswerEvidence.class,
                PokemonAnswerBlock.class,DamageAnswerBlock.class,EvidenceAnswerBlock.class,TeamSuggestionAnswerBlock.class,GameVersionRequest.class,com.vio.vioaiagent.tools.CompetitiveTeamsTool.class,
                GuideAnswerEvidence.class, DamageDialogueRoute.class, OwnedTeamReadRoute.class, TrainingMechanicsReply.class, LocalGuideOnlyRoute.class, GuideResearchAgent.class, SpecialistAgentCoordinator.class, SpecialistRole.class, SavedTeamAnswerEvidence.class, EvidenceText.class, TeamReferenceTools.class,
                com.vio.vioaiagent.pokemon.team.TeamWorkflowService.class, com.vio.vioaiagent.run.RunMutationBoundary.class,
                com.vio.vioaiagent.pokemon.team.TeamChangePresentation.class, com.vio.vioaiagent.pokemon.team.TeamWorkflowController.class,
                com.vio.vioaiagent.config.EvaluationToolPolicy.class,
                com.vio.vioaiagent.agent.ProjectGuideRequest.class,
                com.vio.vioaiagent.agent.GuideReferenceIdentity.class,
                ResourceAnswerEvidence.class,com.vio.vioaiagent.pokemon.reference.ResourceReferenceFacts.class,
                OverviewResourceCoverage.class,
                com.vio.vioaiagent.agent.LearnsetAnswerEvidence.class,
                com.vio.vioaiagent.pokemon.reference.PokemonLearnsetFacts.class,
                com.vio.vioaiagent.pokemon.reference.LocalLearnsetReferences.class,
                com.vio.vioaiagent.pokemon.reference.PokemonLearnsetController.class,
                MechanicAnswerEvidence.class,com.vio.vioaiagent.pokemon.reference.IndependentMechanicFacts.class,
                com.vio.vioaiagent.pokemon.reference.IndependentMechanicCatalog.class,com.vio.vioaiagent.pokemon.reference.PokemonMechanicsController.class,
                com.vio.vioaiagent.tools.governance.MechanicResultEvidence.class,
                BuildCheckAnswerEvidence.class,ToolHistoryIntegrity.class,BuildCheckRequestRoute.class,com.vio.vioaiagent.pokemon.reference.BuildCheckFacts.class,com.vio.vioaiagent.pokemon.reference.BuildLearningNotes.class,
                com.vio.vioaiagent.pokemon.reference.LocalBuildCheckService.class,com.vio.vioaiagent.pokemon.reference.PokemonBuildCheckController.class,
                com.vio.vioaiagent.pokemon.reference.TeamBuildCheckFacts.class,com.vio.vioaiagent.pokemon.reference.PokemonTeamBuildCheckController.class,
                TeamBuildAnswerEvidence.class,com.vio.vioaiagent.tools.governance.TeamBuildResultEvidence.class,
                TeamBuildRequestRoute.class,
                com.vio.vioaiagent.tools.governance.BuildCheckResultEvidence.class,
                SavedTrainingRoadmap.class,SavedTrainingResources.class,TrainingIntentState.class,TrainingTargetCheck.class,TrainingLearningCheck.class,TrainingResourceBudget.class,SavedTrainingGymReview.class,TrainingStatProjection.class,TrainingLevelConstraints.class,TrainingProgressCheck.class,TeamMutationIntent.class,ReferenceRulesReply.class,GymFocusedFactReply.class,com.vio.vioaiagent.pokemon.guides.GuideRevisionPolicy.class,com.vio.vioaiagent.pokemon.gym.GymResourcePolicy.class,com.vio.vioaiagent.pokemon.calculator.StaticStatCalculator.class,
                MachineAnswerEvidence.class,com.vio.vioaiagent.pokemon.reference.MachineReferenceCatalog.class,com.vio.vioaiagent.pokemon.reference.MachineCraftingGuide.class,com.vio.vioaiagent.pokemon.reference.MachineRecipeCatalog.class,com.vio.vioaiagent.pokemon.reference.MachineAcquisitionCatalog.class,
                com.vio.vioaiagent.pokemon.reference.MachineReferenceFacts.class,com.vio.vioaiagent.pokemon.reference.LocalMachineReferences.class,
                com.vio.vioaiagent.pokemon.reference.PokemonMachineController.class,com.vio.vioaiagent.tools.governance.MachineResultEvidence.class,
                com.vio.vioaiagent.pokemon.reference.LocalResourceReferences.class,com.vio.vioaiagent.pokemon.reference.PokemonReferenceTools.class,
                com.vio.vioaiagent.pokemon.reference.PokemonReferenceController.class,
                com.vio.vioaiagent.run.DefaultRunExecutor.class,
                com.vio.vioaiagent.run.AgentRunContext.class,com.vio.vioaiagent.run.PokemonAgentRunService.class,
                com.vio.vioaiagent.observability.ObservedChatModel.class,com.vio.vioaiagent.observability.ModelUsageLedger.class,
                com.vio.vioaiagent.observability.AgentToolObservation.class,com.vio.vioaiagent.observability.AgentTracingConfiguration.class,
                TaskSkillRegistry.class,TaskContextAssembler.class,
                com.vio.vioaiagent.observability.AgentRunObservation.class,
                com.vio.vioaiagent.observability.DomainTracingObservationHandler.class,
                com.vio.vioaiagent.pokemon.reference.LocalPokeApiRepository.class,
                com.vio.vioaiagent.pokemon.reference.RegionalFormName.class,
                com.vio.vioaiagent.observability.ModelFailureDiagnostics.class,
                com.vio.vioaiagent.observability.ProviderTokenUsage.class,
                com.vio.vioaiagent.observability.TokenEstimateComparison.class,
                com.vio.vioaiagent.observability.ModelContextBudget.class,com.vio.vioaiagent.observability.ContextTokenEstimator.class,
                com.vio.vioaiagent.observability.DashScopeBudgetChatModel.class,
                com.vio.vioaiagent.observability.ContextBudgetExceededException.class,com.vio.vioaiagent.config.ContextBudgetProperties.class,
                com.vio.vioaiagent.observability.TaskModelBudget.class,com.vio.vioaiagent.observability.TaskBudgetExceededException.class,
                com.vio.vioaiagent.config.TaskBudgetProperties.class,
                com.vio.vioaiagent.observability.ProviderBillingUsage.class,com.vio.vioaiagent.observability.ModelPriceCatalog.class,
                com.vio.vioaiagent.observability.ModelCostEstimate.class,com.vio.vioaiagent.config.ModelPricingProperties.class,
                com.vio.vioaiagent.observability.RunAttemptTiming.class,com.vio.vioaiagent.observability.AgentMetrics.class,
                com.vio.vioaiagent.tools.governance.BoundedToolExecutor.class,com.vio.vioaiagent.tools.governance.ToolAttemptTiming.class,
                com.vio.vioaiagent.config.ModelPricingConfiguration.class,com.vio.vioaiagent.config.DeepSeekModelConfiguration.class,
                com.vio.vioaiagent.config.DeepSeekRequestCodec.class,com.vio.vioaiagent.config.DeepSeekBillingSseReader.class,
                com.vio.vioaiagent.run.ConversationHistory.class,
                ConversationConstraints.class,com.vio.vioaiagent.run.JdbcConversationConstraints.class,
                com.vio.vioaiagent.run.SqliteLocalHistoryRepository.class,com.vio.vioaiagent.run.JdbcConversationRepository.class,
                com.vio.vioaiagent.run.InMemoryConversationRepository.class,AgentRunController.class,
                com.vio.vioaiagent.tools.governance.ToolExecutionGateway.class,com.vio.vioaiagent.tools.governance.ToolInputEvidence.class,
                com.vio.vioaiagent.tools.governance.DamageResultEvidence.class,com.vio.vioaiagent.pokemon.engine.DamageResultContract.class,
                com.vio.vioaiagent.tools.governance.LearnsetResultEvidence.class,
                com.vio.vioaiagent.tools.governance.DamageToolSchema.class,com.vio.vioaiagent.tools.governance.ToolSchemaReferences.class,
                com.vio.vioaiagent.agent.DamageAdvicePolicy.class,
                com.vio.vioaiagent.tools.ToolRegistration.class,
                com.vio.vioaiagent.pokemon.engine.LocalDamageEngine.class,com.vio.vioaiagent.pokemon.engine.VerifiedLocalDamageEngine.class,com.vio.vioaiagent.pokemon.domain.TeamBuild.class,
                com.vio.vioaiagent.pokemon.guides.LocalGuideSearch.class,com.vio.vioaiagent.pokemon.guides.GuideChunks.class,
                com.vio.vioaiagent.agent.TypeMatchupReply.class,
                com.vio.vioaiagent.pokemon.guides.GuideApplicability.class,
                com.vio.vioaiagent.pokemon.guides.SqliteGuideKnowledgeRetriever.class,com.vio.vioaiagent.pokemon.guides.GuideProvenance.class,
                com.vio.vioaiagent.pokemon.reference.RunToolScope.class)) {
            hashes.put(implementation.getSimpleName(), classDigest(implementation));
        }
        hashes.put("EvidenceAnswerBlock$Source",classDigest(EvidenceAnswerBlock.Source.class));
        hashes.put("LocalDamageEngine$Combatant",classDigest(com.vio.vioaiagent.pokemon.engine.LocalDamageEngine.Combatant.class));
        hashes.put("LocalGuideSemanticSearch",classDigest(com.vio.vioaiagent.pokemon.guides.LocalGuideSemanticSearch.class));
        hashes.put("GuideSemanticProperties",classDigest(com.vio.vioaiagent.pokemon.guides.GuideSemanticProperties.class));
        hashes.put("GuideEmbeddingClient",classDigest("com.vio.vioaiagent.pokemon.guides.GuideEmbeddingClient"));
        hashes.put("GymCatalogAnswer",classDigest("com.vio.vioaiagent.agent.GymCatalogAnswer"));
        hashes.put("GymDialogueRouter",classDigest(GymDialogueRouter.class));
        hashes.put("ConfigurationExplanationRoute",classDigest(ConfigurationExplanationRoute.class));
        hashes.put("ConfigurationExplanationRoute$Topic",classDigest("com.vio.vioaiagent.agent.ConfigurationExplanationRoute$Topic"));
        hashes.put("ConversationRecallReply",classDigest(ConversationRecallReply.class));
        hashes.put("GymConversationContext",classDigest("com.vio.vioaiagent.agent.GymConversationContext"));
        hashes.put("GymTurnPlan",classDigest("com.vio.vioaiagent.agent.GymTurnPlan"));
        hashes.put("OwnedTeamReadRoute",classDigest(OwnedTeamReadRoute.class));
        hashes.put("ExplicitDamageRequest",classDigest(ExplicitDamageRequest.class));
        for(String name:List.of("McpCancellationTransport","McpCancellationTransport$Scope","McpCancellationTransport$Invocation","McpCancellationTransport$Snapshot","CancellableMcpToolCallback"))
            hashes.put(name,classDigest("com.vio.vioaiagent.mcp.client."+name));
        for(String name:List.of("McpCancellationConfiguration","McpCancellationConfiguration$1"))
            hashes.put(name,classDigest("com.vio.vioaiagent.config."+name));
        hashes.put("SourceInstructionReply",classDigest(SourceInstructionReply.class));
        hashes.put("GuideRerankConfiguration",classDigest(com.vio.vioaiagent.pokemon.guides.GuideRerankConfiguration.class));
        hashes.put("GuideRerankConfiguration$HttpReranker",classDigest("com.vio.vioaiagent.pokemon.guides.GuideRerankConfiguration$HttpReranker"));
        hashes.put("GuideRerankConfiguration$HttpReranker$LimitedBody",classDigest("com.vio.vioaiagent.pokemon.guides.GuideRerankConfiguration$HttpReranker$LimitedBody"));
        hashes.put("GuideRerankPipeline",classDigest(com.vio.vioaiagent.pokemon.guides.GuideRerankPipeline.class));
        hashes.put("GuideReranker",classDigest(com.vio.vioaiagent.pokemon.guides.GuideReranker.class));
        Map<String,Object> runtime=new LinkedHashMap<>(Map.of("profiles", List.of(environment.getActiveProfiles()),
                "configuredProvider", environment.getProperty("vio.model.provider", "dashscope"),
                "configuredModelId", configuredModel,
                "providerReturnedModelId", "not-attested-by-this-endpoint",
                "promptVersion", VioManus.PROMPT_VERSION,
                "implementationSha256", hashes,
                "features", Map.of("mcpClient", environment.getProperty("spring.ai.mcp.client.enabled", Boolean.class, false),
                        "highRiskTools", environment.getProperty("vio.tools.high-risk.enabled", Boolean.class, false),
                        "answerStreaming", environment.getProperty("vio.run.answer-streaming-enabled", Boolean.class, false),
                        "versionedGrounding", environment.getProperty("vio.agent.v1.enabled", Boolean.class, false),"modelUsage",true,"contextBudget",true,"taskBudget",true,"costObservation",true,"timingObservation",true,"conversationConstraints",true),
                "taskBudgetPolicy",Map.of("maxModelCalls",taskBudgetProperties.maxModelCalls(),"tokenLimit",taskBudgetProperties.tokenLimit(),
                        "accounting",com.vio.vioaiagent.observability.TaskModelBudget.ACCOUNTING),
                "contextBudgetPolicy",Map.of("contextTokens",contextBudgetProperties.contextTokens(),"outputReserve",contextBudgetProperties.outputTokens(),
                        "historyTokens",contextBudgetProperties.historyTokens(),"historyTurns",contextBudgetProperties.historyTurns(),
                        "safetyPercent",contextBudgetProperties.safetyPercent(),"estimator",com.vio.vioaiagent.observability.ContextTokenEstimator.ID),
                "tools", Arrays.stream(tools).map(tool -> tool.getToolDefinition().name()).sorted().toList()));
        runtime.put("tokenEstimateObservation",Map.of("schemaVersion","token-estimate-comparison-v1","automaticPolicyChange",false));
        runtime.put("agentCollaboration",Map.of("guideResearchEnabled",environment.getProperty("vio.agent.guide-research.enabled",Boolean.class,false),
                "specialistsEnabled",environment.getProperty("vio.agent.specialists.enabled",Boolean.class,false),
                "roles",Arrays.stream(SpecialistRole.values()).map(Enum::name).toList(),
                "maxSpecialistModelCalls",SpecialistAgentCoordinator.MAX_MODEL_CALLS,"maxSpecialistToolCalls",SpecialistAgentCoordinator.MAX_TOOL_CALLS,
                "maxParallelAgents",1,"writes","SUPERVISOR_CONFIRMATION_ONLY","evaluationPolicy","SINGLE_AGENT_BASELINE"));
        runtime.put("toolInputEvidence",Map.of("schemaVersion",com.vio.vioaiagent.tools.governance.ToolInputEvidence.SCHEMA,
                "scope","SUPPLIED_ARGUMENTS_ONLY","tools",com.vio.vioaiagent.tools.governance.ToolInputEvidence.supportedTools().stream().sorted().toList(),
                "previewPolicy",Map.of("projectionVersion",com.vio.vioaiagent.tools.governance.ToolInputEvidence.PREVIEW_POLICY_VERSION,
                        "fieldLimit",com.vio.vioaiagent.tools.governance.ToolInputEvidence.PREVIEW_MAX_FIELDS,"requiresConfirmation",true)));
        runtime.put("damageResultEvidence",Map.of("schemaVersion",com.vio.vioaiagent.tools.governance.DamageResultEvidence.SCHEMA,
                "conditionSchema",com.vio.vioaiagent.pokemon.engine.DamageResultContract.SCHEMA,"bridgeSha256",damageEngine==null?"unconfigured":damageEngine.bridgeDigest(),
                "implementation",damageEngine==null?"unconfigured":damageEngine.getClass().getSimpleName()));
        runtime.put("pricingPolicy",modelPrices.policy());
        runtime.put("evaluationPolicy",evaluationPolicy.describe(tools));
        runtime.put("guideReferencePolicy",Map.of("version",com.vio.vioaiagent.agent.GuideAnswerEvidence.REFERENCE_RETENTION_VERSION,
                "scope","KEEP_ONLY_BOUND_UNAFFECTED_MEMBER_READS_INVALIDATE_SAME_OR_UNKNOWN_TARGET_NOT_COMPLETE_TEAM_EVIDENCE"));
        runtime.put("guideOverviewPolicy",Map.of("version",com.vio.vioaiagent.agent.GuideAnswerEvidence.OVERVIEW_READY_VERSION,
                "scope","PROGRAM_VERIFIED_REFERENCE_AND_GENERAL_GUIDE_ONLY_EXCLUDES_CALCULATION_TEAM_AND_WRITES"));
        runtime.put("guideRevisionPolicy",Map.of("version",com.vio.vioaiagent.pokemon.guides.GuideRevisionPolicy.VERSION,"scopeRestrictedRevisions",com.vio.vioaiagent.pokemon.guides.GuideRevisionPolicy.LEGACY_SCOPE.size(),"scope","EXACT_ID_CHECKSUM_AND_TOOL_SCOPE_NOT_SEMANTIC_VALIDATION"));
        runtime.put("guideRetrievalPolicy",Map.of("version",com.vio.vioaiagent.pokemon.guides.GuideApplicability.POLICY,
                "scope","DECLARED_SUBJECT_AND_TOPIC_FILTER_THEN_SUBJECT_PRIORITY_BM25_NOT_SEMANTIC_REVIEW",
                "subjectSourceVersion",GuideAnswerEvidence.SUBJECT_SOURCE_VERSION,"subjectSourceOrigin",GuideAnswerEvidence.SUBJECT_SOURCE_ORIGIN));
        runtime.put("resourceReferencePolicy",Map.of("schemaVersion",com.vio.vioaiagent.pokemon.reference.ResourceReferenceFacts.SCHEMA,
                "answerVersion",ResourceAnswerEvidence.VERSION,"scope","ARGUMENT_BOUND_PUBLIC_FIELDS_SV_EXCERPT_AND_PROJECT_PARAPHRASE_NOT_COMPLETE_MECHANICS"));
        runtime.put("overviewResourcePolicy",Map.of("version",OverviewResourceCoverage.VERSION,"selectionOrigin",OverviewResourceCoverage.ORIGIN,
                "scope","EXPLICIT_QUERY_KINDS_ONE_BUNDLED_MEMBER_CANDIDATE_PER_ABILITY_OR_MOVE_WITH_GAPS_NOT_COMPLETE_MECHANICS","maxProgramReads",OverviewResourceCoverage.MAX_PROGRAM_READS));
        runtime.put("learnsetReferencePolicy",Map.of("schemaVersion",com.vio.vioaiagent.pokemon.reference.PokemonLearnsetFacts.SCHEMA,
                "answerVersion",com.vio.vioaiagent.agent.LearnsetAnswerEvidence.VERSION,
                "outputEvidenceSchema",com.vio.vioaiagent.tools.governance.LearnsetResultEvidence.SCHEMA,
                "versionGroup",com.vio.vioaiagent.pokemon.reference.PokemonLearnsetFacts.VERSION_GROUP,
                "scope","ARGUMENT_BOUND_MEMBER_MOVE_AND_SV_METHOD_LEVEL_NOT_COMPLETE_OPERATIONS_OR_LEGALITY"));
        runtime.put("independentMechanicPolicy",Map.of("schemaVersion",com.vio.vioaiagent.pokemon.reference.IndependentMechanicFacts.SCHEMA,
                "answerVersion",MechanicAnswerEvidence.VERSION,"outputEvidenceSchema",com.vio.vioaiagent.tools.governance.MechanicResultEvidence.SCHEMA,
                "sourceCommit",com.vio.vioaiagent.pokemon.reference.IndependentMechanicFacts.COMMIT,"scope",com.vio.vioaiagent.pokemon.reference.IndependentMechanicFacts.SCOPE,"records",6));
        runtime.put("buildCheckPolicy",Map.of("schemaVersion",com.vio.vioaiagent.pokemon.reference.BuildCheckFacts.SCHEMA,"answerVersion",BuildCheckAnswerEvidence.VERSION,
                "outputEvidenceSchema",com.vio.vioaiagent.tools.governance.BuildCheckResultEvidence.SCHEMA,"scope",com.vio.vioaiagent.pokemon.reference.BuildCheckFacts.SCOPE,"writesTeams",false,
                "learningNotesVersion",com.vio.vioaiagent.pokemon.reference.BuildLearningNotes.VERSION,"requestRouteVersion",BuildCheckRequestRoute.VERSION));
        runtime.put("machineReferencePolicy",Map.of("schemaVersion",com.vio.vioaiagent.pokemon.reference.MachineReferenceFacts.SCHEMA,"catalogSha256",com.vio.vioaiagent.pokemon.reference.MachineReferenceCatalog.SHA256,"records",4,"scope",com.vio.vioaiagent.pokemon.reference.MachineReferenceFacts.SCOPE));
        runtime.put("machineCraftingGuidePolicy",Map.of("schemaVersion",com.vio.vioaiagent.pokemon.reference.MachineCraftingGuide.SCHEMA,"sha256",com.vio.vioaiagent.pokemon.reference.MachineCraftingGuide.SHA256,"scope",com.vio.vioaiagent.pokemon.reference.MachineCraftingGuide.SCOPE));
        runtime.put("machineRecipePolicy",Map.of("schemaVersion",com.vio.vioaiagent.pokemon.reference.MachineRecipeCatalog.SCHEMA,"catalogSha256",com.vio.vioaiagent.pokemon.reference.MachineRecipeCatalog.SHA256,"scope",com.vio.vioaiagent.pokemon.reference.MachineRecipeCatalog.SCOPE,"records",4,"sourceDisagreements",1));
        runtime.put("machineAcquisitionPolicy",Map.of("schemaVersion",com.vio.vioaiagent.pokemon.reference.MachineAcquisitionCatalog.SCHEMA,"catalogSha256",com.vio.vioaiagent.pokemon.reference.MachineAcquisitionCatalog.SHA256,"scope",com.vio.vioaiagent.pokemon.reference.MachineAcquisitionCatalog.SCOPE,"records",4,"unlockStatus","NOT_CROSS_CHECKED"));
        runtime.put("teamBuildCheckPolicy",Map.of("schemaVersion",com.vio.vioaiagent.pokemon.reference.TeamBuildCheckFacts.SCHEMA,"scope",com.vio.vioaiagent.pokemon.reference.TeamBuildCheckFacts.SCOPE,"maxMembers",6,"jointStatus","NOT_VERIFIED","writesTeams",false,"answerVersion",TeamBuildAnswerEvidence.VERSION,"outputEvidenceSchema",com.vio.vioaiagent.tools.governance.TeamBuildResultEvidence.SCHEMA,"requestRouteVersion",TeamBuildRequestRoute.VERSION,"requestRouteOrigin",TeamBuildRequestRoute.ORIGIN));
        runtime.put("projectGuidePolicy",Map.of("version",com.vio.vioaiagent.agent.ProjectGuideRequest.VERSION,
                "selectionOrigin",com.vio.vioaiagent.agent.ProjectGuideRequest.ORIGIN,"scope","BOUNDED_EXPLICIT_PROJECT_EXPLANATIONS_ONLY",
                "sourcePresentation",com.vio.vioaiagent.agent.ProjectGuideRequest.SOURCE_PRESENTATION));
        var generation=new LinkedHashMap<String,Object>();
        var options=model==null?null:model.getDefaultOptions();
        generation.put("temperature",options==null?null:options.getTemperature());
        generation.put("topP",options==null?null:options.getTopP());
        generation.put("maxTokens",options==null?null:options.getMaxTokens());
        generation.put("scope","CONFIGURED_DEFAULTS_NOT_PROVIDER_REQUEST_ATTESTATION");
        runtime.put("generationDefaults",generation);
        runtime.put("upgradeFeatures",Map.of(
            "answerBlocks",environment.getProperty("vio.agent.answer-blocks.enabled",Boolean.class,false),
            "skills",environment.getProperty("vio.agent.skills.enabled",Boolean.class,false),
            "contextAssembly",environment.getProperty("vio.agent.context-assembly.enabled",Boolean.class,false),
            "rerank",environment.getProperty("vio.local-guides.rerank.enabled",Boolean.class,false),
            "domainTracing",environment.getProperty("vio.observability.tracing.enabled",Boolean.class,false)));
        runtime.put("domainTracingPolicy",Map.of(
                "sampleRate",environment.getProperty("vio.observability.tracing.sample-rate",Double.class,0.1),
                "automaticTracing",environment.getProperty("management.tracing.enabled",Boolean.class,false),
                "scope","CONFIGURED_DOMAIN_ROOT_SAMPLING"));
        return Collections.unmodifiableMap(runtime);
    }
    private static String classDigest(Class<?> implementation) {
        return classDigest(implementation.getName());
    }
    private static String classDigest(String implementation) {
        String resource="/"+implementation.replace('.','/')+".class";
        try (var input = LocalRuntimeController.class.getResourceAsStream(resource)) {
            if (input == null) return "unavailable";
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        } catch (Exception error) { return "unavailable"; }
    }
}
