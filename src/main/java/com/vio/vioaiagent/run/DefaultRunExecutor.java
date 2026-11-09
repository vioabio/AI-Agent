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
    private final ToolCallback[] allTools;
    private final ToolCallbackProvider mcpToolProvider;
    private final ToolExecutionGateway toolExecutionGateway;
    private final GameApp gameApp;
    private final VersionedKnowledgeRunFacade knowledgeFacade;
    private final boolean groundingEnabled;
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
            var savedGym=com.vio.vioaiagent.agent.SavedGymQuestionRoute.reply(context,allTools);
            if(savedGym.isPresent()){context.modelUsage().noModelCalls();if(context.answerDeltaSink()!=null)context.answerDeltaSink().accept(savedGym.get());return savedGym.get();}
            var generalGym=com.vio.vioaiagent.agent.GymGeneralAdviceRoute.reply(context);
            if(generalGym.isPresent()){context.modelUsage().noModelCalls();if(context.answerDeltaSink()!=null)context.answerDeltaSink().accept(generalGym.get());return generalGym.get();}
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
            VioManus agent = new VioManus(evaluationPolicy.select(allTools), evaluationPolicy.active()?null:mcpToolProvider,
                    new com.vio.vioaiagent.observability.ObservedChatModel(chatModel,context.modelUsage(),budget));
            agent.setToolExecutionGateway(toolExecutionGateway);
            agent.setCurrentUserRequest(run.prompt());
            agent.setGuideGameReleaseId(run.gameReleaseId());
            agent.setResponseStyle(context.constraints().responseStyle());
            agent.setTeamWorkflow(evaluationPolicy.active()?null:teamWorkflow);
            agent.setAnswerDeltaSink(answerDeltaSink);
            agent.setMessageList(new java.util.ArrayList<>(context.history()));
            String transcript = agent.run(prompt);
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
                    new com.vio.vioaiagent.observability.ObservedChatModel(chatModel,context.modelUsage(),budget));
            agent.setToolExecutionGateway(toolExecutionGateway);
            agent.setCurrentUserRequest(run.prompt());
            agent.setGuideGameReleaseId(run.gameReleaseId());
            agent.setResponseStyle(context.constraints().responseStyle());
            agent.setMessageList(new java.util.ArrayList<>(context.history()));
            String transcript = agent.run(groundedPrompt);
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

    private void append(StringBuilder builder, String key, String value) {
        if (value != null && !value.isBlank()) builder.append('\n').append(key).append('=').append(value);
    }
}
