package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.agent.BaseAgent;
import com.vio.vioaiagent.agent.VioManus;
import com.vio.vioaiagent.app.GameApp;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;

@Tag(name = "AI 智能体", description = "AI 游戏大师 & VioManus 超级智能体接口")
@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private GameApp gameApp;

    // ==================== 同步 REST 端点（可用 axios 调用） ====================

    @Operation(summary = "同步对话（基础）")
    @GetMapping("/game_app/chat/sync")
    public String doChatWithGameAppSync(
            @Parameter(description = "用户消息") String message,
            @Parameter(description = "会话 ID") String chatId) {
        return gameApp.doChat(message, chatId);
    }

    @Operation(summary = "同步对话（RAG 知识库增强）")
    @GetMapping("/game_app/chat/rag/sync")
    public String doChatWithGameAppRagSync(
            @Parameter(description = "用户消息") String message,
            @Parameter(description = "会话 ID") String chatId) {
        return gameApp.doChatWithRag(message, chatId);
    }

    @Operation(summary = "同步对话（工具调用）")
    @GetMapping("/game_app/chat/tools/sync")
    public String doChatWithGameAppToolsSync(
            @Parameter(description = "用户消息") String message,
            @Parameter(description = "会话 ID") String chatId) {
        return gameApp.doChatWithTools(message, chatId);
    }

    @Operation(summary = "同步对话（MCP 服务）")
    @GetMapping("/game_app/chat/mcp/sync")
    public String doChatWithGameAppMcpSync(
            @Parameter(description = "用户消息") String message,
            @Parameter(description = "会话 ID") String chatId) {
        return gameApp.doChatWithMcp(message, chatId);
    }

    // ==================== SSE 流式端点（需用 EventSource，不可用 axios） ====================

    @Operation(summary = "SSE 流式对话（基础）",
            description = "注意：此端点返回 text/event-stream，前端需使用 EventSource 而非 axios 调用")
    @GetMapping(value = "/game_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithGameAppSSE(
            @Parameter(description = "用户消息") String message,
            @Parameter(description = "会话 ID") String chatId) {
        return gameApp.doChatByStream(message, chatId);
    }

    @Operation(summary = "SSE 流式对话（RAG 知识库增强）")
    @GetMapping(value = "/game_app/chat/rag/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithGameAppRagSSE(
            @Parameter(description = "用户消息") String message,
            @Parameter(description = "会话 ID") String chatId) {
        return gameApp.doChatWithRagByStream(message, chatId);
    }

    @Operation(summary = "SSE 流式对话（工具调用）")
    @GetMapping(value = "/game_app/chat/tools/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithGameAppToolsSSE(
            @Parameter(description = "用户消息") String message,
            @Parameter(description = "会话 ID") String chatId) {
        return gameApp.doChatWithToolsByStream(message, chatId);
    }

    @Operation(summary = "SSE 流式对话（MCP 服务）")
    @GetMapping(value = "/game_app/chat/mcp/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithGameAppMcpSSE(
            @Parameter(description = "用户消息") String message,
            @Parameter(description = "会话 ID") String chatId) {
        return gameApp.doChatWithMcpByStream(message, chatId);
    }

    // ==================== 以下端点返回 Spring 内部类型，标记 @Hidden 避免生成无意义的代码 ====================

    @Hidden
    @GetMapping(value = "/game_app/chat/server_sent_event")
    public Flux<ServerSentEvent<String>> doChatWithGameAppServerSentEvent(String message, String chatId) {
        return gameApp.doChatByStream(message, chatId)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }

    @Hidden
    @GetMapping(value = "/game_app/chat/sse_emitter", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter doChatWithGameAppServerSseEmitter(String message, String chatId) {
        SseEmitter sseEmitter = new SseEmitter(180000L);
        gameApp.doChatByStream(message, chatId)
                .subscribe(chunk -> {
                    try {
                        sseEmitter.send(chunk);
                    } catch (IOException e) {
                        sseEmitter.completeWithError(e);
                    }
                }, sseEmitter::completeWithError, sseEmitter::complete);
        return sseEmitter;
    }

    // ==================== SSE 诊断 ====================

    @Hidden
    @GetMapping(value = "/ping_sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter pingSse() {
        SseEmitter emitter = new SseEmitter(10000L);
        new Thread(() -> {
            try {
                for (int i = 1; i <= 3; i++) {
                    emitter.send("SSE 测试消息 " + i + "/3 ✅");
                    Thread.sleep(500);
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }).start();
        return emitter;
    }

    // ==================== Manus 超级智能体 ====================

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel dashscopeChatModel;

    @Autowired(required = false)
    private ToolCallbackProvider toolCallbackProvider;

    @Autowired(required = false)
    private java.util.concurrent.Executor agentReasoningExecutor;

    private final java.util.concurrent.ConcurrentHashMap<String, BaseAgent> activeAgents =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final com.vio.vioaiagent.orchestration.AgentConcurrencyGuard concurrencyGuard =
            new com.vio.vioaiagent.orchestration.AgentConcurrencyGuard(10);

    @Hidden
    @Operation(summary = "SSE 流式调用 VioManus 超级智能体",
            description = "第一个 SSE 事件携带 sessionId（格式：[SESSION:xxxx]），后续是执行步骤。前端拿到 sessionId 后可通过 /manus/stop 手动终止。")
    @GetMapping(value = "/manus/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter doChatWithManus(
            @Parameter(description = "用户任务描述") String message) {
        // 并发控制：超限时快速拒绝
        concurrencyGuard.acquire();

        VioManus vioManus = new VioManus(allTools, toolCallbackProvider, dashscopeChatModel);
        String sessionId = java.util.UUID.randomUUID().toString().substring(0, 8);
        activeAgents.put(sessionId, vioManus);

        SseEmitter emitter = new SseEmitter(300000L);
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                emitter.send("[SESSION:" + sessionId + "]");
                vioManus.runStream(message, emitter);
            } catch (Exception e) {
                activeAgents.remove(sessionId);
                try { emitter.completeWithError(e); } catch (Exception ignored) {}
            } finally {
                activeAgents.remove(sessionId);
                concurrencyGuard.release();
            }
        }, agentReasoningExecutor != null ? agentReasoningExecutor : Runnable::run);
        emitter.onCompletion(() -> activeAgents.remove(sessionId));
        emitter.onTimeout(() -> { activeAgents.remove(sessionId); vioManus.stop(); });
        return emitter;
    }

    @Operation(summary = "手动停止正在运行的 Agent")
    @GetMapping("/manus/stop")
    public String stopManus(
            @Parameter(description = "会话 ID（来自 /manus/chat 返回的第一条消息）") String sessionId) {
        BaseAgent agent = activeAgents.remove(sessionId);
        if (agent != null) {
            agent.stop();
            return "已停止会话: " + sessionId;
        }
        return "未找到运行中的会话: " + sessionId;
    }
}
