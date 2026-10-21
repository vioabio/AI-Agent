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

    /** Invoked on the run worker thread, preserving lease and governance context. */
    private java.util.function.Consumer<String> answerDeltaSink;
    private final StringBuilder streamedAnswer = new StringBuilder();

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
        // 1. 将"下一步提示词"追加到消息列表，引导 AI 主动规划
        if (StrUtil.isNotBlank(getNextStepPrompt())) {
            UserMessage userMessage = new UserMessage(getNextStepPrompt());
            getMessageList().add(userMessage);
        }

        // 2. 调用 AI 大模型，获取工具调用决策
        List<Message> messageList = getMessageList();
        Prompt prompt = new Prompt(messageList, this.chatOptions);
        try {
            var request = getChatClient().prompt(prompt)
                    .system(getSystemPrompt())
                    .toolCallbacks(getMergedTools());
            ChatResponse chatResponse = answerDeltaSink == null
                    ? request.call().chatResponse() : streamResponse(request);

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
                    finalAnswer = answerDeltaSink == null ? thinkText : streamedAnswer.toString();
                    setState(AgentState.FINISHED);
                }
                return false;
            } else {
                return true;
            }
        } catch (Exception e) {
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
        setMessageList(new java.util.ArrayList<>(toolExecutionResult.conversationHistory()));

        // 3. 检查是否调用了终止工具
        ToolResponseMessage toolResponseMessage =
                (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> "doTerminate".equals(response.name()));

        if (terminateToolCalled) {
            setState(AgentState.FINISHED);
            log.info("{} 调用了终止工具，任务正常结束", getName());
            if (!approvalRequired.get() && getChatClient() != null) {
                finalAnswer = summarizeAnswer();
            }
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
        return results;
    }

    /** No tools are registered for this final synthesis: it cannot perform another action. */
    protected String summarizeAnswer() {
        var request = getChatClient().prompt()
                .system(getSystemPrompt())
                .messages(getMessageList())
                .user("请直接回答用户原始问题，使用用户的语言。结合已有工具结果给出最终总结，"
                        + "不要输出执行日志或任务结束提示。明确说明搜索失败、数据缺失和时效不确定性；"
                        + "不要把属性表当成当前赛季强度证据，不要捏造排名、引用或工具执行成功。");
        String answer;
        if (answerDeltaSink == null) {
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

    /** Stream once for both tool selection and direct answers. Tool argument chunks are
     * assembled by the provider adapter; only content (never reasoning_content) is public.
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
        super.cleanup();
    }
}
