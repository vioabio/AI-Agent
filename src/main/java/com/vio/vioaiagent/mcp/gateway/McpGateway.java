package com.vio.vioaiagent.mcp.gateway;

import com.vio.vioaiagent.mcp.protocol.model.ToolCallResult;
import com.vio.vioaiagent.mcp.protocol.model.ToolDefinition;
import com.vio.vioaiagent.mcp.transport.McpTransport;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MCP 网关 — 自定义 MCP 协议栈的顶层门面.
 *
 * <p>这是 Agent 层与 MCP 协议栈交互的唯一入口. 封装了会话管理、
 * 工具发现和工具调用的全部细节, 对外提供简单的方法调用接口.
 *
 * <pre>{@code
 * // 使用方式
 * McpGateway gateway = new McpGateway(sessionManager);
 * gateway.connect("image-search", transport);
 * List<ToolDefinition> tools = gateway.discoverTools("image-search");
 * ToolCallResult result = gateway.invokeTool("image-search", "search", Map.of("query", "cat"));
 * gateway.shutdown();
 * }</pre>
 *
 * @author vio
 */
@Slf4j
public class McpGateway {

    private final McpSessionManager sessionManager;

    public McpGateway(McpSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 连接到指定的 MCP Server 并完成握手.
     *
     * @param connectionId 连接标识
     * @param transport    传输层实例
     */
    public void connect(String connectionId, McpTransport transport) {
        sessionManager.getOrCreateSession(connectionId, transport);
        log.info("MCP 网关已连接: {}", connectionId);
    }

    /**
     * 连接到指定的 MCP Server（指定协议版本）.
     */
    public void connect(String connectionId, McpTransport transport, String protocolId) {
        sessionManager.getOrCreateSession(connectionId, transport, protocolId);
        log.info("MCP 网关已连接: {} (协议: {})", connectionId, protocolId);
    }

    /**
     * 发现指定 Server 上的所有可用工具.
     *
     * @param connectionId 连接标识
     * @return 工具定义列表
     */
    public List<ToolDefinition> discoverTools(String connectionId) {
        McpSession session = sessionManager.getSession(connectionId);
        if (session == null || !session.isReady()) {
            log.warn("会话未就绪: {}", connectionId);
            return Collections.emptyList();
        }
        return session.listTools();
    }

    /**
     * 调用指定工具.
     *
     * @param connectionId 连接标识
     * @param toolName     工具名称
     * @param arguments    工具参数
     * @return 工具调用结果
     */
    public ToolCallResult invokeTool(String connectionId, String toolName,
                                      Map<String, Object> arguments) {
        McpSession session = sessionManager.getSession(connectionId);
        if (session == null || !session.isReady()) {
            return ToolCallResult.error("MCP 会话未就绪: " + connectionId);
        }
        return session.callTool(toolName, arguments);
    }

    /**
     * 断开指定连接.
     */
    public void disconnect(String connectionId) {
        sessionManager.closeSession(connectionId);
        log.info("MCP 网关已断开: {}", connectionId);
    }

    /**
     * 关闭网关, 释放所有连接和资源.
     */
    public void shutdown() {
        sessionManager.closeAll();
        log.info("MCP 网关已关闭");
    }

    /**
     * 获取活跃会话数.
     */
    public int getActiveConnectionCount() {
        return sessionManager.activeSessionCount();
    }
}
