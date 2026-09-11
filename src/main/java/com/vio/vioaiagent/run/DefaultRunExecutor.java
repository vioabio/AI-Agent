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
            return knowledgeFacade.answer(run, prompt -> generateAnswer(prompt, run));
        }
        return AnswerContract.answered(executeLegacy(run), run.gameReleaseId(), run.battleFormat(),
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

    private String executeLegacy(AgentRun run) {
        String prompt = scopedPrompt(run);
        if (chatModel != null && allTools.length > 0) {
            VioManus agent = new VioManus(allTools, mcpToolProvider, chatModel);
            agent.setToolExecutionGateway(toolExecutionGateway);
            return agent.run(prompt);
        }
        if (gameApp != null) {
            return gameApp.doChat(prompt, run.conversationId());
        }
        return "Run 已创建，领域执行器尚未就绪：" + run.prompt();
    }

    private String generateAnswer(String groundedPrompt, AgentRun run) {
        if (chatModel != null && allTools.length > 0) {
            VioManus agent = new VioManus(allTools, mcpToolProvider, chatModel);
            agent.setToolExecutionGateway(toolExecutionGateway);
            return agent.run(groundedPrompt);
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
