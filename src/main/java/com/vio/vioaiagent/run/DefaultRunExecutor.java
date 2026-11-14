package com.vio.vioaiagent.run;

import com.vio.vioaiagent.agent.VioManus;
import com.vio.vioaiagent.app.GameApp;
import com.vio.vioaiagent.rag.contract.AnswerContract;
import com.vio.vioaiagent.tools.governance.ToolExecutionGateway;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Run 的默认领域执行器。
 *
 * <p>可用模型和工具存在时使用一次性 VioManus 实例执行，所有工具回调仍由
 * ToolExecutionGateway 包装；依赖未就绪时才返回明确的占位结果，避免启动阶段
 * 伪装成“已完成”。这样 M3 生命周期与旧 AiController 的 Agent 实现可以逐步迁移。</p>
 */
@Component
public class DefaultRunExecutor implements ContractAwareRunExecutor {
    private final ChatModel chatModel;
    @Autowired(required=false)
    private io.micrometer.observation.ObservationRegistry observationRegistry=io.micrometer.observation.ObservationRegistry.NOOP;
    private final ToolCallback[] allTools;
    private final ToolCallbackProvider mcpToolProvider;
    private final ToolExecutionGateway toolExecutionGateway;
    private final GameApp gameApp;
    private final VersionedKnowledgeRunFacade knowledgeFacade;
    private final boolean groundingEnabled;
    @Value("${vio.agent.guide-research.enabled:false}")
    private boolean guideResearchEnabled;
    public void setGuideResearchEnabled(boolean enabled){this.guideResearchEnabled=enabled;}
    @Value("${vio.agent.specialists.enabled:false}")
    private boolean specialistsEnabled;
    @Value("${vio.agent.skills.enabled:false}")
    private boolean skillsEnabled;
    @Value("${vio.agent.context-assembly.enabled:false}")
    private boolean contextAssemblyEnabled;
    public void setSpecialistsEnabled(boolean enabled){this.specialistsEnabled=enabled;}
    private com.vio.vioaiagent.config.EvaluationToolPolicy evaluationPolicy=com.vio.vioaiagent.config.EvaluationToolPolicy.off();
    @Autowired(required=false)
    public void setEvaluationToolPolicy(com.vio.vioaiagent.config.EvaluationToolPolicy policy) {
        policy.select(allTools);this.evaluationPolicy=policy;
    }
    private com.vio.vioaiagent.config.ContextBudgetProperties contextBudgetProperties=com.vio.vioaiagent.config.ContextBudgetProperties.defaults();
    @Autowired(required=false)
    public void setContextBudgetProperties(com.vio.vioaiagent.config.ContextBudgetProperties properties){this.contextBudgetProperties=java.util.Objects.requireNonNull(properties);}
    @Autowired(required=false)
    private com.vio.vioaiagent.pokemon.team.TeamWorkflowService teamWorkflow;

    public void setTeamWorkflow(com.vio.vioaiagent.pokemon.team.TeamWorkflowService workflow) { this.teamWorkflow=workflow; }

    /** Spring 构造器；ObjectProvider/可选注入保持本地无模型时可启动。 */
    @Autowired
    public DefaultRunExecutor(
            org.springframework.beans.factory.ObjectProvider<ChatModel> chatModelProvider,
            @Qualifier("allTools") ToolCallback[] allTools,
            org.springframework.beans.factory.ObjectProvider<ToolCallbackProvider> mcpProvider,
            org.springframework.beans.factory.ObjectProvider<ToolExecutionGateway> gatewayProvider,
            org.springframework.beans.factory.ObjectProvider<GameApp> gameAppProvider,
            org.springframework.beans.factory.ObjectProvider<VersionedKnowledgeRunFacade> knowledgeFacadeProvider,
            @Value("${vio.agent.v1.enabled:false}") boolean groundingEnabled) {
        this.chatModel = chatModelProvider.getIfAvailable();
        this.allTools = allTools == null ? new ToolCallback[0] : allTools;
        this.mcpToolProvider = mcpProvider.getIfAvailable();
        this.toolExecutionGateway = gatewayProvider.getIfAvailable();
        this.gameApp = gameAppProvider.getIfAvailable();
        this.knowledgeFacade = knowledgeFacadeProvider.getIfAvailable();
        this.groundingEnabled = groundingEnabled;
    }

    /**
     * 离线/嵌入式构造器；允许在不启动 Spring 或真实模型的情况下验证 Run 到知识门面的接线。
     */
    public DefaultRunExecutor(ChatModel chatModel, ToolCallback[] allTools,
                              ToolCallbackProvider mcpToolProvider,
                              ToolExecutionGateway toolExecutionGateway,
                              GameApp gameApp,
                              VersionedKnowledgeRunFacade knowledgeFacade,
                              boolean groundingEnabled) {
        this.chatModel = chatModel;
        this.allTools = allTools == null ? new ToolCallback[0] : allTools;
        this.mcpToolProvider = mcpToolProvider;
        this.toolExecutionGateway = toolExecutionGateway;
        this.gameApp = gameApp;
        this.knowledgeFacade = knowledgeFacade;
        this.groundingEnabled = groundingEnabled;
    }

    /** 测试/无 Spring 场景的安全占位构造器。 */
    public DefaultRunExecutor() {
        // 对 null 参数显式标注类型，避免与 Spring ObjectProvider 构造器重载产生歧义。
        this((ChatModel) null, new ToolCallback[0], (ToolCallbackProvider) null,
                (ToolExecutionGateway) null, (GameApp) null,
                (VersionedKnowledgeRunFacade) null, false);
    }

    @Override
    public String execute(AgentRunContext context) {
        return executeContract(context).answer();
    }

    /**
     * 执行并返回结构化回答契约。Run 主链仍通过 {@link RunExecutor#execute(AgentRunContext)}
     * 接收文本，因此这里保留文本兼容适配；需要展示引用的上层可直接消费契约。
     */
    public AnswerContract executeContract(AgentRunContext context) {
        return new com.vio.vioaiagent.observability.AgentRunObservation(observationRegistry)
                .execute(context.run().id(), () -> executeContractObserved(context));
    }

    private AnswerContract executeContractObserved(AgentRunContext context) {
        AgentRun run = context.run();
        if (groundingEnabled) {
            if (knowledgeFacade == null) {
                return AnswerContract.refused("版本化知识执行器尚未配置。", run.traceId(),
                        "知识检索与证据契约依赖不可用");
            }
            return knowledgeFacade.answer(run, prompt -> generateAnswer(prompt, context));
        }
        return AnswerContract.answered(executeLegacy(context), run.gameReleaseId(), run.battleFormat(),
                run.rulesetVersionId(), java.util.List.of(), java.util.List.of("v1 版本化知识门面未启用"),
                run.traceId());
    }

    @Override
    public boolean usesKnowledgeGrounding() {
        return groundingEnabled;
    }

    /**
     * 将 active knowledge/index release 解析结果传回 RunService，写入答案投影
     * 和完成事件。目录不可用时由门面返回空血缘，反馈入口会保持 fail-closed。
     */
    @Override
    public AnswerLineage answerLineage(AgentRunContext context, AnswerContract contract) {
        if (!groundingEnabled || knowledgeFacade == null || context == null) {
            return AnswerLineage.empty();
        }
        return knowledgeFacade.answerLineage(context.run(), contract);
    }

    private String executeLegacy(AgentRunContext context) {
        AgentRun run = context.run();
        if(!evaluationPolicy.active()){
            var bypass=com.vio.vioaiagent.agent.TeamMutationIntent.confirmationBypassReply(run.prompt());
            if(bypass.isPresent()){context.modelUsage().noModelCalls();if(context.answerDeltaSink()!=null)context.answerDeltaSink().accept(bypass.get());return bypass.get();}
            if(com.vio.vioaiagent.agent.TrainingIntentState.resetRequested(run.prompt())){
                String reset="后续培养核对将从本条消息起重新收集条件。请重新说明游戏、目标、进度、练级与资源限制；本次没有删除历史消息、修改保存队伍或切换对话版本。";
                context.modelUsage().noModelCalls();if(context.answerDeltaSink()!=null)context.answerDeltaSink().accept(reset);return reset;
            }
            var clarification=com.vio.vioaiagent.agent.AccountScopeReply.adviceClarification(run.prompt(),context.dialogueHistory());
            if(clarification.isPresent()){context.modelUsage().noModelCalls();if(context.answerDeltaSink()!=null)context.answerDeltaSink().accept(clarification.get());return clarification.get();}
            var types=com.vio.vioaiagent.agent.TypeMatchupReply.reply(run.prompt());
            if(types.isPresent()){context.modelUsage().noModelCalls();if(context.answerDeltaSink()!=null)context.answerDeltaSink().accept(types.get());return types.get();}
            var training=com.vio.vioaiagent.agent.TrainingMechanicsReply.reply(run.prompt());
            if(training.isPresent()){context.modelUsage().noModelCalls();if(context.answerDeltaSink()!=null)context.answerDeltaSink().accept(training.get());return training.get();}
            var referenceRules=com.vio.vioaiagent.agent.ReferenceRulesReply.reply(context,allTools);
            if(referenceRules.isPresent()){context.modelUsage().noModelCalls();if(context.answerDeltaSink()!=null)context.answerDeltaSink().accept(referenceRules.get());return referenceRules.get();}
            var localGuide=com.vio.vioaiagent.agent.LocalGuideOnlyRoute.reply(context,allTools);
            if(localGuide.isPresent()){context.modelUsage().noModelCalls();if(context.answerDeltaSink()!=null)context.answerDeltaSink().accept(localGuide.get());return localGuide.get();}
            var saveStatus=com.vio.vioaiagent.agent.OwnedTeamReadRoute.saveStatusReply(run.prompt());
            if(saveStatus.isPresent()){context.modelUsage().noModelCalls();if(context.answerDeltaSink()!=null)context.answerDeltaSink().accept(saveStatus.get());return saveStatus.get();}
            var damageReply=com.vio.vioaiagent.agent.DamageDialogueRoute.reply(context,allTools);
            if(damageReply.isPresent()){context.modelUsage().noModelCalls();if(context.answerDeltaSink()!=null)context.answerDeltaSink().accept(damageReply.get());return damageReply.get();}
            if(run.prompt().matches("(?s).*这一轮仅回复[“\"]收到[”\"].*")){context.modelUsage().noModelCalls();if(context.answerDeltaSink()!=null)context.answerDeltaSink().accept("收到");return "收到";}
            if(com.vio.vioaiagent.agent.SavedTrainingRoadmap.requested(run.prompt())){
                var trainingReply=com.vio.vioaiagent.agent.OwnedTeamReadRoute.reply(context,allTools,toolExecutionGateway);
                if(trainingReply.isPresent()){context.modelUsage().noModelCalls();if(context.answerDeltaSink()!=null)context.answerDeltaSink().accept(trainingReply.get());return trainingReply.get();}
            }
            var gymReply=com.vio.vioaiagent.agent.GymDialogueRouter.reply(context,allTools);
            if(gymReply.isPresent()){context.modelUsage().noModelCalls();if(context.answerDeltaSink()!=null)context.answerDeltaSink().accept(gymReply.get());return gymReply.get();}
            var ownedReply=com.vio.vioaiagent.agent.OwnedTeamReadRoute.reply(context,allTools,toolExecutionGateway);
            if(ownedReply.isPresent()){context.modelUsage().noModelCalls();if(context.answerDeltaSink()!=null)context.answerDeltaSink().accept(ownedReply.get());return ownedReply.get();}
        }
        if(teamWorkflow!=null && !evaluationPolicy.active()) {
            String confirmed=com.vio.vioaiagent.pokemon.team.TeamChangePresentation.finishIfPending(teamWorkflow,context);
            if(confirmed!=null){context.modelUsage().noModelCalls();return confirmed;}
        }
        if(chatModel!=null) {
            // The same bounded refusal also protects standalone agents. At the Run boundary
            // we can explicitly account for the known zero-call path before constructing a model client.
            var scopeReply=com.vio.vioaiagent.agent.AccountScopeReply.reply(run.prompt())
                    .or(() -> com.vio.vioaiagent.agent.CompetitionClarification.reply(run.prompt()))
                    .or(() -> com.vio.vioaiagent.agent.SourceInstructionReply.reply(run.prompt()));
            if(scopeReply.isPresent()) {
                context.modelUsage().noModelCalls();
                if(context.answerDeltaSink()!=null)context.answerDeltaSink().accept(scopeReply.get());
                return scopeReply.get();
            }
        }
        if(chatModel!=null && !evaluationPolicy.active()) {
            var capability=com.vio.vioaiagent.agent.CapabilityCatalog.missingStatisticsReply(run.prompt(),run.language(),allTools)
                    .or(() -> com.vio.vioaiagent.agent.CapabilityCatalog.unsupportedDamageReply(run.prompt(),run.language(),allTools))
                    .or(() -> com.vio.vioaiagent.agent.CapabilityCatalog.reply(run.prompt(),run.language(),context.constraints().responseStyle(),allTools));
            if(capability.isPresent()) {
                context.modelUsage().noModelCalls();
                if(context.answerDeltaSink()!=null) context.answerDeltaSink().accept(capability.get());
                return capability.get();
            }
        }
        var answerDeltaSink = context.answerDeltaSink();
        String prompt = context.constraints().applyTo(scopedPrompt(run));
        if (chatModel != null) {
            var budget=new com.vio.vioaiagent.observability.ModelContextBudget(contextBudgetProperties,context.history(),context.modelUsage());
            var research=com.vio.vioaiagent.agent.GuideResearchAgent.Result.skipped();
            if(guideResearchEnabled&&!specialistsEnabled&&!evaluationPolicy.active())research=new com.vio.vioaiagent.agent.GuideResearchAgent().research(context,
                    new com.vio.vioaiagent.observability.ObservedChatModel(chatModel,context.modelUsage(),
                            new com.vio.vioaiagent.observability.ModelContextBudget(contextBudgetProperties,java.util.List.of(),context.modelUsage())),allTools);
            VioManus agent = new VioManus(evaluationPolicy.select(allTools), evaluationPolicy.active()?null:mcpToolProvider,
                    new com.vio.vioaiagent.observability.ObservedChatModel(chatModel,context.modelUsage(),budget,observationRegistry));
            agent.setPokemonBlockSink(context.mutations()::publishFact);
            agent.setDamageBlockSink(context.mutations()::publishDamage);
            agent.setTeamBlockSink(context.mutations()::publishTeam);
            agent.setTrainingPlanSink(context.mutations()::publishTraining);
            agent.setGuideResearchResult(research);
            configureSpecialists(agent,context);
            agent.setToolExecutionGateway(toolExecutionGateway);
            agent.setCurrentUserRequest(run.prompt());
            agent.setGuideGameReleaseId(run.gameReleaseId());
            agent.setResponseStyle(context.constraints().responseStyle());
            agent.setTeamWorkflow(evaluationPolicy.active()?null:teamWorkflow);
            agent.setAnswerDeltaSink(answerDeltaSink);
            agent.setMessageList(new java.util.ArrayList<>(context.history()));
            String transcript = agent.run(prompt);
            if(agent.calculationFailed())throw new com.vio.vioaiagent.agent.DamageCalculationFailedException();
            if(agent.isProgramOnlyPreview() || agent.isProgramOnlyTrends()) context.modelUsage().noModelCalls();
            budget.throwIfBlocked();
            context.modelUsage().checkTaskBudget();
            if (answerDeltaSink != null && agent.getFinalAnswer() == null && run.status() != AgentRunStatus.WAITING_APPROVAL) {
                throw new IllegalStateException("模型回答生成失败，请检查 DeepSeek Key、模型权限和网络连接");
            }
            return agent.getFinalAnswer() == null ? transcript : agent.getFinalAnswer();
        }
        if(evaluationPolicy.active())throw new IllegalStateException("评测模式需要已配置的ChatModel，不使用旧问答回退");
        if (gameApp != null) {
            return gameApp.doChat(prompt, run.conversationId());
        }
        return "Run 已创建，领域执行器尚未就绪：" + run.prompt();
    }

    private String generateAnswer(String groundedPrompt, AgentRunContext context) {
        AgentRun run = context.run();
        groundedPrompt = context.constraints().applyTo(groundedPrompt);
        if (chatModel != null && allTools.length > 0) {
            var budget=new com.vio.vioaiagent.observability.ModelContextBudget(contextBudgetProperties,context.history(),context.modelUsage());
            VioManus agent = new VioManus(allTools, mcpToolProvider,
                    new com.vio.vioaiagent.observability.ObservedChatModel(chatModel,context.modelUsage(),budget,observationRegistry));
            configureSpecialists(agent,context);
            agent.setToolExecutionGateway(toolExecutionGateway);
            agent.setCurrentUserRequest(run.prompt());
            agent.setGuideGameReleaseId(run.gameReleaseId());
            agent.setResponseStyle(context.constraints().responseStyle());
            agent.setMessageList(new java.util.ArrayList<>(context.history()));
            String transcript = agent.run(groundedPrompt);
            if(agent.calculationFailed())throw new com.vio.vioaiagent.agent.DamageCalculationFailedException();
            budget.throwIfBlocked();
            context.modelUsage().checkTaskBudget();
            return agent.getFinalAnswer() == null ? transcript : agent.getFinalAnswer();
        }
        if (gameApp != null) {
            return gameApp.doChat(groundedPrompt, run.conversationId());
        }
        return null;
    }

    private String scopedPrompt(AgentRun run) {
        StringBuilder prefix = new StringBuilder("[VIO Run 上下文]");
        append(prefix, "game_release_id", run.gameReleaseId());
        append(prefix, "battle_format", run.battleFormat());
        append(prefix, "ruleset_version_id", run.rulesetVersionId());
        append(prefix, "language", run.language());
        return prefix.append("\n用户请求：").append(run.prompt()).toString();
    }

    private void configureSpecialists(VioManus agent,AgentRunContext context) {
        if(specialistsEnabled&&!evaluationPolicy.active()) {
            var coordinator=new com.vio.vioaiagent.agent.SpecialistAgentCoordinator(context,
                new com.vio.vioaiagent.observability.ObservedChatModel(chatModel,context.modelUsage(),
                        new com.vio.vioaiagent.observability.ModelContextBudget(contextBudgetProperties,java.util.List.of(),context.modelUsage()),observationRegistry));
            coordinator.setContextAssembly(contextAssemblyEnabled,contextBudgetProperties);
            coordinator.setSkillsEnabled(skillsEnabled);agent.setSpecialistCoordinator(coordinator);
        }
    }

    private void append(StringBuilder builder, String key, String value) {
        if (value != null && !value.isBlank()) builder.append('\n').append(key).append('=').append(value);
    }
}
