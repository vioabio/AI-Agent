package com.vio.vioaiagent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import com.vio.vioaiagent.agent.model.AgentState;
import com.vio.vioaiagent.tools.governance.ToolExecutionContext;
import com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder;
import com.vio.vioaiagent.tools.governance.ToolExecutionGateway;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 工具调用智能体
 * <p>
 * 具体实现了 ReActAgent 的 {@link #think()} 和 {@link #act()} 方法，
 * 具备完整的工具调用能力。可作为具体智能体实例（如 VioManus）的直接父类。
 *
 * @author vio
 * @see VioManus
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

    /** 单次工具调用返回结果的最大字符数（超过则截断，防止 Token 爆炸） */
    private static final int MAX_TOOL_OUTPUT_LENGTH = 3000;

    /** 连续错误的最大容忍次数（超过则强制终止，防止无限循环） */
    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    // ==================== 工具系统 ====================

    private final ToolCallback[] availableTools;

    private ToolCallbackProvider toolCallbackProvider;

    /** 可选治理网关；本地工具通常已在 ToolRegistration 中包装，MCP 工具在此补齐。 */
    private ToolExecutionGateway toolExecutionGateway;

    private ChatResponse toolCallChatResponse;

    private ToolCallingManager toolCallingManager;

    private final ChatOptions chatOptions;

    /** 连续错误计数器 */
    private int consecutiveErrors = 0;

    /** User-facing response, kept separate from the execution transcript. */
    private String finalAnswer;
    /** The unwrapped current user request, separate from saved preferences, source material and history. */
    private String currentUserRequest;
    private com.vio.vioaiagent.run.ConversationConstraints.ResponseStyle responseStyle = com.vio.vioaiagent.run.ConversationConstraints.ResponseStyle.STANDARD;

    /** Invoked on the run worker thread, preserving lease and governance context. */
    private java.util.function.Consumer<String> answerDeltaSink;
    private final StringBuilder streamedAnswer = new StringBuilder();
    private TeamAnswerEvidence teamAnswerEvidence;
    private DamageAnswerEvidence damageAnswerEvidence;
    private GuideAnswerEvidence guideAnswerEvidence;
    private SavedTeamAnswerEvidence savedTeamAnswerEvidence;
    /** Supplied by the authenticated Run adapter, never parsed from source text or model arguments. */
    private String guideGameReleaseId;
    private boolean projectGuideAttempted;
    private boolean speciesGuideAttempted;
    private boolean suppliedBuildAttempted;
    private boolean suppliedTeamAttempted;
    private TeamDraftIdentityEvidence draftIdentity;
    private final java.util.Set<String> overviewResourceAttempts=new java.util.HashSet<>();
    private com.vio.vioaiagent.pokemon.team.TeamWorkflowService teamWorkflow;

    // ==================== 构造器 ====================

    public ToolCallAgent(ToolCallback[] availableTools) {
        super();
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.chatOptions = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
    }

    public void setToolCallbackProvider(ToolCallbackProvider toolCallbackProvider) {
        this.toolCallbackProvider = toolCallbackProvider;
    }

    public void setToolExecutionGateway(ToolExecutionGateway toolExecutionGateway) {
        this.toolExecutionGateway = toolExecutionGateway;
    }

    private ToolCallback[] getMergedTools() {
        if (toolCallbackProvider == null) {
            return availableTools;
        }
        ToolCallback[] mcpTools = toolCallbackProvider.getToolCallbacks();
        if (mcpTools == null || mcpTools.length == 0) {
            return availableTools;
        }
        if (toolExecutionGateway != null) {
            mcpTools = toolExecutionGateway.wrap(mcpTools);
        }
        ToolCallback[] merged = Arrays.copyOf(availableTools,
                availableTools.length + mcpTools.length);
        System.arraycopy(mcpTools, 0, merged, availableTools.length, mcpTools.length);
        return merged;
    }

    // ==================== Think：推理阶段 ====================

    @Override
    public boolean think() {
        String currentQuestion = currentQuestion();
        var scopeReply = AccountScopeReply.reply(currentQuestion);
        if (scopeReply.isPresent()) {
            finalAnswer = publishVerified(scopeReply.get());
            setState(AgentState.FINISHED);
            return false;
        }
        if (draftIdentity == null) draftIdentity = new TeamDraftIdentityEvidence(currentQuestion);
        if (teamAnswerEvidence != null && !teamAnswerEvidence.initialized()) {
            teamAnswerEvidence.begin(currentQuestion);
        }
        if (damageAnswerEvidence != null && !damageAnswerEvidence.initialized()) {
            damageAnswerEvidence.begin(currentQuestion);
        }
        if (guideAnswerEvidence != null && !guideAnswerEvidence.initialized()) guideAnswerEvidence.begin(currentQuestion);
        if (savedTeamAnswerEvidence != null && !savedTeamAnswerEvidence.initialized()) savedTeamAnswerEvidence.begin(currentQuestion);
        // 1. 将"下一步提示词"追加到消息列表，引导 AI 主动规划
        if (StrUtil.isNotBlank(getNextStepPrompt())) {
            UserMessage userMessage = new UserMessage(getNextStepPrompt());
            getMessageList().add(userMessage);
        }

        // 2. 调用 AI 大模型，获取工具调用决策
        List<Message> messageList = getMessageList();
        Prompt prompt = new Prompt(messageList, this.chatOptions);
        try {
            if (checkSuppliedTeam(currentQuestion) || checkSuppliedBuild(currentQuestion)) return false;
            ensureProjectGuideSource(currentQuestion);
            prompt = new Prompt(getMessageList(), this.chatOptions);
            var request = getChatClient().prompt(prompt)
                    .system(getSystemPrompt() + (teamAnswerEvidence == null ? "" : teamAnswerEvidence.planningInstruction())
                            + (guideAnswerEvidence == null ? "" : guideAnswerEvidence.planningInstruction())
                            + draftIdentity.instruction())
                    .toolCallbacks(draftIdentity.planningTools(TeamBuildAnswerEvidence.planningTools(currentQuestion,getMergedTools())));
            // Tool selection is private. A model can put planning text in content,
            // not only reasoning_content, so it must never share the answer stream.
            ChatResponse chatResponse = request.call().chatResponse();

            // 成功调用，重置错误计数
            this.consecutiveErrors = 0;

            this.toolCallChatResponse = chatResponse;

            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();

            String thinkText = assistantMessage.getText();
            log.info("{} 的思考：{}", getName(), thinkText);
            log.info("{} 选择了 {} 个工具来使用", getName(), toolCallList.size());
            if (!toolCallList.isEmpty()) {
                String toolCallInfo = toolCallList.stream()
                        .map(tc -> String.format("  工具：%s，参数：%s", tc.name(), tc.arguments()))
                        .collect(Collectors.joining("\n"));
                log.info("工具调用详情：\n{}", toolCallInfo);
            }

            if (toolCallList.isEmpty()) {
                getMessageList().add(assistantMessage);
                if (StrUtil.isNotBlank(thinkText)) {
                    finalAnswer = summarizeAnswer();
                    setState(AgentState.FINISHED);
                }
                return false;
            } else {
                return true;
            }
        } catch (Exception e) {
            for(Throwable cause=e;cause!=null;cause=cause.getCause()) {
                if(cause instanceof com.vio.vioaiagent.observability.ContextBudgetExceededException
                        || cause instanceof com.vio.vioaiagent.observability.TaskBudgetExceededException) {
                    setState(AgentState.FINISHED);
                    return false; // DefaultRunExecutor propagates the recorded budget failure to the Run lifecycle.
                }
                if(cause.getCause()==cause)break;
            }
            if (answerDeltaSink != null) {
                // Never retry a generation that may already have emitted public chunks.
                setState(AgentState.FINISHED);
                log.warn("模型生成失败，停止流式任务: {}", e.getClass().getSimpleName());
                return false;
            }
            consecutiveErrors++;
            log.error("{} 的思考过程遇到问题（连续错误 {}/{}）：{}",
                    getName(), consecutiveErrors, MAX_CONSECUTIVE_ERRORS, e.getMessage());

            // 连续错误超过阈值 → 强制终止，防止无限循环
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                log.error("{} 连续错误达到 {} 次，强制终止 Agent", getName(), MAX_CONSECUTIVE_ERRORS);
                setState(AgentState.FINISHED);
                getMessageList().add(new AssistantMessage("抱歉，多次尝试后仍然无法完成任务："
                        + e.getMessage()));
                return false;
            }

            getMessageList().add(new AssistantMessage("思考时遇到错误：" + e.getMessage()));
            return false;
        }
    }

    private boolean checkSuppliedTeam(String question) {
        if(suppliedTeamAttempted||guideAnswerEvidence==null||toolExecutionGateway==null)return false;
        var selected=TeamBuildRequestRoute.select(question);if(selected.isEmpty())return false;
        var parent=ToolExecutionContextHolder.peek();if(parent==null)return false;
        var callback=Arrays.stream(availableTools).filter(t->"checkPokemonTeamBuild".equals(t.getToolDefinition().name())).findFirst().orElse(null);if(callback==null)return false;
        suppliedTeamAttempted=true;if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Run cancelled");
        String input=TeamBuildRequestRoute.arguments(selected.get());
        var marked=parent.withEventSink(event->{var attributes=new java.util.LinkedHashMap<String,Object>(event.attributes());attributes.put("selectionOrigin",TeamBuildRequestRoute.ORIGIN);parent.eventSink().accept(new com.vio.vioaiagent.tools.governance.ToolGovernanceEvent(event.eventType(),event.runId(),event.toolName(),event.eventId(),event.occurredAt(),attributes));});
        String output=toolExecutionGateway.execute(callback,input,marked).toModelText();guideAnswerEvidence.observe("checkPokemonTeamBuild",output,input);
        String id="supplied-team-"+java.util.UUID.randomUUID();getMessageList().add(new AssistantMessage("",java.util.Map.of("selectionOrigin",TeamBuildRequestRoute.ORIGIN),List.of(new AssistantMessage.ToolCall(id,"function","checkPokemonTeamBuild",input))));
        getMessageList().add(new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(id,"checkPokemonTeamBuild",output))));
        finalAnswer=publishVerified(guideAnswerEvidence.verifiedAnswer());setState(AgentState.FINISHED);return true;
    }

    private boolean checkSuppliedBuild(String question) {
        if (suppliedBuildAttempted || guideAnswerEvidence == null || toolExecutionGateway == null) return false;
        var selected = BuildCheckRequestRoute.select(question); if (selected.isEmpty()) return false;
        var parent = ToolExecutionContextHolder.peek(); if (parent == null) return false;
        var callback = Arrays.stream(availableTools).filter(t -> "checkPokemonBuild".equals(t.getToolDefinition().name())).findFirst().orElse(null);
        if (callback == null) return false;
        suppliedBuildAttempted = true;
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Run cancelled");
        String input = BuildCheckRequestRoute.arguments(selected.get());
        var marked = parent.withEventSink(event -> {
            var attributes = new java.util.LinkedHashMap<String,Object>(event.attributes());
            attributes.put("selectionOrigin",BuildCheckRequestRoute.ORIGIN);
            parent.eventSink().accept(new com.vio.vioaiagent.tools.governance.ToolGovernanceEvent(event.eventType(),event.runId(),event.toolName(),event.eventId(),event.occurredAt(),attributes));
        });
        String output = toolExecutionGateway.execute(callback,input,marked).toModelText();
        guideAnswerEvidence.observe("checkPokemonBuild",output,input);
        String id = "supplied-build-" + java.util.UUID.randomUUID();
        getMessageList().add(new AssistantMessage("",java.util.Map.of("selectionOrigin",BuildCheckRequestRoute.ORIGIN),List.of(new AssistantMessage.ToolCall(id,"function","checkPokemonBuild",input))));
        getMessageList().add(new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(id,"checkPokemonBuild",output))));
        finalAnswer = publishVerified(guideAnswerEvidence.verifiedAnswer()); setState(AgentState.FINISHED); return true;
    }

    private void ensureProjectGuideSource(String question) throws Exception {
        if(projectGuideAttempted||guideAnswerEvidence==null||!ProjectGuideRequest.requiresSource(question))return;
        projectGuideAttempted=true;
        executeGuideSource(question,ProjectGuideRequest.ORIGIN,"project");
    }
    private void ensureSpeciesGuideSource(String question) {
        if(speciesGuideAttempted||guideAnswerEvidence==null)return;
        var query=guideAnswerEvidence.missingSpeciesSourceQuery(question);
        if(query.isEmpty())return;
        speciesGuideAttempted=true;
        executeGuideSource(query.get(),GuideAnswerEvidence.SUBJECT_SOURCE_ORIGIN,"species");
    }
    private void executeGuideSource(String question,String origin,String purpose) {
        if(!"pokemon-scarlet-violet".equals(guideGameReleaseId)||toolExecutionGateway==null)return;
        ToolExecutionContext parent=ToolExecutionContextHolder.peek();
        if(parent==null)return;
        ToolCallback guide=Arrays.stream(availableTools).filter(tool->"searchPokemonGuides".equals(tool.getToolDefinition().name())).findFirst().orElse(null);
        if(guide==null)return;
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Run cancelled");
        String input;
        try {input=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("query",question,"gameReleaseId",guideGameReleaseId));}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("攻略检索请求编码失败",invalid);}
        var marked=parent.withEventSink(event->{
            var attributes=new java.util.LinkedHashMap<String,Object>(event.attributes());
            attributes.put("selectionOrigin",origin);
            parent.eventSink().accept(new com.vio.vioaiagent.tools.governance.ToolGovernanceEvent(event.eventType(),event.runId(),event.toolName(),event.eventId(),event.occurredAt(),attributes));
        });
        // Existing registered callback, same gateway/context/lease; no second registration or ungoverned invocation.
        String output=toolExecutionGateway.execute(guide,input,marked).toModelText();
        guideAnswerEvidence.observe("searchPokemonGuides",output);
        String id=purpose+"-guide-"+java.util.UUID.randomUUID();
        getMessageList().add(new AssistantMessage("",java.util.Map.of("selectionOrigin",origin),List.of(
                new AssistantMessage.ToolCall(id,"function","searchPokemonGuides",input))));
        getMessageList().add(new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(id,"searchPokemonGuides",output))));
    }
    private void ensureOverviewResources(String question) {
        if(guideAnswerEvidence==null)return;
        for(var request:guideAnswerEvidence.missingOverviewResources(question)) {
            if(overviewResourceAttempts.size()>=OverviewResourceCoverage.MAX_PROGRAM_READS&&!overviewResourceAttempts.contains(request.kind())) {
                guideAnswerEvidence.overviewResourceUnavailable(request.kind());continue;
            }
            if(!overviewResourceAttempts.add(request.kind()))continue;
            var parent=ToolExecutionContextHolder.peek();
            var callback=Arrays.stream(availableTools).filter(t->"lookupPokemonResource".equals(t.getToolDefinition().name())).findFirst().orElse(null);
            if(!"pokemon-scarlet-violet".equals(guideGameReleaseId)||toolExecutionGateway==null||parent==null||callback==null) {
                guideAnswerEvidence.overviewResourceUnavailable(request.kind());continue;
            }
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Run cancelled");
            String input;
            try{input=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("kind",request.kind(),"slug",request.slug(),"dataReleaseId",request.dataReleaseId()));}
            catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("资源查询请求编码失败",invalid);}
            var marked=parent.withEventSink(event->{
                var attributes=new java.util.LinkedHashMap<String,Object>(event.attributes());attributes.put("selectionOrigin",OverviewResourceCoverage.ORIGIN);
                parent.eventSink().accept(new com.vio.vioaiagent.tools.governance.ToolGovernanceEvent(event.eventType(),event.runId(),event.toolName(),event.eventId(),event.occurredAt(),attributes));
            });
            String output=toolExecutionGateway.execute(callback,input,marked).toModelText();
            guideAnswerEvidence.observe("lookupPokemonResource",output,input);
            String id="overview-resource-"+java.util.UUID.randomUUID();
            getMessageList().add(new AssistantMessage("",java.util.Map.of("selectionOrigin",OverviewResourceCoverage.ORIGIN),List.of(new AssistantMessage.ToolCall(id,"function","lookupPokemonResource",input))));
            getMessageList().add(new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(id,"lookupPokemonResource",output))));
        }
    }

    // ==================== Act：行动阶段 ====================

    @Override
    protected String noActionResult() {
        if (getState() == AgentState.FINISHED && toolCallChatResponse != null
                && !toolCallChatResponse.hasToolCalls()) {
            String answer = finalAnswer;
            if (StrUtil.isNotBlank(answer)) return answer;
        }
        return super.noActionResult();
    }

    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            return "没有工具需要调用";
        }

        // 0. 注入工具调用 span 到 MDC
        org.slf4j.MDC.put("stepType", "act");

        // 1. 执行工具调用
        Prompt prompt = new Prompt(getMessageList(), this.chatOptions);
        // RunService 会在异步 worker 中预置完整上下文；不要再次从 RequestContext 生成新的 run/trace。
        // 兼容旧版 /ai/manus 端点：它不经过 Run Service 时没有预置上下文，
        // 仍然要生成受限的请求级上下文，不能因为治理接入而 NPE。
        ToolExecutionContext previous = ToolExecutionContextHolder.peek();
        ToolExecutionContext inherited = ToolExecutionContextHolder.current();
        if (inherited == null) {
            inherited = ToolExecutionContext.fromRequest();
        }
        ToolExecutionContext parentContext = inherited;
        AtomicBoolean approvalRequired = new AtomicBoolean(false);
        ToolExecutionContext context = parentContext.withEventSink(event -> {
            if ("approval.required".equals(event.eventType())) {
                approvalRequired.set(true);
            }
            // 保留上游 Run 事件接收器，再写一条调试日志。
            parentContext.eventSink().accept(event);
            log.debug("工具治理事件: {} {}", event.eventType(), event.toolName());
        });
        if (draftIdentity != null) context = context.withInputConstraint(draftIdentity::verify);
        ToolExecutionContextHolder.set(context);
        ToolExecutionResult toolExecutionResult;
        try {
            toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        } finally {
            if (previous == null) ToolExecutionContextHolder.clear();
            else ToolExecutionContextHolder.set(previous);
        }

        // HITL 不是普通工具文本：暂停当前 Agent loop，由 RunService 将 Run 置为
        // WAITING_APPROVAL；审批通过后从同一 Run 重新调度，拒绝则进入失败终态。
        if (approvalRequired.get()) {
            setState(AgentState.FINISHED);
        }

        // 2. 更新消息上下文
        setMessageList(ToolHistoryIntegrity.sanitize(toolExecutionResult.conversationHistory()));

        // 3. 检查是否调用了终止工具
        ToolResponseMessage toolResponseMessage =
                (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
        if (draftIdentity != null) toolResponseMessage.getResponses().forEach(response ->
                draftIdentity.observe(response.name(), response.responseData(),
                        toolCallChatResponse.getResult().getOutput().getToolCalls().stream()
                                .filter(call -> call.id().equals(response.id()) && call.name().equals(response.name()))
                                .map(AssistantMessage.ToolCall::arguments).findFirst().orElse(null)));
        if (teamAnswerEvidence != null) toolResponseMessage.getResponses().forEach(response ->
                teamAnswerEvidence.observe(response.name(), response.responseData()));
        if (damageAnswerEvidence != null) toolResponseMessage.getResponses().forEach(response ->
                damageAnswerEvidence.observe(response.name(), response.responseData()));
        if (guideAnswerEvidence != null) toolResponseMessage.getResponses().forEach(response ->
                guideAnswerEvidence.observe(response.name(), response.responseData(),
                        toolCallChatResponse.getResult().getOutput().getToolCalls().stream()
                                .filter(call -> call.id().equals(response.id()) && call.name().equals(response.name()))
                                .map(AssistantMessage.ToolCall::arguments).findFirst().orElse(null)));
        if (savedTeamAnswerEvidence != null) toolResponseMessage.getResponses().forEach(response ->
                savedTeamAnswerEvidence.observe(response.name(), response.responseData()));
        if (teamWorkflow != null) toolResponseMessage.getResponses().forEach(response ->
                teamWorkflow.observeCalculation(response.name(), response.responseData()));
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> "doTerminate".equals(response.name()));
        boolean overviewPublished = false;

        if(!terminateToolCalled&&!approvalRequired.get()&&guideAnswerEvidence!=null
                &&(teamAnswerEvidence==null||!teamAnswerEvidence.active())
                &&(damageAnswerEvidence==null||!damageAnswerEvidence.active())
                &&(savedTeamAnswerEvidence==null||!savedTeamAnswerEvidence.complete()&&!savedTeamAnswerEvidence.ownsClarification())) {
            ensureSpeciesGuideSource(currentQuestion());
            ensureOverviewResources(currentQuestion());
        }

        if (terminateToolCalled) {
            setState(AgentState.FINISHED);
            log.info("{} 调用了终止工具，任务正常结束", getName());
            if (!approvalRequired.get() && getChatClient() != null) {
                finalAnswer = summarizeAnswer();
            }
        } else if (!approvalRequired.get() && guideAnswerEvidence != null
                && guideAnswerEvidence.overviewReady(currentQuestion())
                && (teamAnswerEvidence == null || !teamAnswerEvidence.active())
                && (damageAnswerEvidence == null || !damageAnswerEvidence.active())
                && (savedTeamAnswerEvidence == null || !savedTeamAnswerEvidence.complete() && !savedTeamAnswerEvidence.ownsClarification())) {
            // This bounded source-only answer is already determined by validated facts and
            // guide text. Do not spend more planning calls collecting unrelated type tables.
            finalAnswer = publishVerified(guideAnswerEvidence.overviewAnswer());
            overviewPublished = true;
            setState(AgentState.FINISHED);
        }

        // 4. 格式化返回结果（截断过长的输出）
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> {
                    String data = response.responseData();
                    String truncated = truncateIfNeeded(data);
                    return "工具 " + response.name() + " 返回结果：" + truncated;
                })
                .collect(Collectors.joining("\n"));
        log.info("工具执行结果：\n{}", results);
        return overviewPublished ? finalAnswer : results;
    }

    /** No tools are registered for this final synthesis: it cannot perform another action. */
    protected String summarizeAnswer() {
        if (savedTeamAnswerEvidence != null && savedTeamAnswerEvidence.ownsClarification()
                && (damageAnswerEvidence == null || !damageAnswerEvidence.complete())) {
            return publishVerified(savedTeamAnswerEvidence.verifiedClarification()
                    + (guideAnswerEvidence != null && guideAnswerEvidence.active() ? "\n" + guideAnswerEvidence.verifiedAnswer(false) : ""));
        }
        boolean guardedTeam = teamAnswerEvidence != null && teamAnswerEvidence.active();
        boolean guardedDamage = damageAnswerEvidence != null && damageAnswerEvidence.active();
        boolean guardedSaved = savedTeamAnswerEvidence != null && savedTeamAnswerEvidence.complete();
        boolean guarded = guardedTeam || guardedDamage || guardedSaved;
        if (guardedTeam && !teamAnswerEvidence.complete()) {
            String answer = teamAnswerEvidence.missingAnswer();
            if (answerDeltaSink != null) publishDelta(answer);
            return answer;
        }
        if (!guardedTeam && guardedDamage && !damageAnswerEvidence.complete()) {
            String answer = damageAnswerEvidence.missingAnswer();
            if (answerDeltaSink != null) publishDelta(answer);
            return answer;
        }
        String verifiedPrefix = (guardedSaved ? savedTeamAnswerEvidence.verifiedSummary() + "\n" : "")
                + (guardedTeam ? teamAnswerEvidence.verifiedSummary() + "\n" : "")
                + (guardedDamage ? (damageAnswerEvidence.complete() ? damageAnswerEvidence.verifiedSummary() : damageAnswerEvidence.missingAnswer()) + "\n" : "");
        if (guideAnswerEvidence != null && guideAnswerEvidence.active()) {
            // Search success cannot authorize arbitrary prose to inherit citations.
            // Preserve independently checked team/calculation facts for mixed tasks.
            return publishVerified(verifiedPrefix + guideAnswerEvidence.verifiedAnswer(!guardedTeam && !guardedSaved));
        }
        if (guarded && answerDeltaSink != null) publishDelta(verifiedPrefix);
        var request = getChatClient().prompt()
                .system(getSystemPrompt())
                .messages(getMessageList())
                .user("现在只输出给用户阅读的最终回答。遵循 Run language，未指定时使用简体中文。"
                        + "直接给出介绍和可执行建议，不要说‘我已经有足够资料’‘接下来给出答案’，"
                        + "不要输出内部计划、推理过程或对工具行为的猜测。"
                        + "不要展示 JSON、checksum、UUID、内部版本标识、run/trace 编号；"
                        + "保留有意义的种族值、努力值、伤害范围和简短来源名称。"
                        + "确实需要完整介绍或培养时，才按需使用训练家笔记栏目，明确区分资料事实与策略建议；"
                        + "属性和配置只填写实际已知信息，不为填充样式编造内容。"
                        + "结合已有工具结果给出最终总结，"
                        + "不要输出执行日志或任务结束提示。明确说明搜索失败、数据缺失和时效不确定性；"
                        + "不要把属性表当成当前赛季强度证据，不要捏造排名、引用或工具执行成功。"
                        + (guardedTeam ? teamAnswerEvidence.finalInstruction() : "")
                        + (guardedSaved ? savedTeamAnswerEvidence.finalInstruction() : "")
                        + (guardedDamage && damageAnswerEvidence.complete() ? damageAnswerEvidence.finalInstruction() : "")
                        + AnswerPresentation.instruction(AnswerPresentation.resolve(currentQuestion(),responseStyle)));
        String answer;
        if (guarded) {
            // Do not publish unverified strategy fragments. Evidence is already visible;
            // release coaching only after the bounded mechanism checks have completed.
            String draft = request.call().content();
            if (StrUtil.isBlank(draft)) throw new IllegalStateException("模型未返回最终回答");
            var advicePolicy = teamAnswerEvidence == null ? new TeamAnswerEvidence() : teamAnswerEvidence;
            String advice = advicePolicy.checkedAdvice(draft);
            if (guardedDamage && damageAnswerEvidence.complete() && !guardedTeam && !guardedSaved
                    && (!advicePolicy.canPublishAdvice(draft) || !DamageAdvicePolicy.canPublish(draft))) {
                // Reject the model's prose; derive a useful replacement from verified numbers only.
                advice = damageAnswerEvidence.verifiedInterpretation();
            }
            if (guardedSaved) advice = savedTeamAnswerEvidence.checkedAdvice(advice);
            if (answerDeltaSink != null) publishDelta(advice);
            answer = verifiedPrefix + advice;
        } else if (answerDeltaSink == null) {
            answer = request.call().content();
        } else {
            ChatResponse response = streamResponse(request);
            if (StrUtil.isBlank(response.getResult().getOutput().getText())) {
                throw new IllegalStateException("模型未返回最终回答");
            }
            answer = streamedAnswer.toString();
        }
        if (StrUtil.isBlank(answer)) throw new IllegalStateException("模型未返回最终回答");
        return answer;
    }

    private String publishVerified(String answer) {
        if (answerDeltaSink != null) for (String block : answer.split("(?<=\\n\\n)")) if (!block.isEmpty()) publishDelta(block);
        return answer;
    }

    private String currentQuestion() {
        return currentUserRequest!=null?currentUserRequest:getMessageList().stream().filter(message->message instanceof UserMessage)
                .reduce((first,last)->last).map(Message::getText).orElse("");
    }

    /** Stream the dedicated final answer only; planning content is never public.
     * Read on the run worker so thread-local leases and governance remain intact. */
    private ChatResponse streamResponse(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec request) {
        StringBuilder turn = new StringBuilder();
        var calls = new java.util.LinkedHashMap<String, AssistantMessage.ToolCall>();
        boolean separated = false;
        try (var chunks = request.stream().chatResponse()
                .timeout(java.time.Duration.ofSeconds(60)).toStream()) {
            var iterator = chunks.iterator();
            while (iterator.hasNext()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException("Run cancelled");
                }
                ChatResponse chunk = iterator.next();
                if (chunk.getResult() == null) continue;
                AssistantMessage output = chunk.getResult().getOutput();
                for (var call : output.getToolCalls()) {
                    if (call.id() == null || call.id().isBlank() || call.name() == null || call.name().isBlank()) {
                        throw new IllegalStateException("工具调用片段不完整");
                    }
                    calls.put(call.id(), call);
                }
                String delta = output.getText();
                if (delta == null || delta.isEmpty()) continue;
                if (!separated && !streamedAnswer.isEmpty()) publishDelta("\n\n");
                separated = true;
                publishDelta(delta);
                turn.append(delta);
            }
        }
        if (turn.isEmpty() && calls.isEmpty()) throw new IllegalStateException("模型返回空内容");
        return new ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
                new AssistantMessage(turn.toString(), java.util.Map.of(), List.copyOf(calls.values())))));
    }

    private void publishDelta(String delta) {
        answerDeltaSink.accept(delta);
        streamedAnswer.append(delta);
    }

    /**
     * 截断过长的工具输出，防止塞爆 AI 上下文窗口
     */
    private String truncateIfNeeded(String text) {
        if (text == null) {
            return "(empty)";
        }
        if (text.length() <= MAX_TOOL_OUTPUT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TOOL_OUTPUT_LENGTH)
                + "...(truncated, total " + text.length() + " chars)";
    }

    @Override
    protected void cleanup() {
        this.consecutiveErrors = 0;
        this.draftIdentity = null;
        super.cleanup();
    }
}
