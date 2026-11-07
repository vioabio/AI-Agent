package com.vio.vioaiagent.mcp.protocol.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * MCP 初始化请求.
 *
 * <p>客户端在建立传输连接后, 首先发送此请求与服务端进行能力协商.
 * 对应 JSON-RPC 方法: {@code "initialize"}.
 *
 * @param protocolVersion 客户端支持的 MCP 协议版本（如 "2024-11-05"）
 * @param capabilities    客户端能力声明
 * @param clientInfo      客户端信息（名称、版本等自由字段）
 * @author vio
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InitializeRequest(
        String protocolVersion,
        Map<String, Object> capabilities,
        Map<String, Object> clientInfo) {

    public static final String LATEST_PROTOCOL_VERSION = "2024-11-05";

    /**
     * 创建使用最新协议版本的初始化请求.
     */
    public static InitializeRequest ofLatest(Map<String, Object> capabilities, Map<String, Object> clientInfo) {
        return new InitializeRequest(LATEST_PROTOCOL_VERSION, capabilities, clientInfo);
    }
}
