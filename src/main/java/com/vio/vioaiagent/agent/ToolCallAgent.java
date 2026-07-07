package com.vio.vioaiagent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.vio.vioaiagent.agent.model.AgentState;
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

    private ChatResponse toolCallChatResponse;

    private ToolCallingManager toolCallingManager;

    private final ChatOptions chatOptions;

    /** 连续错误计数器 */
    private int consecutiveErrors = 0;

    // ==================== 构造器 ====================

    public ToolCallAgent(ToolCallback[] availableTools) {
        super();
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.chatOptions = DashScopeChatOptions.builder()
                .withInternalToolExecutionEnabled(false)
                .build();
    }

    public void setToolCallbackProvider(ToolCallbackProvider toolCallbackProvider) {
        this.toolCallbackProvider = toolCallbackProvider;
    }

    private ToolCallback[] getMergedTools() {
        if (toolCallbackProvider == null) {
            return availableTools;
        }
        ToolCallback[] mcpTools = toolCallbackProvider.getToolCallbacks();
        if (mcpTools == null || mcpTools.length == 0) {
            return availableTools;
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
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .system(getSystemPrompt())
                    .toolCallbacks(getMergedTools())
                    .call()
                    .chatResponse();

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
                return false;
            } else {
                return true;
            }
        } catch (Exception e) {
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
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            return "没有工具需要调用";
        }

        // 1. 执行工具调用
        Prompt prompt = new Prompt(getMessageList(), this.chatOptions);
        ToolExecutionResult toolExecutionResult =
                toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);

        // 2. 更新消息上下文
        setMessageList(toolExecutionResult.conversationHistory());

        // 3. 检查是否调用了终止工具
        ToolResponseMessage toolResponseMessage =
                (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> "doTerminate".equals(response.name()));

        if (terminateToolCalled) {
            setState(AgentState.FINISHED);
            log.info("{} 调用了终止工具，任务正常结束", getName());
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
