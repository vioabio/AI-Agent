package com.vio.vioaiagent.mcp.protocol.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * MCP 初始化结果.
 *
 * <p>服务端在响应 initialize 请求时返回, 包含协商后的协议版本和服务端能力.
 *
 * @param protocolVersion 协商后使用的协议版本
 * @param capabilities    服务端能力声明
 * @param serverInfo      服务端信息（名称、版本等自由字段）
 * @author vio
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InitializeResult(
        String protocolVersion,
        ServerCapabilities capabilities,
        Map<String, Object> serverInfo) {

    public static final String LATEST_PROTOCOL_VERSION = "2024-11-05";

    /**
     * 创建使用最新协议版本的成功结果.
     */
    public static InitializeResult success(ServerCapabilities capabilities, Map<String, Object> serverInfo) {
        return new InitializeResult(LATEST_PROTOCOL_VERSION, capabilities, serverInfo);
    }
}
