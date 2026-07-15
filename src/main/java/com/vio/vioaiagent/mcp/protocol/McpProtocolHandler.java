package com.vio.vioaiagent.mcp.protocol;

import com.vio.vioaiagent.mcp.protocol.model.InitializeRequest;
import com.vio.vioaiagent.mcp.protocol.model.InitializeResult;
import com.vio.vioaiagent.mcp.protocol.model.ServerCapabilities;
import com.vio.vioaiagent.mcp.protocol.model.ToolCallResult;
import com.vio.vioaiagent.mcp.protocol.model.ToolListResult;

import java.util.Collections;
import java.util.Map;

/**
 * MCP 协议处理器接口 — 定义 MCP 协议的核心生命周期方法.
 *
 * <p>这是整个自定义 MCP 协议栈的中心接口. 所有 MCP 协议的语义操作都通过此接口定义：
 * <ol>
 *   <li>{@link #initialize(InitializeRequest)} — 握手: 交换协议版本和能力</li>
 *   <li>{@link #listTools(String)} — 工具发现: 获取可用工具列表</li>
 *   <li>{@link #callTool(String, Map)} — 工具调用: 执行具体工具</li>
 *   <li>{@link #shutdown()} — 优雅关闭</li>
 * </ol>
 *
 * <p>面试话术: "MCP 的核心价值是统一了工具发现和调用的标准接口.
 * 我们的 McpProtocolHandler 定义了 initialize → tools/list → tools/call
 * 的标准流程, 任何遵循该接口的实现都可以被 Agent 自动发现和调用."
 *
 * @author vio
 * @see McpProtocolSpec 参考实现
 */
public interface McpProtocolHandler {

    /**
     * 握手初始化 — 面试高频考点.
     *
     * <p>客户端首先发送 initialize 请求, 携带协议版本号和客户端能力声明；
     * 服务端验证后返回服务端能力列表、协议版本和服务器信息.
     *
     * @param request 初始化请求（含客户端协议版本和能力声明）
     * @return 初始化结果（含服务端能力和协议版本）
     */
    InitializeResult initialize(InitializeRequest request);

    /**
     * 查询服务端能力.
     *
     * @return 当前服务端的能力声明
     */
    ServerCapabilities getCapabilities();

    /**
     * 工具发现 — 面试高频考点.
     *
     * <p>客户端调用此方法获取服务端暴露的所有工具列表.
     * 支持基于游标的分页, 当工具数量较多时可分批返回.
     *
     * @param cursor 分页游标, {@code null} 或空字符串表示首页
     * @return 工具列表结果（含当前页工具和下一页游标）
     */
    ToolListResult listTools(String cursor);

    /**
     * 工具发现（不分页, 一次返回所有工具）.
     */
    default ToolListResult listTools() {
        return listTools(null);
    }

    /**
     * 工具调用 — 面试高频考点.
     *
     * <p>客户端调用此方法执行指定工具, 传入工具名和参数,
     * 服务端执行后返回结果或错误.
     *
     * @param toolName  工具名称
     * @param arguments 工具参数
     * @return 工具调用结果（含内容列表和错误标记）
     */
    ToolCallResult callTool(String toolName, Map<String, Object> arguments);

    /**
     * 优雅关闭 — 释放资源、断开连接.
     */
    void shutdown();

    /**
     * 心跳检测, 确认服务端仍然可用.
     */
    default void ping() {
        // 默认实现为空, 子类可覆盖提供实际的心跳逻辑
    }
}
