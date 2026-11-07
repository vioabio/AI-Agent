package com.vio.vioaiagent.mcp.transport;

import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcRequest;
import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcResponse;

/**
 * MCP 传输层抽象接口.
 *
 * <p>定义了 MCP 协议栈与底层通信机制的合约. 具体的传输实现
 * （STDIO 子进程、HTTP SSE、WebSocket 等）只需实现此接口即可接入 MCP 协议栈.
 *
 * <p>接口使用同步模型（{@link #send} 阻塞直到收到响应）, 与 Spring AI 的
 * {@code McpSyncClient} 保持一致, 简化调用方逻辑. 未来可扩展异步变体.
 *
 * @author vio
 */
public interface McpTransport {

    /**
     * 建立连接.
     *
     * <p>对于 STDIO 传输, 此方法启动子进程并打开 stdin/stdout.
     * 对于 SSE 传输, 此方法打开 SSE 长连接并获取 session 端点.
     *
     * @throws com.vio.vioaiagent.exception.BusinessException 连接失败时抛出
     */
    void connect();

    /**
     * 发送 JSON-RPC 请求并等待响应（同步阻塞）.
     *
     * @param request JSON-RPC 请求
     * @return JSON-RPC 响应
     * @throws com.vio.vioaiagent.exception.BusinessException 通信失败时抛出
     */
    JsonRpcResponse send(JsonRpcRequest request);

    /**
     * 关闭连接, 释放资源.
     */
    void close();

    /**
     * 查询连接状态.
     *
     * @return 是否已连接
     */
    boolean isConnected();
}
