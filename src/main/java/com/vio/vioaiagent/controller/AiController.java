package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.agent.BaseAgent;
import com.vio.vioaiagent.agent.VioManus;
import com.vio.vioaiagent.app.LoveApp;
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

@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private LoveApp loveApp;

    /**
     * 同步调用 AI 恋爱大师应用
     *
     * @param message 用户消息
     * @param chatId  会话 ID
     * @return AI 回复
     */
    @GetMapping("/love_app/chat/sync")
    public String doChatWithLoveAppSync(String message, String chatId) {
        return loveApp.doChat(message, chatId);
    }

    /**
     * SSE 流式调用 AI 恋爱大师应用
     *
     * @param message 用户消息
     * @param chatId  会话 ID
     * @return 流式响应
     */
    @GetMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithLoveAppSSE(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId);
    }

    /**
     * ServerSentEvent 流式调用 AI 恋爱大师应用
     *
     * @param message 用户消息
     * @param chatId  会话 ID
     * @return ServerSentEvent 流
     */
    @GetMapping(value = "/love_app/chat/server_sent_event")
    public Flux<ServerSentEvent<String>> doChatWithLoveAppServerSentEvent(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }

    /**
     * SseEmitter 流式调用 AI 恋爱大师应用
     *
     * @param message 用户消息
     * @param chatId  会话 ID
     * @return SseEmitter
     */
    @GetMapping(value = "/love_app/chat/sse_emitter", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter doChatWithLoveAppServerSseEmitter(String message, String chatId) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter sseEmitter = new SseEmitter(180000L); // 3 分钟超时
        // 获取 Flux 响应式数据流并且直接通过订阅推送给 SseEmitter
        loveApp.doChatByStream(message, chatId)
                .subscribe(chunk -> {
                    try {
                        sseEmitter.send(chunk);
                    } catch (IOException e) {
                        sseEmitter.completeWithError(e);
                    }
                }, sseEmitter::completeWithError, sseEmitter::complete);
        // 返回
        return sseEmitter;
    }

    /**
     * 同步调用 AI 恋爱大师应用（RAG 知识库增强）
     *
     * @param message 用户消息
     * @param chatId  会话 ID
     * @return AI 回复（基于知识库）
     */
    @GetMapping("/love_app/chat/rag/sync")
    public String doChatWithLoveAppRagSync(String message, String chatId) {
        return loveApp.doChatWithRag(message, chatId);
    }

    /**
     * SSE 流式调用 AI 恋爱大师应用（RAG 知识库增强）
     *
     * @param message 用户消息
     * @param chatId  会话 ID
     * @return 流式响应（基于知识库）
     */
    @GetMapping(value = "/love_app/chat/rag/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithLoveAppRagSSE(String message, String chatId) {
        return loveApp.doChatWithRagByStream(message, chatId);
    }

    /**
     * 同步调用 AI 恋爱大师应用（支持工具调用）
     *
     * @param message 用户消息
     * @param chatId  会话 ID
     * @return AI 回复（可调用工具）
     */
    @GetMapping("/love_app/chat/tools/sync")
    public String doChatWithLoveAppToolsSync(String message, String chatId) {
        return loveApp.doChatWithTools(message, chatId);
    }

    /**
     * SSE 流式调用 AI 恋爱大师应用（支持工具调用）
     *
     * @param message 用户消息
     * @param chatId  会话 ID
     * @return 流式响应（可调用工具）
     */
    @GetMapping(value = "/love_app/chat/tools/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithLoveAppToolsSSE(String message, String chatId) {
        return loveApp.doChatWithToolsByStream(message, chatId);
    }

    /**
     * 同步调用 AI 恋爱大师应用（MCP 服务）
     *
     * @param message 用户消息
     * @param chatId  会话 ID
     * @return AI 回复（可调用 MCP 工具）
     */
    @GetMapping("/love_app/chat/mcp/sync")
    public String doChatWithLoveAppMcpSync(String message, String chatId) {
        return loveApp.doChatWithMcp(message, chatId);
    }

    /**
     * SSE 流式调用 AI 恋爱大师应用（MCP 服务）
     *
     * @param message 用户消息
     * @param chatId  会话 ID
     * @return 流式响应（可调用 MCP 工具）
     */
    @GetMapping(value = "/love_app/chat/mcp/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithLoveAppMcpSSE(String message, String chatId) {
        return loveApp.doChatWithMcpByStream(message, chatId);
    }

    // ==================== SSE 诊断端点 ====================

    /**
     * SSE 连通性测试 — 发送固定内容验证 SSE 链路是否正常
     */
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

    /** 活跃的 Agent 实例，用于手动停止 */
    private final java.util.concurrent.ConcurrentHashMap<String, BaseAgent> activeAgents =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * SSE 流式调用 VioManus 超级智能体
     * <p>
     * 第一个 SSE 事件携带 sessionId（格式：[SESSION:xxxx]），后续是执行步骤。
     * 前端拿到 sessionId 后可通过 /manus/stop 手动终止。
     */
    @GetMapping(value = "/manus/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter doChatWithManus(String message) {
        VioManus vioManus = new VioManus(allTools, toolCallbackProvider, dashscopeChatModel);
        String sessionId = java.util.UUID.randomUUID().toString().substring(0, 8);
        activeAgents.put(sessionId, vioManus);

        // 用自定义 SseEmitter 在最前面注入 sessionId
        SseEmitter emitter = new SseEmitter(300000L);
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                // 第一条消息：sessionId
                emitter.send("[SESSION:" + sessionId + "]");
                // 之后交由 Agent 自己的 runStream 逻辑执行
                vioManus.runStream(message, emitter);
            } catch (Exception e) {
                activeAgents.remove(sessionId);
                try { emitter.completeWithError(e); } catch (Exception ignored) {}
            } finally {
                activeAgents.remove(sessionId);
            }
        });
        emitter.onCompletion(() -> activeAgents.remove(sessionId));
        emitter.onTimeout(() -> { activeAgents.remove(sessionId); vioManus.stop(); });
        return emitter;
    }

    /**
     * 手动停止正在运行的 Agent
     */
    @GetMapping("/manus/stop")
    public String stopManus(String sessionId) {
        BaseAgent agent = activeAgents.remove(sessionId);
        if (agent != null) {
            agent.stop();
            return "已停止会话: " + sessionId;
        }
        return "未找到运行中的会话: " + sessionId;
    }
}
