package com.vio.vioaiagent.mcp.protocol.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * MCP 工具定义.
 *
 * <p>描述一个可供 Agent 调用的工具, 包含名称、描述和输入参数 JSON Schema.
 * 对应 JSON-RPC 方法 {@code "tools/list"} 的响应条目.
 *
 * @param name        工具名称（唯一标识）
 * @param description 工具功能描述（供 LLM 理解用途）
 * @param inputSchema 输入参数的 JSON Schema 定义
 * @author vio
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {

    /**
     * 创建不带 Schema 的简化工具定义（用于快速注册）.
     */
    public static ToolDefinition of(String name, String description) {
        return new ToolDefinition(name, description, null);
    }
}
