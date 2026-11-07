package com.vio.vioaiagent.mcp.gateway;

import com.vio.vioaiagent.exception.BusinessException;
import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcErrorCode;
import com.vio.vioaiagent.mcp.protocol.McpProtocolHandler;
import com.vio.vioaiagent.mcp.protocol.model.InitializeRequest;
import com.vio.vioaiagent.mcp.protocol.model.ToolCallResult;
import com.vio.vioaiagent.mcp.protocol.model.ToolDefinition;
import com.vio.vioaiagent.mcp.protocol.model.ToolListResult;
import com.vio.vioaiagent.mcp.transport.McpTransport;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP 会话 — 封装一个 MCP 连接的完整状态.
 *
 * <p>每个 MCP Server 连接对应一个 McpSession, 管理：
 * <ul>
 *   <li>传输层连接（McpTransport）</li>
 *   <li>协议处理器（McpProtocolHandler）</li>
 *   <li>会话状态（CREATED → INITIALIZING → READY → CLOSED）</li>
 *   <li>工具缓存（避免重复请求 tools/list）</li>
 * </ul>
 *
 * <p>线程安全：状态使用 AtomicReference 管理.
 *
 * @author vio
 */
@Slf4j
public class McpSession {

    /** 会话状态枚举 */
    public enum State { CREATED, INITIALIZING, READY, CLOSED }

    @Getter private final String sessionId;
    @Getter private final String connectionId;
    private final McpTransport transport;
    private final McpProtocolHandler handler;
    private final AtomicReference<State> state;

    @Getter private volatile List<ToolDefinition> cachedTools;
    @Getter private final Instant createdAt;
    @Getter private volatile Instant lastActivityAt;

    /**
     * 构造会话（不建立连接, 需调用 {@link #initialize()}）.
     */
    public McpSession(String connectionId, McpTransport transport, McpProtocolHandler handler) {
        this.sessionId = UUID.randomUUID().toString().substring(0, 8);
        this.connectionId = connectionId;
        this.transport = transport;
        this.handler = handler;
        this.state = new AtomicReference<>(State.CREATED);
        this.cachedTools = Collections.emptyList();
        this.createdAt = Instant.now();
        this.lastActivityAt = Instant.now();
    }

    /**
     * 初始化会话 — 建立传输连接 + MCP 握手.
     */
    public void initialize() {
        if (!state.compareAndSet(State.CREATED, State.INITIALIZING)) {
            throw new BusinessException("会话状态异常: 期望 CREATED, 实际 " + state.get());
        }
        try {
            transport.connect();
            handler.initialize(InitializeRequest.ofLatest(
                    Map.of(), Map.of("name", "vio-ai-agent", "version", "1.0")
            ));
            cachedTools = handler.listTools().tools();
            state.set(State.READY);
            lastActivityAt = Instant.now();
            log.info("MCP 会话已就绪: connectionId={}, sessionId={}, 工具数={}",
                    connectionId, sessionId, cachedTools.size());
        } catch (Exception e) {
            state.set(State.CLOSED);
            throw new BusinessException(
                    "MCP 会话初始化失败 [" + connectionId + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 获取可用工具列表（优先使用缓存）.
     */
    public List<ToolDefinition> listTools() {
        ensureReady();
        // 每次调用工具列表时刷新缓存
        ToolListResult result = handler.listTools();
        cachedTools = result.tools();
        touch();
        return cachedTools;
    }

    /**
     * 调用工具.
     */
    public ToolCallResult callTool(String toolName, Map<String, Object> arguments) {
        ensureReady();
        touch();
        return handler.callTool(toolName, arguments);
    }

    /**
     * 关闭会话, 释放资源.
     */
    public void close() {
        State oldState = state.getAndSet(State.CLOSED);
        if (oldState == State.CLOSED) {
            return; // 已经关闭
        }
        try {
            handler.shutdown();
        } catch (Exception ignored) {
        }
        try {
            transport.close();
        } catch (Exception ignored) {
        }
        log.info("MCP 会话已关闭: connectionId={}, sessionId={}", connectionId, sessionId);
    }

    /** 当前会话状态 */
    public State getState() {
        return state.get();
    }

    /** 是否处于就绪状态 */
    public boolean isReady() {
        return state.get() == State.READY;
    }

    private void ensureReady() {
        if (state.get() != State.READY) {
            throw new BusinessException(
                    "MCP 会话未就绪 [" + connectionId + "], 当前状态: " + state.get());
        }
    }

    private void touch() {
        lastActivityAt = Instant.now();
    }
}
