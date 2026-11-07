package com.vio.vioaiagent.mcp.transport;

import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcCodec;

/**
 * 传输工厂 — 创建 MCP 传输实例的静态工厂方法.
 *
 * <p>不依赖 Spring 容器, 可直接在测试或程序化场景中使用.
 *
 * @author vio
 */
public final class TransportFactory {

    private TransportFactory() {
        // 工具类, 禁止实例化
    }

    /**
     * 创建 STDIO 传输实例.
     *
     * @param codec  JSON-RPC 编解码器
     * @param params 子进程启动参数
     * @return STDIO 传输实例（未连接）
     */
    public static McpTransport createStdio(JsonRpcCodec codec, StdioTransport.ServerParameters params) {
        return new StdioTransport(codec, params);
    }

    /**
     * 创建 SSE 传输实例（使用默认端点 /mcp/sse）.
     *
     * @param codec   JSON-RPC 编解码器
     * @param baseUrl MCP 服务端基础 URL
     * @return SSE 传输实例（未连接）
     */
    public static McpTransport createSse(JsonRpcCodec codec, String baseUrl) {
        return new SseTransport(codec, baseUrl);
    }

    /**
     * 创建 SSE 传输实例（自定义端点）.
     *
     * @param codec       JSON-RPC 编解码器
     * @param baseUrl     MCP 服务端基础 URL
     * @param sseEndpoint 自定义 SSE 端点路径
     * @return SSE 传输实例（未连接）
     */
    public static McpTransport createSse(JsonRpcCodec codec, String baseUrl, String sseEndpoint) {
        return new SseTransport(codec, baseUrl, sseEndpoint);
    }
}
