package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.agent.VioManus;
import com.vio.vioaiagent.app.LoveApp;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
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
    @GetMapping(value = "/love_app/chat/sse_emitter")
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

    // ==================== Manus 超级智能体 ====================

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel dashscopeChatModel;

    /**
     * SSE 流式调用 VioManus 超级智能体
     * <p>
     * 智能体拥有自主规划能力，能将复杂任务分解为多步骤并逐步执行。
     * 每次请求创建新的 VioManus 实例（Agent 有状态，不可跨请求共享）。
     *
     * @param message 用户输入/任务描述
     * @return SseEmitter 实时推送每步执行结果（5 分钟超时）
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message) {
        VioManus vioManus = new VioManus(allTools, dashscopeChatModel);
        return vioManus.runStream(message);
    }
}
