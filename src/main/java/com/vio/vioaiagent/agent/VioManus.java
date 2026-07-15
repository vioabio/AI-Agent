package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.advisor.MyLoggerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * VioManus — AI 超级智能体
 * <p>
 * 拥有自主规划能力的通用智能体，能够：
 * <ul>
 *   <li>分解复杂任务为多个子步骤</li>
 *   <li>自主选择合适的工具（文件操作、联网搜索、网页抓取、PDF生成、资源下载、终端执行）</li>
 *   <li>根据每步执行结果动态调整后续策略</li>
 *   <li>在任务完成时主动调用 terminate 工具结束执行</li>
 * </ul>
 * <p>
 * 技术架构：
 * <pre>
 *   BaseAgent → ReActAgent → ToolCallAgent → VioManus
 * </pre>
 * <p>
 * 使用方式：
 * <pre>
 *   VioManus vioManus = new VioManus(allTools, dashscopeChatModel);
 *   SseEmitter emitter = vioManus.runStream("帮我搜索宝可梦朱紫的6星太晶坑单刷攻略并生成PDF报告");
 * </pre>
 * <p>
 * 注意：每次请求应创建新的 VioManus 实例，因为智能体维护内部状态，不能跨请求共享。
 *
 * @author vio
 */
public class VioManus extends ToolCallAgent {

    /**
     * 系统提示词 — 定义智能体的角色和能力
     */
    private static final String SYSTEM_PROMPT = """
            You are VioManus, an all-capable AI assistant, aimed at solving any task presented by the user.
            You have various tools at your disposal that you can call upon to efficiently complete complex requests.
            Whether it's searching the web, generating PDF documents, executing terminal commands, downloading resources,
            reading and writing files, or scraping web pages — you can do it all.

            Key capabilities:
            - **Web Search**: Search the internet for real-time information
            - **Web Scraping**: Extract detailed content from any URL
            - **File Operations**: Read and write files on the local system
            - **PDF Generation**: Create professional PDF documents with Chinese text support
            - **Resource Download**: Download files from URLs
            - **Terminal Commands**: Execute system commands for advanced operations

            Always work step by step. Break down complex tasks into manageable parts.
            After completing each tool call, analyze the results and decide the next action.
            Be thorough and proactive — don't wait for the user to specify every detail.
            """;

    /**
     * 下一步提示词 — 引导 AI 主动分解任务并选择工具
     * <p>
     * 这是 Agent 自主规划的关键：每次 Think 阶段都会将此提示词追加到消息列表末尾，
     * 促使 AI 不断推进任务进度。
     */
    private static final String NEXT_STEP_PROMPT = """
            Based on user needs, proactively select the most appropriate tool or combination of tools.
            For complex tasks, you can break down the problem and use different tools step by step to solve it.
            After using each tool, clearly explain the execution results and suggest the next steps.

            Guidelines:
            1. If the user's request is simple and you can answer directly, just respond without using tools.
            2. For multi-step tasks, plan the sequence before acting — search first, then analyze, then generate.
            3. After each tool execution, review the result. If something went wrong, try an alternative approach.
            4. When a task requires information from multiple sources, gather all data before synthesizing.
            5. When the task is fully complete or you determine it cannot be completed, call the `terminate` tool.

            Remember: You are in control. Drive the task forward step by step until it is done.
            """;

    /**
     * 构造 VioManus 超级智能体（仅本地工具）
     *
     * @param allTools           所有注册的本地工具（来自 ToolRegistration）
     * @param dashscopeChatModel DashScope AI 大模型
     */
    public VioManus(ToolCallback[] allTools, ChatModel dashscopeChatModel) {
        this(allTools, null, dashscopeChatModel);
    }

    /**
     * 构造 VioManus 超级智能体（本地工具 + MCP 远程工具）
     * <p>
     * 当 MCP Server（高德地图、图片搜索等）已启动时，通过 ToolCallbackProvider
     * 将远程 MCP 工具与本地工具合并，让 Agent 获得地图搜索、图片搜索等额外能力。
     *
     * @param allTools            所有注册的本地工具（来自 ToolRegistration）
     * @param mcpToolProvider     MCP 工具提供者，可为 null（MCP Server 未启动时）
     * @param dashscopeChatModel  DashScope AI 大模型
     */
    public VioManus(ToolCallback[] allTools, ToolCallbackProvider mcpToolProvider,
                    ChatModel dashscopeChatModel) {
        super(allTools);

        // 基本信息
        this.setName("VioManus");
        this.setSystemPrompt(SYSTEM_PROMPT);
        this.setNextStepPrompt(NEXT_STEP_PROMPT);

        // 最大执行步数（复杂任务可能需要更多步骤）
        this.setMaxSteps(20);

        // 集成 MCP 工具提供者（高德地图、图片搜索等）
        if (mcpToolProvider != null) {
            this.setToolCallbackProvider(mcpToolProvider);
        }

        // 初始化 ChatClient（带日志 Advisor，便于调试观察 Agent 的思考过程）
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }
}