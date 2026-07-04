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

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具调用智能体
 * <p>
 * 具体实现了 ReActAgent 的 {@link #think()} 和 {@link #act()} 方法，
 * 具备完整的工具调用能力。可作为具体智能体实例（如 VioManus）的直接父类。
 * <p>
 * 核心设计决策：
 * <ul>
 *   <li><b>禁用 Spring AI 内置工具执行</b>（withInternalToolExecutionEnabled = false）：
 *       自主管理消息上下文和工具调用流程，保持对 Agent Loop 的完全控制</li>
 *   <li><b>手动维护 messageList</b>：ToolCallingManager.executeToolCalls() 返回的
 *       conversationHistory 包含完整的助手消息 + 工具返回消息，直接替换当前的 messageList</li>
 *   <li><b>TerminateTool 检测</b>：act() 中检查是否调用了 doTerminate 工具，
 *       若是则触发 state = FINISHED，优雅结束 Agent Loop</li>
 * </ul>
 *
 * @author vio
 * @see VioManus
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

    // ==================== 工具系统 ====================

    /**
     * 可用工具列表（由 ToolRegistration 注册的 Bean 注入）
     */
    private final ToolCallback[] availableTools;

    /**
     * 当前步骤的 AI 工具调用响应（think 阶段产生，act 阶段消费）
     */
    private ChatResponse toolCallChatResponse;

    /**
     * 工具调用管理器（手动控制工具执行，不使用 Spring AI 内置机制）
     */
    private final ToolCallingManager toolCallingManager;

    /**
     * 对话选项 — 禁用 Spring AI 内置工具执行
     */
    private final ChatOptions chatOptions;

    // ==================== 构造器 ====================

    /**
     * 构造工具调用智能体
     *
     * @param availableTools 可用工具列表
     */
    public ToolCallAgent(ToolCallback[] availableTools) {
        super();
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder().build();
        // 关键：禁用 Spring AI 内置的工具调用机制，自主管理消息上下文和执行流程
        this.chatOptions = DashScopeChatOptions.builder()
                .withInternalToolExecutionEnabled(false)
                .build();
    }

    // ==================== Think：推理阶段 ====================

    /**
     * 思考阶段：调用 AI 大模型，让 AI 根据当前上下文决定是否需要调用工具
     * <p>
     * 执行流程：
     * <ol>
     *   <li>将 nextStepPrompt 追加到消息列表（引导 AI 主动规划下一步）</li>
     *   <li>调用 ChatClient，传入系统提示词 + 完整消息历史 + 可用工具列表</li>
     *   <li>解析 AI 返回的 AssistantMessage，提取 ToolCall 列表</li>
     *   <li>有工具调用 → 保存响应，返回 true</li>
     *   <li>无工具调用 → 记录 AI 的文本回复，返回 false（Agent Loop 继续下一轮）</li>
     * </ol>
     *
     * @return true 表示 AI 决定调用工具（需要进入 act 阶段），false 表示无需行动
     */
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
                    .tools(availableTools)
                    .call()
                    .chatResponse();

            // 保存响应，供 act() 阶段使用
            this.toolCallChatResponse = chatResponse;

            // 3. 解析工具调用结果
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();

            // 日志：记录 AI 的思考内容
            String thinkText = assistantMessage.getText();
            log.info("{} 的思考：{}", getName(), thinkText);
            log.info("{} 选择了 {} 个工具来使用", getName(), toolCallList.size());
            if (!toolCallList.isEmpty()) {
                String toolCallInfo = toolCallList.stream()
                        .map(tc -> String.format("  工具：%s，参数：%s", tc.name(), tc.arguments()))
                        .collect(Collectors.joining("\n"));
                log.info("工具调用详情：\n{}", toolCallInfo);
            }

            // 4. 判断是否需要行动
            if (toolCallList.isEmpty()) {
                // 无工具调用：AI 给出了纯文本回复，记录到消息历史
                getMessageList().add(assistantMessage);
                return false;
            } else {
                // 有工具调用：不需要手动记录 assistantMessage，
                // ToolCallingManager.executeToolCalls() 会自动处理消息追加
                return true;
            }
        } catch (Exception e) {
            log.error("{} 的思考过程遇到问题：{}", getName(), e.getMessage());
            getMessageList().add(new AssistantMessage("思考时遇到错误：" + e.getMessage()));
            return false;
        }
    }

    // ==================== Act：行动阶段 ====================

    /**
     * 行动阶段：执行 think() 阶段 AI 决定的工具调用
     * <p>
     * 执行流程：
     * <ol>
     *   <li>通过 ToolCallingManager 执行 AI 选择的所有工具</li>
     *   <li>用 conversationHistory 替换当前 messageList（包含助手消息 + 工具返回消息）</li>
     *   <li>检测是否调用了 doTerminate 终止工具</li>
     *   <li>若是 → 设置 state = FINISHED，Agent Loop 下一轮检查时自然退出</li>
     *   <li>返回工具执行结果描述</li>
     * </ol>
     *
     * @return 工具执行结果
     */
    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            return "没有工具需要调用";
        }

        // 1. 执行工具调用
        Prompt prompt = new Prompt(getMessageList(), this.chatOptions);
        ToolExecutionResult toolExecutionResult =
                toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);

        // 2. 更新消息上下文 — conversationHistory 已包含：
        //    用户消息 + 助手消息（含 ToolCall）+ 工具返回消息
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

        // 4. 格式化返回结果
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "工具 " + response.name() + " 返回结果：" + response.responseData())
                .collect(Collectors.joining("\n"));
        log.info("工具执行结果：\n{}", results);
        return results;
    }
}