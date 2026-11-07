package com.vio.vioaiagent.mcp.protocol;

import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcError;
import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcErrorCode;
import com.vio.vioaiagent.mcp.protocol.model.InitializeRequest;
import com.vio.vioaiagent.mcp.protocol.model.InitializeResult;
import com.vio.vioaiagent.mcp.protocol.model.ServerCapabilities;
import com.vio.vioaiagent.mcp.protocol.model.ToolCallResult;
import com.vio.vioaiagent.mcp.protocol.model.ToolDefinition;
import com.vio.vioaiagent.mcp.protocol.model.ToolListResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * MCP 协议规范实现 — {@link McpProtocolHandler} 的参考实现.
 *
 * <p>提供基于内存的工具注册表和处理器注册表. 不依赖 Spring AI 或任何外部框架,
 * 纯 Java 实现, 可作为嵌入式 MCP 服务端或测试桩使用.
 *
 * <p>使用方式：
 * <pre>{@code
 * McpProtocolSpec spec = new McpProtocolSpec("my-server", "1.0.0");
 * spec.registerTool(
 *     ToolDefinition.of("search", "搜索工具"),
 *     (toolName, args) -> ToolCallResult.success("搜索结果: ...")
 * );
 * InitializeResult result = spec.initialize(InitializeRequest.ofLatest(...));
 * ToolListResult tools = spec.listTools();
 * ToolCallResult callResult = spec.callTool("search", Map.of("query", "test"));
 * }</pre>
 *
 * <p>线程安全：工具注册表使用 ConcurrentHashMap, 支持并发读写.
 *
 * @author vio
 * @see McpProtocolHandler
 */
@Slf4j
public class McpProtocolSpec implements McpProtocolHandler {

    /** 服务端信息 */
    private final Map<String, Object> serverInfo;
    /** 服务端能力 */
    private final ServerCapabilities capabilities;
    /** 工具定义注册表 (toolName → definition) */
    private final Map<String, ToolDefinition> toolRegistry;
    /** 工具处理器注册表 (toolName → handler) */
    private final Map<String, BiFunction<String, Map<String, Object>, ToolCallResult>> handlerRegistry;
    /** 是否已初始化 */
    private volatile boolean initialized;

    /**
     * 构造协议规范实现.
     *
     * @param serverName    服务端名称
     * @param serverVersion 服务端版本
     */
    public McpProtocolSpec(String serverName, String serverVersion) {
        this(serverName, serverVersion, ServerCapabilities.toolsOnly());
    }

    /**
     * 构造协议规范实现（指定能力）.
     *
     * @param serverName    服务端名称
     * @param serverVersion 服务端版本
     * @param capabilities  服务端能力声明
     */
    public McpProtocolSpec(String serverName, String serverVersion, ServerCapabilities capabilities) {
        this.serverInfo = new ConcurrentHashMap<>();
        this.serverInfo.put("name", serverName);
        this.serverInfo.put("version", serverVersion);
        this.capabilities = capabilities;
        this.toolRegistry = new ConcurrentHashMap<>();
        this.handlerRegistry = new ConcurrentHashMap<>();
        this.initialized = false;
    }

    /**
     * 注册一个工具及其处理器.
     *
     * @param definition 工具定义
     * @param handler    工具处理器（接收 toolName 和 arguments, 返回 ToolCallResult）
     */
    public void registerTool(ToolDefinition definition,
                             BiFunction<String, Map<String, Object>, ToolCallResult> handler) {
        toolRegistry.put(definition.name(), definition);
        handlerRegistry.put(definition.name(), handler);
        log.info("注册 MCP 工具: {} — {}", definition.name(), definition.description());
    }

    /**
     * 批量注册工具.
     *
     * @param tools 工具定义和处理器列表
     */
    @SafeVarargs
    public final void registerTools(
            Map.Entry<ToolDefinition, BiFunction<String, Map<String, Object>, ToolCallResult>>... tools) {
        for (Map.Entry<ToolDefinition, BiFunction<String, Map<String, Object>, ToolCallResult>> entry : tools) {
            registerTool(entry.getKey(), entry.getValue());
        }
    }

    // ==================== McpProtocolHandler 实现 ====================

    @Override
    public InitializeResult initialize(InitializeRequest request) {
        log.info("MCP 初始化握手: 客户端协议版本={}", request.protocolVersion());
        initialized = true;
        return InitializeResult.success(capabilities, Collections.unmodifiableMap(serverInfo));
    }

    @Override
    public ServerCapabilities getCapabilities() {
        return capabilities;
    }

    @Override
    public ToolListResult listTools(String cursor) {
        ensureInitialized();
        List<ToolDefinition> tools = new ArrayList<>(toolRegistry.values());
        log.info("返回工具列表: {} 个工具", tools.size());
        // 当前实现不分页, 一次返回所有工具
        return ToolListResult.of(Collections.unmodifiableList(tools));
    }

    @Override
    public ToolCallResult callTool(String toolName, Map<String, Object> arguments) {
        ensureInitialized();
        BiFunction<String, Map<String, Object>, ToolCallResult> handler = handlerRegistry.get(toolName);
        if (handler == null) {
            log.warn("工具未找到: {}", toolName);
            return ToolCallResult.error("工具未找到: " + toolName);
        }
        log.info("调用工具: {} 参数: {}", toolName, arguments);
        try {
            return handler.apply(toolName, arguments);
        } catch (Exception e) {
            log.error("工具执行失败: {} — {}", toolName, e.getMessage(), e);
            return ToolCallResult.error("工具执行失败: " + e.getMessage());
        }
    }

    @Override
    public void shutdown() {
        log.info("MCP 协议规范关闭, 已注册工具数: {}", toolRegistry.size());
        toolRegistry.clear();
        handlerRegistry.clear();
        initialized = false;
    }

    // ==================== 私有方法 ====================

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException(
                    "服务端未初始化, 请先调用 initialize() — 错误码: "
                            + JsonRpcErrorCode.SERVER_NOT_INITIALIZED.getCode());
        }
    }
}
