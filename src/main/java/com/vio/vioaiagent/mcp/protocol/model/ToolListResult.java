package com.vio.vioaiagent.mcp.protocol.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * MCP 工具列表结果.
 *
 * <p>对应 JSON-RPC 方法 {@code "tools/list"} 的响应.
 * 支持基于 cursor 的分页 — 当工具数量较多时, 服务端可分批返回.
 *
 * @param tools      当前页的工具定义列表
 * @param nextCursor 下一页的游标, {@code null} 表示没有更多页
 * @author vio
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolListResult(List<ToolDefinition> tools, String nextCursor) {

    /**
     * 创建不分页的工具列表结果（一次返回所有工具）.
     */
    public static ToolListResult of(List<ToolDefinition> tools) {
        return new ToolListResult(tools, null);
    }
}
