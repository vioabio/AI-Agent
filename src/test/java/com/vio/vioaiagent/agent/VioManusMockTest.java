package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.agent.model.AgentState;
import com.vio.vioaiagent.tools.TerminateTool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.DefaultToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * VioManus Agent Mock 测试 — 不依赖真实 AI 调用
 * <p>
 * 覆盖智能体的状态机、终止条件、边界条件和错误处理：
 * <ul>
 *   <li>状态转换：IDLE → RUNNING → FINISHED / ERROR</li>
 *   <li>终止工具检测：doTerminate → FINISHED</li>
 *   <li>边界条件：空输入、非 IDLE 启动、最大步数限制</li>
 *   <li>错误恢复：think/act 阶段异常</li>
 * </ul>
 * <p>
 * 使用三类测试策略：
 * <ol>
 *   <li><b>Stub Agent</b>：继承 BaseAgent 模拟 step()，测试状态机骨架</li>
 *   <li><b>Mock ToolCallingManager</b>：Mockito 模拟工具执行，验证终止检测逻辑</li>
 *   <li><b>直接状态注入</b>：验证非 IDLE 状态保护</li>
 * </ol>
 *
 * @author vio
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VioManus Mock 测试（无 AI 依赖）")
class VioManusMockTest {

    // ==================== 1. BaseAgent 状态机测试（Stub Agent） ====================

    /**
     * 用于测试状态机的桩 Agent：模拟多步骤执行，可控制何时结束和是否抛异常。
     * 继承 BaseAgent，不需要任何 AI 依赖。
     */
    static class StateTestAgent extends BaseAgent {
        private int stepCount = 0;
        private final int finishAfterSteps;
        private boolean shouldThrow = false;

        StateTestAgent(String name, int finishAfterSteps) {
            this.setName(name);
            this.finishAfterSteps = finishAfterSteps;
            this.setMaxSteps(10);
        }

        StateTestAgent(String name, int finishAfterSteps, int maxSteps) {
            this.setName(name);
            this.finishAfterSteps = finishAfterSteps;
            this.setMaxSteps(maxSteps);
        }

        void setShouldThrow(boolean shouldThrow) {
            this.shouldThrow = shouldThrow;
        }

        @Override
        public String step() {
            if (shouldThrow) {
                throw new RuntimeException("模拟 step 异常");
            }
            stepCount++;
            if (stepCount >= finishAfterSteps) {
                setState(AgentState.FINISHED);
                return "任务完成（第 " + stepCount + " 步）";
            }
            return "第 " + stepCount + " 步执行中...";
        }
    }

    @Test
    @DisplayName("状态机 — IDLE → RUNNING → FINISHED 正常流程")
    void testStateTransitionIdleToFinished() {
        StateTestAgent agent = new StateTestAgent("TestAgent", 3);
        Assertions.assertEquals(AgentState.IDLE, agent.getState());

        String result = agent.run("测试任务");
        Assertions.assertEquals(AgentState.FINISHED, agent.getState());
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.contains("Step 1"));
        Assertions.assertTrue(result.contains("Step 2"));
        Assertions.assertTrue(result.contains("Step 3"));
        Assertions.assertTrue(result.contains("任务完成"));
    }

    @Test
    @DisplayName("状态机 — 非 IDLE 状态启动应抛异常")
    void testCannotRunFromNonIdleState() {
        StateTestAgent agent = new StateTestAgent("TestAgent", 1);
        agent.run("第一次");
        Assertions.assertEquals(AgentState.FINISHED, agent.getState());

        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> agent.run("第二次"));
        Assertions.assertTrue(ex.getMessage().contains("IDLE"));
    }

    @Test
    @DisplayName("状态机 — 空 prompt 启动应抛异常")
    void testEmptyPromptRejected() {
        StateTestAgent agent = new StateTestAgent("TestAgent", 1);

        Assertions.assertThrows(RuntimeException.class,
                () -> agent.run(""),
                "空字符串应抛出异常");

        Assertions.assertThrows(RuntimeException.class,
                () -> agent.run(null),
                "null 应抛出异常");
    }

    @Test
    @DisplayName("状态机 — 达到最大步数强制终止")
    void testMaxStepsEnforced() {
        StateTestAgent agent = new StateTestAgent("TestAgent", 100, 3);
        String result = agent.run("需要很多步的任务");

        Assertions.assertEquals(AgentState.FINISHED, agent.getState());
        Assertions.assertTrue(result.contains("已达到最大步数限制"));
        Assertions.assertTrue(result.contains("3"));
    }

    @Test
    @DisplayName("状态机 — step 异常时状态变为 ERROR")
    void testErrorStateOnStepException() {
        StateTestAgent agent = new StateTestAgent("TestAgent", 1);
        agent.setShouldThrow(true);
        String result = agent.run("触发异常");

        Assertions.assertEquals(AgentState.ERROR, agent.getState());
        Assertions.assertTrue(result.contains("执行错误"));
        Assertions.assertTrue(result.contains("模拟 step 异常"));
    }

    @Test
    @DisplayName("状态机 — 正好在最后一步完成任务")
    void testExactStepCount() {
        // 刚好 3 步完成，maxSteps 也是 3
        // 注意：BaseAgent 在循环结束后会检查 currentStep >= maxSteps，
        // 即使 Agent 自然完成也会触发安全阀提示
        StateTestAgent agent = new StateTestAgent("TestAgent", 3, 3);
        String result = agent.run("精确 3 步");

        Assertions.assertEquals(AgentState.FINISHED, agent.getState());
        Assertions.assertTrue(result.contains("Step 3"));
        Assertions.assertTrue(result.contains("任务完成"));
    }

    // ==================== 2. ToolCallAgent 终止检测（Mock ToolCallingManager） ====================

    @Mock
    private ToolCallingManager mockToolCallingManager;

    private ToolCallback[] toolsWithTerminate;

    @BeforeEach
    void setUpTools() {
        toolsWithTerminate = ToolCallbacks.from(new TerminateTool());
    }

    /**
     * 创建带 Mock ToolCallingManager 的 ToolCallAgent
     */
    private ToolCallAgent createAgentWithMockManager() {
        ToolCallAgent agent = new ToolCallAgent(toolsWithTerminate);
        agent.setName("TestTerminateAgent");
        agent.setSystemPrompt("You are a test agent.");
        // 注入 mock ToolCallingManager，绕过真实的工具执行
        agent.setToolCallingManager(mockToolCallingManager);
        return agent;
    }

    @Test
    @DisplayName("终止检测 — doTerminate 调用后 state 变为 FINISHED")
    void testTerminateToolSetsStateToFinished() {
        // Given: Agent with mock ToolCallingManager
        ToolCallAgent agent = createAgentWithMockManager();

        // 构造 ToolResponseMessage 含 doTerminate
        ToolResponseMessage.ToolResponse terminateResponse =
                new ToolResponseMessage.ToolResponse("resp_1", "doTerminate", "任务结束");
        ToolResponseMessage toolResponseMsg =
                new ToolResponseMessage(List.of(terminateResponse));

        // conversationHistory: AssistantMessage(含 ToolCall) + ToolResponseMessage
        AssistantMessage.ToolCall terminateCall = new AssistantMessage.ToolCall(
                "call_term", "function", "doTerminate", "{}");
        AssistantMessage assistantMsg = new AssistantMessage(
                "任务完成", Map.of(), List.of(terminateCall));
        List<Message> history = List.of(assistantMsg, toolResponseMsg);

        // Mock ToolCallingManager 返回预构建的 ToolExecutionResult
        ToolExecutionResult mockResult = new DefaultToolExecutionResult(history, false);
        when(mockToolCallingManager.executeToolCalls(any(), any())).thenReturn(mockResult);

        // 构造 ChatResponse 含 doTerminate 工具调用
        Generation generation = new Generation(assistantMsg);
        ChatResponse chatResponse = new ChatResponse(List.of(generation));
        agent.setToolCallChatResponse(chatResponse);

        // When
        String result = agent.act();

        // Then
        Assertions.assertEquals(AgentState.FINISHED, agent.getState(),
                "调用 doTerminate 后状态应为 FINISHED");
        Assertions.assertTrue(result.contains("doTerminate"),
                "结果中应包含 doTerminate 工具名，实际: " + result);
        Assertions.assertTrue(result.contains("任务结束"),
                "结果中应包含 TerminateTool 的返回值，实际: " + result);
    }

    @Test
    @DisplayName("终止检测 — 无工具调用时 act 返回提示")
    void testActWithoutToolCalls() {
        ToolCallAgent agent = createAgentWithMockManager();

        // 构造无 ToolCall 的 ChatResponse
        ChatResponse emptyResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("直接回答，无工具调用"))));
        agent.setToolCallChatResponse(emptyResponse);

        String result = agent.act();

        Assertions.assertTrue(result.contains("没有工具需要调用"),
                "无工具调用时应返回提示");
        Assertions.assertEquals(AgentState.IDLE, agent.getState(),
                "无工具调用时状态不应改变");
    }

    @Test
    @DisplayName("终止检测 — 非终止工具不触发 FINISHED")
    void testNonTerminateToolKeepsRunning() {
        ToolCallAgent agent = createAgentWithMockManager();
        agent.setState(AgentState.RUNNING);  // 模拟 think 已执行

        // 构造 ToolResponseMessage 含非终止工具（如 fileWrite）
        ToolResponseMessage.ToolResponse fileResponse =
                new ToolResponseMessage.ToolResponse("resp_f1", "writeFile", "文件写入成功");
        ToolResponseMessage toolResponseMsg =
                new ToolResponseMessage(List.of(fileResponse));

        AssistantMessage.ToolCall fileCall = new AssistantMessage.ToolCall(
                "call_file", "function", "writeFile", "{\"path\":\"test.txt\"}");
        AssistantMessage assistantMsg = new AssistantMessage(
                "正在写入文件", Map.of(), List.of(fileCall));
        List<Message> history = List.of(assistantMsg, toolResponseMsg);

        ToolExecutionResult mockResult = new DefaultToolExecutionResult(history, false);
        when(mockToolCallingManager.executeToolCalls(any(), any())).thenReturn(mockResult);

        Generation generation = new Generation(assistantMsg);
        ChatResponse chatResponse = new ChatResponse(List.of(generation));
        agent.setToolCallChatResponse(chatResponse);

        // When
        String result = agent.act();

        // Then: 非终止工具不应设置 FINISHED
        Assertions.assertNotEquals(AgentState.FINISHED, agent.getState(),
                "writeFile 不应触发 FINISHED");
        Assertions.assertTrue(result.contains("writeFile"),
                "结果中应包含工具名，实际: " + result);
    }

    @Test
    @DisplayName("终止检测 — 多工具混合，doTerminate 触发终止")
    void testMultipleToolCallsWithTerminate() {
        ToolCallAgent agent = createAgentWithMockManager();

        // 模拟 writeFile + doTerminate 两个工具被调用
        ToolResponseMessage.ToolResponse fileResponse =
                new ToolResponseMessage.ToolResponse("resp_f2", "writeFile", "写入成功");
        ToolResponseMessage.ToolResponse terminateResponse =
                new ToolResponseMessage.ToolResponse("resp_1", "doTerminate", "任务结束");
        ToolResponseMessage toolResponseMsg =
                new ToolResponseMessage(List.of(fileResponse, terminateResponse));

        AssistantMessage.ToolCall fileCall = new AssistantMessage.ToolCall(
                "call_file", "function", "writeFile", "{}");
        AssistantMessage.ToolCall termCall = new AssistantMessage.ToolCall(
                "call_term", "function", "doTerminate", "{}");
        AssistantMessage assistantMsg = new AssistantMessage(
                "全部完成", Map.of(), List.of(fileCall, termCall));
        List<Message> history = List.of(assistantMsg, toolResponseMsg);

        ToolExecutionResult mockResult = new DefaultToolExecutionResult(history, false);
        when(mockToolCallingManager.executeToolCalls(any(), any())).thenReturn(mockResult);

        Generation generation = new Generation(assistantMsg);
        ChatResponse chatResponse = new ChatResponse(List.of(generation));
        agent.setToolCallChatResponse(chatResponse);

        // When
        String result = agent.act();

        // Then
        Assertions.assertEquals(AgentState.FINISHED, agent.getState(),
                "响应中含 doTerminate 即应触发 FINISHED");
        Assertions.assertTrue(result.contains("doTerminate"));
        Assertions.assertTrue(result.contains("writeFile"));
    }

    // ==================== 3. 状态校验（直接状态注入） ====================

    @Test
    @DisplayName("状态校验 — 从 FINISHED 状态运行抛异常")
    void testRunFromFinishedThrows() {
        ToolCallAgent agent = new ToolCallAgent(toolsWithTerminate);
        agent.setName("TestAgent");
        agent.setMaxSteps(1);
        agent.setState(AgentState.FINISHED);

        Assertions.assertThrows(RuntimeException.class,
                () -> agent.run("再次运行"),
                "从 FINISHED 状态运行应抛出异常");
    }

    @Test
    @DisplayName("状态校验 — 从 ERROR 状态运行抛异常")
    void testRunFromErrorThrows() {
        ToolCallAgent agent = new ToolCallAgent(toolsWithTerminate);
        agent.setName("TestAgent");
        agent.setState(AgentState.ERROR);

        Assertions.assertThrows(RuntimeException.class,
                () -> agent.run("再次运行"),
                "从 ERROR 状态运行应抛出异常");
    }

    @Test
    @DisplayName("状态校验 — 从 RUNNING 状态运行抛异常")
    void testRunFromRunningThrows() {
        ToolCallAgent agent = new ToolCallAgent(toolsWithTerminate);
        agent.setName("TestAgent");
        agent.setState(AgentState.RUNNING);

        Assertions.assertThrows(RuntimeException.class,
                () -> agent.run("并发运行"),
                "从 RUNNING 状态运行应抛出异常");
    }

    // ==================== 4. MCP 工具集成测试 ====================

    @Test
    @DisplayName("合并工具 — MCP Provider 为 null 时仅返回本地工具")
    void testMergedToolsWhenProviderIsNull() {
        ToolCallAgent agent = new ToolCallAgent(toolsWithTerminate);
        agent.setToolCallbackProvider(null);

        // 验证构造不会抛异常，Agent 处于 IDLE
        Assertions.assertEquals(AgentState.IDLE, agent.getState());
        Assertions.assertNotNull(agent.getAvailableTools());
        Assertions.assertEquals(1, agent.getAvailableTools().length,
                "仅 TerminateTool，应为 1 个");
    }

    @Test
    @DisplayName("集成 — setToolCallbackProvider 不影响本地工具")
    void testSetToolCallbackProviderPreservesLocalTools() {
        ToolCallAgent agent = new ToolCallAgent(toolsWithTerminate);

        // MCP Server 未启动 → provider 为 null
        agent.setToolCallbackProvider(null);

        Assertions.assertNotNull(agent.getAvailableTools());
        Assertions.assertTrue(agent.getAvailableTools().length >= 1,
                "至少应有 TerminateTool");
    }

    @Test
    @DisplayName("边界 — maxSteps 为 0 时直接终止")
    void testZeroMaxSteps() {
        StateTestAgent agent = new StateTestAgent("TestAgent", 100, 0);

        String result = agent.run("任何任务");

        // maxSteps=0 → 循环一次都不执行 → 直接因超步数终止
        Assertions.assertEquals(AgentState.FINISHED, agent.getState());
        Assertions.assertTrue(result.contains("已达到最大步数限制"));
    }
}
